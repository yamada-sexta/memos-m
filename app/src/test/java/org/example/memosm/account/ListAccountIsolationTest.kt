package org.example.memosm.account

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.example.memosm.viewmodel.manager.BaseListManager
import org.example.memosm.viewmodel.manager.CacheCallbacks
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ListAccountIsolationTest {
    @Test fun `late A response cannot repopulate B or write into its cache`() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<List<String>>()
        var account = "A"
        val manager = object : BaseListManager<String>(
            backgroundScope,
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { writes += it }),
            nameProvider = { it }
        ) {
            override suspend fun fetchFromApi(pageToken: String?): Pair<List<String>, String?> {
                val startedFor = account
                if (startedFor == "A") withContext(NonCancellable) { release.await() }
                return listOf(startedFor) to null
            }
        }
        manager.fetch()
        runCurrent()
        manager.reset()
        account = "B"
        manager.fetch()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("B"), manager.listState.value.items)
        assertEquals(listOf(listOf("B")), writes)
    }

    @Test fun `late cached read is ignored after A B A resets`() = runTest {
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val manager = object : BaseListManager<String>(
            backgroundScope,
            cacheCallbacks = CacheCallbacks(getCachedData = {
                val old = reads++ == 0
                if (old) withContext(NonCancellable) { release.await() }
                listOf(if (old) "old-A" else "current-A")
            })
        ) {
            override suspend fun fetchFromApi(pageToken: String?) = emptyList<String>() to null
        }
        manager.loadFromCache()
        runCurrent()
        manager.reset()
        manager.reset()
        manager.loadFromCache()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("current-A"), manager.listState.value.items)
    }
}
