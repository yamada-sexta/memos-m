package org.example.memosm.account

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.*
import org.example.memosm.data.cache.cachedMemos
import org.example.memosm.data.cache.seedMemo
import org.example.memosm.data.sync.FULL_SNAPSHOT_INTERVAL_MS
import org.example.memosm.data.sync.downloadMemoHistory
import org.example.memosm.data.sync.needsFullSnapshot
import org.example.memosm.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class PreDownloadReconciliationTest {
    private class Fixture : AutoCloseable {
        val database =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MemoCacheDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val cache = MemoCacheRepository(database.memoDao())
        val filters = mutableListOf<String>()
        var fetch: suspend (String?, String) -> ListMemosResponse = { _, _ -> error("No response configured") }
        val api =
            stub<MemosApi> { method, args ->
                when (method) {
                    "buildMemoCreatorFilter" -> {
                        "creator == 'users/A'"
                    }

                    "listMemos" -> {
                        val filter = args[4] as String
                        filters += filter
                        if (args[2] == "ARCHIVED") {
                            ListMemosResponse(emptyList(), null)
                        } else {
                            fetch(args[1] as String?, filter)
                        }
                    }

                    else -> {
                        error(method)
                    }
                }
            }

        suspend fun seed() = cache.seedMemo("A", Memo(name = "memos/old", content = "old"), CacheListType.USER)

        suspend fun names() = cache.cachedMemos("A", CacheListType.USER).mapNotNull { it.name }.toSet()

        suspend fun download(
            includeExplore: Boolean = false,
            current: suspend () -> Boolean = { true },
        ) = downloadMemoHistory(api, "A", UserSnapshot(name = "users/A"), cache, includeExplore, isCurrent = current)

        override fun close() = database.close()
    }

    @Test fun `partial full pull preserves old history and checkpoint`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                fixture.fetch = { token, _ ->
                    if (token == null) {
                        ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), "next")
                    } else {
                        throw IOException("Interrupted")
                    }
                }
                try {
                    fixture.download()
                    fail("Expected interrupted download")
                } catch (_: IOException) {
                }
                assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
                val query = fixture.cache.getQuery("A", "USER:history")!!
                assertFalse(query.complete)
                assertEquals(0L, query.incrementalCursor)
                assertEquals(0L, query.lastFullSnapshotAt)
            }
        }

    @Test fun `empty intermediate page never prunes before terminal page`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                val waiting = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fixture.fetch = { token, _ ->
                    if (token == null) {
                        ListMemosResponse(emptyList(), "next")
                    } else {
                        waiting.complete(Unit)
                        release.await()
                        ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), null)
                    }
                }
                val job = async { fixture.download() }
                waiting.await()
                assertEquals(setOf("memos/old"), fixture.names())
                release.complete(Unit)
                job.await()
                assertEquals(setOf("memos/fresh"), fixture.names())
                assertTrue(fixture.cache.getQuery("A", "USER:history")!!.complete)
            }
        }

    @Test fun `incremental pull merges without moving full snapshot timestamp`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                val fullAt = System.currentTimeMillis() - 60 * 60 * 1000
                fixture.database.memoDao().putQuery(
                    CachedQuery(
                        "A",
                        "USER:history",
                        "USER",
                        loaded = true,
                        complete = true,
                        lastFullSnapshotAt = fullAt,
                        incrementalCursor = fullAt + 1,
                    ),
                )
                fixture.fetch = { _, _ -> ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), null) }
                fixture.download()
                assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
                assertTrue(fixture.filters.first().contains("update_time"))
                assertEquals(fullAt, fixture.cache.getQuery("A", "USER:history")!!.lastFullSnapshotAt)
            }
        }

    @Test fun `partial incremental pull restarts first page and completes on retry`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                val now = System.currentTimeMillis()
                fixture.database.memoDao().putQuery(
                    CachedQuery(
                        "A",
                        "USER:history",
                        "USER",
                        loaded = true,
                        complete = true,
                        lastFullSnapshotAt = now - 1000,
                        incrementalCursor = now - 1000,
                    ),
                )
                fixture.fetch = { token, _ ->
                    if (token == null) {
                        ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), "unfinished")
                    } else {
                        throw IOException("Interrupted incremental pull")
                    }
                }
                try {
                    fixture.download()
                    fail("Expected interrupted download")
                } catch (_: IOException) {
                }
                assertEquals("unfinished", fixture.cache.getQuery("A", "USER:history")!!.nextPageToken)
                val retriedTokens = mutableListOf<String?>()
                fixture.fetch = { token, _ ->
                    retriedTokens += token
                    ListMemosResponse(emptyList(), null)
                }
                fixture.download()
                assertEquals(listOf<String?>(null), retriedTokens)
                assertNull(fixture.cache.getQuery("A", "USER:history")!!.nextPageToken)
                assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
            }
        }

    @Test fun `recent incremental checkpoint cannot postpone overdue full reconciliation`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                val now = System.currentTimeMillis()
                fixture.database.memoDao().putQuery(
                    CachedQuery(
                        "A",
                        "USER:history",
                        "USER",
                        loaded = true,
                        complete = true,
                        lastFullSnapshotAt = now - FULL_SNAPSHOT_INTERVAL_MS - 1,
                        incrementalCursor = now - 1000,
                    ),
                )
                fixture.fetch = { _, _ -> ListMemosResponse(emptyList(), null) }
                fixture.download()
                assertFalse(fixture.filters.first().contains("update_time"))
                assertTrue(fixture.names().isEmpty())
                assertTrue(fixture.cache.getQuery("A", "USER:history")!!.lastFullSnapshotAt > now - 1000)
            }
        }

    @Test fun `new Explore collection receives complete baseline independent of user cursor`() =
        runBlocking {
            Fixture().use { fixture ->
                val now = System.currentTimeMillis()
                fixture.database.memoDao().putQuery(
                    CachedQuery(
                        "A",
                        "USER:history",
                        "USER",
                        loaded = true,
                        complete = true,
                        lastFullSnapshotAt = now,
                        incrementalCursor = now,
                    ),
                )
                fixture.fetch = { _, _ -> ListMemosResponse(emptyList(), null) }
                fixture.download(includeExplore = true)
                val exploreFilter = fixture.filters.single { it.startsWith("visibility") }
                assertFalse(exploreFilter.contains("update_time"))
                assertTrue(fixture.cache.getQuery("A", "EXPLORE:history")!!.complete)
            }
        }

    @Test fun `missing collection cannot erase cached history`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                fixture.fetch = { _, _ -> ListMemosResponse(null, null) }
                try {
                    fixture.download()
                    fail("Expected malformed response")
                } catch (_: IOException) {
                }
                assertEquals(setOf("memos/old"), fixture.names())
                assertEquals(0L, fixture.cache.getQuery("A", "USER:history")!!.incrementalCursor)
            }
        }

    @Test fun `cancellation retains committed pages without pruning`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                val waiting = CompletableDeferred<Unit>()
                fixture.fetch = { token, _ ->
                    if (token == null) {
                        ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), "next")
                    } else {
                        waiting.complete(Unit)
                        awaitCancellation()
                    }
                }
                val job = launch { fixture.download() }
                waiting.await()
                job.cancelAndJoin()
                assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
                assertFalse(fixture.cache.getQuery("A", "USER:history")!!.complete)
            }
        }

    @Test fun `changed account storage rejects late response before ingestion`() =
        runBlocking {
            Fixture().use { fixture ->
                fixture.seed()
                var current = true
                fixture.fetch = { _, _ ->
                    current = false
                    ListMemosResponse(emptyList(), null)
                }
                try {
                    fixture.download(current = { current })
                    fail("Expected stale response cancellation")
                } catch (
                    _: CancellationException,
                ) {
                }
                assertEquals(setOf("memos/old"), fixture.names())
            }
        }

    @Test fun `uninitialized query needs full snapshot`() {
        assertTrue(needsFullSnapshot(null, System.currentTimeMillis()))
        assertTrue(needsFullSnapshot(CachedQuery("A", "EXPLORE:history", "EXPLORE"), System.currentTimeMillis()))
    }
}
