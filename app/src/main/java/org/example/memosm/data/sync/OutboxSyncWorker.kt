package org.example.memosm.data.sync

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentUploadQueue

/** The sole outbound sync entrypoint. UI, startup and connectivity only enqueue this work. */
class OutboxSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            BackupCoordinator.awaitStartupRecovery()
            val accountId = inputData.getString(ACCOUNT_ID) ?: return@withContext Result.failure()

            fun retryOrSchedule(): Result {
                if (inputData.getBoolean(RETRY, false)) return Result.retry()
                WorkManagerSyncWorkScheduler(applicationContext).scheduleRetry(accountId)
                return Result.success()
            }
            val koin =
                org.koin.core.context.GlobalContext
                    .get()
            val settings = koin.get<DataStoreManager>()
            val account = settings.getAccounts().firstOrNull { it.id == accountId } ?: return@withContext Result.success()
            val generation = BackupCoordinator.generation

            suspend fun isCurrent(): Boolean =
                !isStopped && !BackupCoordinator.restoring.value &&
                    BackupCoordinator.recoveryError.value == null && BackupCoordinator.generation == generation &&
                    settings.getAccounts().any {
                        it.id == accountId && it.accessToken == account.accessToken &&
                            it.hostUrl == account.hostUrl
                    }
            if (!isCurrent()) return@withContext retryOrSchedule()
            try {
                AccountSyncCoordinator.withAccountLock(accountId) {
                    val repository = koin.get<SyncRepository>()
                    val uploads = koin.get<AttachmentUploadQueue>()
                    if (repository.getOps(accountId).isEmpty() && !uploads.hasPending(accountId)) {
                        return@withAccountLock Result.success()
                    }
                    // Android's Internet validation is unrelated to reachability of a private Memos server.
                    if (applicationContext.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
                        return@withAccountLock retryOrSchedule()
                    }
                    val client =
                        koin
                            .get<OkHttpClient>()
                            .newBuilder()
                            .addInterceptor(AuthInterceptor(account.accessToken))
                            .build()
                    val api = MemosApiFactory.create(account.hostUrl, client)
                    val outcome =
                        drainOutbox(
                            accountId,
                            api,
                            repository,
                            koin.get<MemoCacheRepository>(),
                            uploads,
                            StreamingAttachmentApi(client, account.hostUrl, api.constants.apiPath),
                            koin.get<SyncAuditLogger>(),
                            ::isCurrent,
                        )
                    if (!isCurrent()) return@withAccountLock retryOrSchedule()
                    if (repository.getOps(accountId).isEmpty() && !uploads.hasPending(accountId)) {
                        BackupCoordinator.withStorageLock {
                            if (isCurrent()) settings.saveLastSyncTime(accountId, System.currentTimeMillis())
                        }
                    }
                    if (outcome.retry) retryOrSchedule() else Result.success()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                koin.get<SyncAuditLogger>().record(accountId, "WORKER", "RETRY", detailCode = error.javaClass.simpleName)
                retryOrSchedule()
            }
        }

    companion object {
        const val ACCOUNT_ID = "account_id"
        const val RETRY = "retry"
    }
}
