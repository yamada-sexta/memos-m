package org.example.memosm.data.media

import android.content.Context
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
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.MemoCacheDatabase

/**
 * Replays an account's queued attachment uploads after the UI process is gone.
 * The account id comes from WorkManager input, but the account/token are read
 * from DataStore at execution time so a removed account can never be replayed.
 */
class AttachmentUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val accountId = inputData.getString(ACCOUNT_ID) ?: return@withContext Result.failure()
        val dataStore = org.koin.core.context.GlobalContext.get().get<DataStoreManager>()
        val account = dataStore.getAccounts().firstOrNull { it.id == accountId }
            ?: return@withContext Result.success()
        val database = MemoCacheDatabase.getInstance(applicationContext)
        val queue = AttachmentUploadQueue(
            applicationContext,
            database.attachmentUploadDao(),
            SyncAuditLogger(AuditDatabase.getInstance(applicationContext).syncAuditDao())
        )
        val api = MemosApiFactory.create(
            account.hostUrl,
            OkHttpClient.Builder().addInterceptor(AuthInterceptor(account.accessToken)).build()
        )
        val streamingApi = StreamingAttachmentApi(
            OkHttpClient.Builder().addInterceptor(AuthInterceptor(account.accessToken)).build(),
            account.hostUrl
        )

        try {
            queue.replay(accountId, api, streamingApi)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // replay() drops permanently failing rows itself and only
            // rethrows transient failures, so any exception here means retry.
            Result.retry()
        }
    }

    companion object {
        const val ACCOUNT_ID = "account_id"
    }
}
