package org.example.memosm.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Schedules one durable, account-isolated background replay request. */
interface SyncWorkScheduler {
    fun schedule(accountId: String)

    fun cancel(accountId: String)
}

class WorkManagerSyncWorkScheduler(context: Context) : SyncWorkScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun schedule(accountId: String) {
        if (accountId.isBlank()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<OutboxSyncWorker>()
            .setInputData(workDataOf(OutboxSyncWorker.ACCOUNT_ID to accountId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName(accountId))
            .build()
        workManager.enqueueUniqueWork(workName(accountId), ExistingWorkPolicy.KEEP, request)
        // Durable fallback for process death: the one-time request above fires
        // as soon as the network is back, but once it has run nothing re-arms
        // it - a process that dies before connectivity returns would leave the
        // outbox untouched until the next app launch. The periodic request
        // guarantees a replay at least every few hours while the network is up.
        // UPDATE (not KEEP) so changed intervals/constraints propagate to the
        // already-enqueued request on app upgrades.
        val periodic = PeriodicWorkRequestBuilder<OutboxSyncWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setInputData(workDataOf(OutboxSyncWorker.ACCOUNT_ID to accountId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(periodicWorkName(accountId))
            .build()
        workManager.enqueueUniquePeriodicWork(
            periodicWorkName(accountId), ExistingPeriodicWorkPolicy.UPDATE, periodic
        )
    }

    override fun cancel(accountId: String) {
        workManager.cancelUniqueWork(workName(accountId))
        workManager.cancelUniqueWork(periodicWorkName(accountId))
    }

    companion object {
        fun workName(accountId: String) = "memosm-outbox-$accountId"
        fun periodicWorkName(accountId: String) = "memosm-outbox-periodic-$accountId"
    }
}
