package org.example.memosm.data.sync

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SyncWorkSchedulerTest {
    private lateinit var context: Context
    private lateinit var work: WorkManager
    private lateinit var scheduler: WorkManagerSyncWorkScheduler
    private val attempts = mutableListOf<Boolean>()

    @Before fun open() {
        context = ApplicationProvider.getApplicationContext()
        val factory =
            object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ) = object : Worker(appContext, workerParameters) {
                    override fun doWork(): Result {
                        val retry = inputData.getBoolean(OutboxSyncWorker.RETRY, false)
                        attempts += retry
                        return if (retry) Result.retry() else Result.success()
                    }
                }
            }
        val config =
            Configuration
                .Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        work = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncWorkScheduler(context)
    }

    @After fun close() {
        work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun unique(name: String): List<WorkInfo> = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)

    @Test fun localServerWorkDoesNotRequireValidatedInternet() {
        scheduler.schedule("A")
        scheduler.scheduleRetry("A")
        val requests = work.getWorkInfosByTag(WorkManagerSyncWorkScheduler.workName("A")).get(10, TimeUnit.SECONDS)
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.constraints.requiredNetworkType == NetworkType.NOT_REQUIRED })
        assertEquals(WorkInfo.State.SUCCEEDED, unique(WorkManagerSyncWorkScheduler.drainWorkName("A")).single().state)
    }

    @Test fun newImmediateSignalRunsWhileBackgroundRetryIsBackingOff() {
        scheduler.scheduleRetry("A")
        val retry = unique(WorkManagerSyncWorkScheduler.retryWorkName("A")).single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(retry.id)
        val backingOff = unique(WorkManagerSyncWorkScheduler.retryWorkName("A")).single()
        assertEquals(WorkInfo.State.ENQUEUED, backingOff.state)
        assertEquals(1, backingOff.runAttemptCount)
        scheduler.schedule("A")
        assertEquals(WorkInfo.State.SUCCEEDED, unique(WorkManagerSyncWorkScheduler.drainWorkName("A")).single().state)
        assertTrue(attempts.contains(false))
        assertEquals(WorkInfo.State.ENQUEUED, unique(WorkManagerSyncWorkScheduler.retryWorkName("A")).single().state)
    }

    @Test fun upgradeCancelsLegacyValidatedNetworkChainsBeforeSchedulingNewDrain() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val legacy = OneTimeWorkRequestBuilder<OutboxSyncWorker>().setConstraints(constraints).build()
        val legacyUpload = OneTimeWorkRequestBuilder<OutboxSyncWorker>().setConstraints(constraints).build()
        work
            .enqueueUniqueWork(WorkManagerSyncWorkScheduler.workName("A"), ExistingWorkPolicy.KEEP, legacy)
            .result
            .get(10, TimeUnit.SECONDS)
        work
            .enqueueUniqueWork("memosm-attachment-upload-A", ExistingWorkPolicy.KEEP, legacyUpload)
            .result
            .get(10, TimeUnit.SECONDS)
        scheduler.schedule("A")
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoById(legacy.id).get(10, TimeUnit.SECONDS)!!.state)
        assertEquals(WorkInfo.State.CANCELLED, work.getWorkInfoById(legacyUpload.id).get(10, TimeUnit.SECONDS)!!.state)
        assertEquals(WorkInfo.State.SUCCEEDED, unique(WorkManagerSyncWorkScheduler.drainWorkName("A")).single().state)
    }

    @Test fun cancellingOneAccountCancelsImmediateRetryAndPeriodicWorkOnlyForThatAccount() {
        scheduler.schedule("A")
        scheduler.scheduleRetry("A")
        scheduler.schedule("B")
        scheduler.scheduleRetry("B")
        scheduler.cancel("A")
        val accountA = work.getWorkInfosByTag(WorkManagerSyncWorkScheduler.workName("A")).get(10, TimeUnit.SECONDS)
        val accountB = work.getWorkInfosByTag(WorkManagerSyncWorkScheduler.workName("B")).get(10, TimeUnit.SECONDS)
        assertTrue(accountA.all { it.state.isFinished })
        assertFalse(accountB.all { it.state.isFinished })
    }
}
