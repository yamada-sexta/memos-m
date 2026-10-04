package org.example.memosm.data.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All UI sync managers and WorkManager replay share the same per-account lock. */
object AccountSyncCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withAccountLock(accountId: String, action: suspend () -> T): T =
        org.example.memosm.data.backup.BackupCoordinator.withStorageLock {
            locks.getOrPut(accountId) { Mutex() }.withLock { action() }
        }
}
