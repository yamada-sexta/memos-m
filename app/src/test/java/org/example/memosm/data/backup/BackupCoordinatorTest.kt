package org.example.memosm.data.backup

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupCoordinatorTest {
    @Test fun `startup blocks writers while recovery can make nested storage changes`() = runBlocking {
        BackupCoordinator.beginStartupRecovery()
        try {
            var writerRan = false
            val writer = launch(start = CoroutineStart.UNDISPATCHED) {
                BackupCoordinator.withStorageLock { writerRan = true }
            }
            assertFalse(writerRan)
            var recovered = false
            BackupCoordinator.restore { BackupCoordinator.withStorageLock { recovered = true } }
            assertTrue(recovered)
            assertFalse(writerRan)
            BackupCoordinator.finishStartupRecovery()
            writer.join()
            assertTrue(writerRan)
        } finally { BackupCoordinator.finishStartupRecovery() }
    }
}
