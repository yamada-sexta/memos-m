package org.example.memosm.viewmodel.manager

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshRetryTest {
    @Test fun `refresh keeps trying after failures and retains cache until the server recovers`() = runTest {
        var reachable = false
        var requests = 0
        val manager = object : BaseListManager<String>(
            backgroundScope,
            cacheCallbacks = CacheCallbacks(getCachedData = { listOf("cached") }),
            nameProvider = { it }
        ) {
            override suspend fun fetchFromApi(pageToken: String?): Pair<List<String>, String?> {
                requests++
                if (!reachable) throw IOException("Offline")
                return listOf("fresh") to null
            }
        }
        manager.loadFromCache()
        runCurrent()

        repeat(2) { attempt ->
            manager.fetch(refresh = true)
            runCurrent()
            assertEquals(attempt + 1, requests)
            assertEquals(listOf("cached"), manager.listState.value.items)
            assertFalse(manager.listState.value.isLoading)
            assertTrue(manager.listState.value.isOffline)
        }

        reachable = true
        manager.fetch(refresh = true)
        runCurrent()
        assertEquals(3, requests)
        assertTrue(manager.listState.value.items.contains("fresh"))
        assertFalse(manager.listState.value.isLoading)
        assertFalse(manager.listState.value.isOffline)
    }

    @Test fun `manual refresh replaces a suspended request without blanking cached content`() = runTest {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val writes = mutableListOf<List<String>>()
        val manager = object : BaseListManager<String>(backgroundScope,
            cacheCallbacks = CacheCallbacks(getCachedData = { listOf("cached") }, onFetchSuccess = { writes += it }),
            nameProvider = { it }) {
            override suspend fun fetchFromApi(pageToken: String?): Pair<List<String>, String?> {
                if (attempts++ == 0) {
                    withContext(NonCancellable) { release.await() }
                    return listOf("stale") to null
                }
                return listOf("fresh") to null
            }
        }
        manager.loadFromCache(); runCurrent()
        manager.fetch(refresh = true); runCurrent()
        manager.fetch(refresh = true)
        assertEquals(listOf("cached"), manager.listState.value.items)
        runCurrent()
        release.complete(Unit); runCurrent()
        assertTrue(manager.listState.value.items.contains("fresh"))
        assertFalse(manager.listState.value.items.contains("stale"))
        assertFalse(manager.listState.value.isLoading)
        assertEquals(listOf(listOf("fresh")), writes)
    }

    @Test fun `confirmed deletion cannot be restored by cache prefill already in flight`() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<List<String>>()
        val manager = object : BaseListManager<String>(backgroundScope,
            cacheCallbacks = CacheCallbacks(getCachedData = { release.await(); listOf("gone", "retained") },
                onFetchSuccess = { writes += it }), nameProvider = { it }) {
            override suspend fun fetchFromApi(pageToken: String?) = listOf("fresh") to null
        }
        manager.fetch(); runCurrent()
        manager.forget("gone")
        release.complete(Unit); runCurrent()
        assertFalse(manager.listState.value.items.contains("gone"))
        assertTrue(manager.listState.value.items.contains("retained"))
        assertEquals(listOf(listOf("fresh")), writes)
    }

    @Test fun `cancelling a request before it starts releases the loading flag`() = runTest {
        val manager = object : BaseListManager<String>(backgroundScope) {
            override suspend fun fetchFromApi(pageToken: String?) = listOf("fresh") to null
        }
        val job = manager.fetch()
        assertTrue(manager.listState.value.isLoading)
        job!!.cancel(); runCurrent()
        assertFalse(manager.listState.value.isLoading)
        manager.fetch(); runCurrent()
        assertEquals(listOf("fresh"), manager.listState.value.items)
    }

    @Test fun `a hung list request times out and leaves the cache refreshable`() = runTest {
        var hang = true
        val manager = object : BaseListManager<String>(backgroundScope,
            cacheCallbacks = CacheCallbacks(getCachedData = { listOf("cached") }), nameProvider = { it }) {
            override suspend fun fetchFromApi(pageToken: String?): Pair<List<String>, String?> {
                if (hang) awaitCancellation()
                return listOf("fresh") to null
            }
        }
        manager.fetch(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("cached"), manager.listState.value.items)
        assertFalse(manager.listState.value.isLoading)
        assertTrue(manager.listState.value.isOffline)
        hang = false
        manager.fetch(refresh = true); runCurrent()
        assertTrue(manager.listState.value.items.contains("fresh"))
    }

    @Test fun `a refresh preserves a queued create that existed before the request started`() = runTest {
        val manager = object : BaseListManager<String>(backgroundScope, nameProvider = { it },
            protectedNamesProvider = { setOf("local-unsent") }) {
            override suspend fun fetchFromApi(pageToken: String?) = listOf("server") to null
        }
        manager.upsert("local-unsent", { it == "local-unsent" })
        manager.fetch(refresh = true); runCurrent()
        assertEquals(setOf("local-unsent", "server"), manager.listState.value.items.toSet())
    }

    @Test fun `late offline search cannot restore a confirmed deletion`() = runTest {
        val release = CompletableDeferred<Unit>()
        val manager = SearchMemoListManager(backgroundScope, { null }, { 10 }, localSearchProvider = {
            release.await()
            listOf(org.example.memosm.model.Memo(name = "memos/deleted", content = "gone"))
        })
        manager.searchLocal(); runCurrent()
        manager.forget("memos/deleted")
        release.complete(Unit); runCurrent()
        assertTrue(manager.listState.value.items.isEmpty())
    }
}
