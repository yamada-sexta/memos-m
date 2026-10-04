package org.example.memosm.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Executes real suspend test handlers, including deliberately delayed responses. */
internal inline fun <reified T> stub(noinline handler: suspend (String, List<Any?>) -> Any?): T {
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, raw ->
        val args = raw?.toList().orEmpty()
        when (method.name) {
            "toString" -> "Test ${T::class.java.simpleName}"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args.firstOrNull()
            else -> {
                @Suppress("UNCHECKED_CAST")
                val continuation = args.lastOrNull() as? Continuation<Any?>
                val operation: suspend () -> Any? = {
                    handler(method.name, if (continuation == null) args else args.dropLast(1))
                }
                if (continuation != null) operation.startCoroutineUninterceptedOrReturn(continuation)
                else kotlinx.coroutines.runBlocking { operation() }
            }
        }
    } as T
}

internal class MemoryPreferences(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    override val data = MutableStateFlow(initial)
    private val mutex = Mutex()
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        mutex.withLock { transform(data.value).also { data.value = it } }
}
