package org.example.memosm.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** All UI sync managers and WorkManager replay share the same per-account lock. */
object AccountSyncCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withAccountLock(
        accountId: String,
        action: suspend () -> T,
    ): T = locks.getOrPut(accountId) { Mutex() }.withLock { action() }
}
