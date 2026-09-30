package org.example.memosm.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApi
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.model.Account
import retrofit2.HttpException

/**
 * Replays an account's persisted outbox after the UI process is gone. The
 * account id comes from WorkManager input, but the account/token are read from
 * DataStore at execution time so a removed account can never be replayed.
 *
 * Per-op replay semantics live in [OpReplayExecutor] (shared with SyncManager);
 * this worker only adds WorkManager concerns: retry/failure results and a
 * conflict callback that stays a no-op (there is no UI to resolve with).
 */
class OutboxSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val accountId = inputData.getString(ACCOUNT_ID) ?: return@withContext Result.failure()
        val koin = org.koin.core.context.GlobalContext.get()
        val dataStore = koin.get<DataStoreManager>()
        val account = dataStore.getAccounts().firstOrNull { it.id == accountId }
            ?: return@withContext Result.success()
        val database = MemoCacheDatabase.getInstance(applicationContext)
        val repository = SyncRepository(database.pendingOpDao())
        val audit = SyncAuditLogger(AuditDatabase.getInstance(applicationContext).syncAuditDao())
        // Reuse the app's shared client (connection pool, timeouts) and only
        // add this account's auth interceptor on top via newBuilder().
        val httpClient = koin.get<OkHttpClient>().newBuilder()
            .addInterceptor(AuthInterceptor(account.accessToken))
            .build()
        val api = MemosApiFactory.create(account.hostUrl, httpClient)
        val executor = OpReplayExecutor(
            api = api,
            repository = repository,
            memoCacheRepository = MemoCacheRepository(database.memoDao()),
            auditLogger = audit,
            accountId = accountId,
            attachmentUploadQueueProvider = {
                AttachmentUploadQueue(applicationContext, database.attachmentUploadDao(), audit)
            }
        )

        try {
            var ops = repository.getOps(accountId)
            var index = 0
            while (index < ops.size) {
                if (isStopped) return@withContext Result.retry()
                // Reload after each acknowledgement so temporary-name remaps
                // and rebased follow-ups are used immediately.
                val op = repository.getOp(ops[index].id) ?: run {
                    ops = repository.getOps(accountId)
                    index++
                    continue
                }
                // Ops the server rejected with a 4xx will never succeed by
                // retrying - leave them queued (visible in the UI) until the
                // user discards or force-syncs.
                if (op.permanentlyFailed) {
                    index++
                    continue
                }
                try {
                    val success = executor.replay(op)
                    if (success) {
                        repository.deleteOp(op.id)
                        audit.record(accountId, "SYNC", "SUCCESS", op.type, op.memoName)
                        ops = repository.getOps(accountId)
                        continue
                    }
                    index++
                } catch (error: Exception) {
                    // 4xx means the server deterministically rejected the op -
                    // retrying is pointless, mark it permanent. 5xx and
                    // transport errors are transient and retried by WorkManager.
                    // 408/429 are transient too (timeout / rate limit).
                    val permanent = error is HttpException &&
                        error.code() in 400..499 && error.code() !in setOf(408, 429)
                    repository.markFailed(
                        op.id, op.attemptCount + 1, error.message,
                        System.currentTimeMillis(), permanent
                    )
                    audit.record(
                        accountId, "SYNC",
                        if (permanent) "REJECTED" else "RETRY",
                        op.type, op.memoName, errorCode(error)
                    )
                    if (!permanent) return@withContext Result.retry()
                    index++
                }
            }
            dataStore.saveLastSyncTime(accountId, System.currentTimeMillis())
            runPreDownload(account, api, this)
            Result.success()
        } catch (error: Exception) {
            audit.record(accountId, "WORKER", "RETRY", detailCode = errorCode(error))
            Result.retry()
        }
    }

    /**
     * After a successful replay pass (including the empty-outbox periodic run),
     * run one incremental pre-download pass in the background. Reuses
     * [PreDownloadManager]'s own gates (settings toggles, persisted cooldown,
     * wifi-only); connectivity is checked straight against ConnectivityManager
     * so no UI-scoped observer state is involved. Best-effort: failures here
     * never fail the worker's result.
     */
    private suspend fun runPreDownload(account: Account, api: MemosApi, scope: CoroutineScope) {
        try {
            val connectivityManager = applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            fun capabilities() =
                connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) {
                return
            }
            // The creator filter needs the user identity; there is no UI
            // session in a worker, so fetch it with the account's own client.
            val user = api.getCurrentSession().user ?: return
            val koin = org.koin.core.context.GlobalContext.get()
            val manager = PreDownloadManager(
                scope = scope,
                memoCacheRepository = koin.get(),
                dataStoreManager = koin.get(),
                attachmentCacheManager = koin.get(),
                attachmentCacheStore = koin.get(),
                apiProvider = { api },
                accountIdProvider = { account.id },
                userProvider = { user },
                hostUrlProvider = { account.hostUrl },
                tokenProvider = { account.accessToken },
                isOnlineProvider = {
                    capabilities()?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                },
                isWifiProvider = {
                    capabilities()?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }
            )
            manager.runAutoDownloadBlocking()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("OutboxSyncWorker", "background pre-download failed", e)
        }
    }

    private fun errorCode(error: Exception): String = when (error) {
        is HttpException -> "http_${error.code()}"
        else -> error.javaClass.simpleName.take(64)
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
    }
}
