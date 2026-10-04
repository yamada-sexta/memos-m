package org.example.memosm.data.backup

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes restore with cooperating storage writers; nested repository writes are reentrant. */
object BackupCoordinator {
    private val mutex = Mutex()
    private class Held : AbstractCoroutineContextElement(Key) {
        var active = true
        companion object Key : CoroutineContext.Key<Held>
    }
    private val hooks = CopyOnWriteArrayList<suspend (Boolean) -> Unit>()
    private val _restoring = MutableStateFlow(false)
    val restoring = _restoring.asStateFlow()
    val recoveryError = MutableStateFlow<String?>(null)

    fun register(hook: suspend (Boolean) -> Unit): () -> Unit {
        hooks += hook
        return { hooks -= hook }
    }

    suspend fun <T> withStorageLock(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (currentCoroutineContext()[Held]?.active == true) return action()
        return mutex.withLock {
            check(recoveryError.value == null) { "Finish the interrupted restore first" }
            withContext(Held()) { try { action() } finally { currentCoroutineContext()[Held]?.active = false } }
        }
    }

    suspend fun <T> restore(action: suspend () -> T): T {
        check(_restoring.compareAndSet(false, true)) { "A restore is already running" }
        try {
            if (hooks.isNotEmpty()) withContext(Dispatchers.Main) { hooks.forEach { it(true) } }
            return mutex.withLock { withContext(Held()) { try { action() } finally { currentCoroutineContext()[Held]?.active = false } } }
        } finally {
            _restoring.value = false
            if (recoveryError.value == null && hooks.isNotEmpty()) withContext(NonCancellable + Dispatchers.Main) { hooks.forEach { it(false) } }
        }
    }
}
