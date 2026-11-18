package org.example.memosm.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

interface SyncWorkScheduler {
    fun schedule(accountId: String)

    fun cancel(accountId: String)
}

/** Immediate signals never queue behind a background retry's backoff. Both execute the same worker. */
class WorkManagerSyncWorkScheduler(
    context: Context,
) : SyncWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(accountId: String) {
        if (accountId.isBlank()) return
        // Upgrade from the old CONNECTED-constrained chain, which can remain blocked on a local-only LAN.
        workManager.cancelUniqueWork(workName(accountId))
        workManager.cancelUniqueWork("memosm-attachment-upload-$accountId")
        val request =
            OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                .setInputData(workDataOf(OutboxSyncWorker.ACCOUNT_ID to accountId))
                .addTag(workName(accountId))
                .build()
        workManager.enqueueUniqueWork(drainWorkName(accountId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        val periodic =
            PeriodicWorkRequestBuilder<OutboxSyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setInputData(workDataOf(OutboxSyncWorker.ACCOUNT_ID to accountId, OutboxSyncWorker.RETRY to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(workName(accountId))
                .build()
        workManager.enqueueUniquePeriodicWork(periodicWorkName(accountId), ExistingPeriodicWorkPolicy.UPDATE, periodic)
    }

    fun scheduleRetry(accountId: String) {
        val request =
            OneTimeWorkRequestBuilder<OutboxSyncWorker>()
                .setInputData(workDataOf(OutboxSyncWorker.ACCOUNT_ID to accountId, OutboxSyncWorker.RETRY to true))
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(workName(accountId))
                .build()
        // Appending closes the race with an existing retry worker that is just finishing.
        workManager.enqueueUniqueWork(retryWorkName(accountId), ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun cancel(accountId: String) {
        workManager.cancelUniqueWork(drainWorkName(accountId))
        workManager.cancelUniqueWork(retryWorkName(accountId))
        workManager.cancelUniqueWork(periodicWorkName(accountId))
        workManager.cancelUniqueWork(workName(accountId))
        workManager.cancelUniqueWork("memosm-attachment-upload-$accountId")
    }

    companion object {
        /** Common account tag, including immediate, delayed, and periodic work. */
        fun workName(accountId: String) = "memosm-outbox-$accountId"

        fun drainWorkName(accountId: String) = "${workName(accountId)}-drain"

        fun retryWorkName(accountId: String) = "${workName(accountId)}-retry"

        fun periodicWorkName(accountId: String) = "memosm-outbox-periodic-$accountId"
    }
}
