package org.example.memosm.viewmodel.manager

import java.io.IOException
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
}
