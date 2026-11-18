package org.example.memosm.data.paging

import android.app.Application
import androidx.paging.testing.asSnapshot
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.example.memosm.account.stub
import org.example.memosm.api.ApiConstants
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemoCreatorFilterStyle
import org.example.memosm.api.MemoSearchFilter
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.seedMemos
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Account
import org.example.memosm.model.ListMemoCommentsResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.UserSnapshot
import org.example.memosm.viewmodel.AccountContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MemoPagingTest {
    private lateinit var database: MemoCacheDatabase
    private lateinit var repository: MemoCacheRepository
    private val parent = "memos/parent"
    private val query = MemoQuery(CacheListType.COMMENT, parentName = parent)

    @Before fun open() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MemoCacheDatabase::class.java).build()
        repository = MemoCacheRepository(database.memoDao())
        runBlocking { repository.seedMemos("A", CacheListType.USER, listOf(Memo(name = parent, content = "parent"))) }
    }

    @After fun close() = database.close()

    private fun context(fetch: suspend (String?) -> ListMemoCommentsResponse): AccountContext {
        val api =
            stub<MemosApi> { method, args ->
                when (method) {
                    "getConstants" -> {
                        ApiConstants(
                            "general",
                            "locale",
                            "memoVisibility",
                            MemoCreatorFilterStyle.RESOURCE_NAME,
                            "pinned desc",
                            "create_time desc",
                            "create_time asc",
                            "username",
                            "email",
                            "display_name",
                            "avatar_url",
                            "description",
                            "password",
                            "display_name",
                            "url",
                            "title",
                            "filter",
                        )
                    }

                    "listMemoComments" -> {
                        assertEquals(parent, args[0])
                        fetch(args[2] as String?)
                    }

                    else -> {
                        error("Unexpected API request: $method")
                    }
                }
            }
        return AccountContext(Account(id = "A"), api, OkHttpClient(), generation = 1)
    }

    @Test fun emptyThreadRemainsKnownEmptyOfflineAndRefreshesAfterReconnectWithoutChangingParent() =
        runBlocking {
            var calls = 0
            var online = true
            val account =
                context {
                    calls++
                    ListMemoCommentsResponse(emptyList(), null, 0)
                }

            suspend fun snapshot() =
                withTimeout(10_000) {
                    // asSnapshot throws on load errors, so every phase must complete through normal Paging load states.
                    memoPages(account, repository, query, null, 100, { online }, { true }).asSnapshot()
                }
            assertTrue(snapshot().isEmpty())
            val loaded = requireNotNull(repository.getQuery("A", query.key))
            assertTrue(loaded.loaded)
            assertTrue(loaded.complete)
            assertEquals(1, calls)

            online = false
            assertTrue(snapshot().isEmpty())
            assertEquals(1, calls)
            assertEquals(loaded, repository.getQuery("A", query.key))

            online = true
            assertTrue(snapshot().isEmpty())
            val recovered = requireNotNull(repository.getQuery("A", query.key))
            assertTrue(recovered.loaded)
            assertTrue(recovered.complete)
            assertTrue(recovered.generation > loaded.generation)
            assertEquals(2, calls)
        }

    @Test fun remoteRefreshKeepsCachedItemsAndPersistsAcrossOfflineRestarts() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<android.content.Context>()
            val name = "memo-cache-${java.util.UUID.randomUUID()}.db"

            fun openDisk() = Room.databaseBuilder(app, MemoCacheDatabase::class.java, name).build()
            var disk = openDisk()
            var collector: Job? = null
            try {
                var cache = MemoCacheRepository(disk.memoDao())
                var online = true
                var calls = 0
                var fetch: suspend () -> org.example.memosm.model.ListMemosResponse = {
                    org.example.memosm.model
                        .ListMemosResponse(listOf(Memo(name = "memos/remote", content = "remote v1")), null)
                }
                val constants = context { error("Not a comment query") }.api.constants
                val api =
                    stub<MemosApi> { method, _ ->
                        when (method) {
                            "getConstants" -> {
                                constants
                            }

                            "buildMemoCreatorFilter" -> {
                                "creator == 'users/me'"
                            }

                            "listMemos" -> {
                                calls++
                                fetch()
                            }

                            else -> {
                                error("Unexpected API: $method")
                            }
                        }
                    }
                val account = AccountContext(Account(id = "A"), api, OkHttpClient(), generation = 1)
                val shown = kotlinx.coroutines.flow.MutableStateFlow(emptyList<Memo>())

                fun presenter() =
                    object : androidx.paging.PagingDataPresenter<Memo>(Dispatchers.Unconfined) {
                        override suspend fun presentPagingDataEvent(event: androidx.paging.PagingDataEvent<Memo>) {
                            shown.value = snapshot().items
                        }
                    }

                suspend fun pages() =
                    memoPages(account, cache, MemoQuery(CacheListType.USER), UserSnapshot(name = "users/me"), 20, { online }, { true })
                val feed = presenter()
                collector = launch { pages().collectLatest { feed.collectFrom(it) } }
                withTimeout(10_000) { shown.first { it.map(Memo::content) == listOf("remote v1") } }
                assertEquals(1, calls)

                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                fetch = {
                    started.complete(Unit)
                    release.await()
                    org.example.memosm.model
                        .ListMemosResponse(listOf(Memo(name = "memos/remote", content = "remote v2")), null)
                }
                feed.refresh() // The same Paging refresh used by the pull gesture.
                withTimeout(10_000) { started.await() }
                assertEquals(listOf("remote v1"), feed.snapshot().items.map { it.content })
                assertEquals("remote v1", cache.getCachedMemo("A", "memos/remote")!!.content)
                release.complete(Unit)
                withTimeout(10_000) { shown.first { it.map(Memo::content) == listOf("remote v2") } }
                assertEquals(2, calls)
                fetch = { throw java.io.IOException("Server unavailable") }
                feed.refresh()
                withTimeout(10_000) { feed.loadStateFlow.filterNotNull().first { it.refresh is androidx.paging.LoadState.Error } }
                assertEquals(listOf("remote v2"), feed.snapshot().items.map { it.content })
                assertEquals(3, calls)

                collector.cancelAndJoin()
                disk.close()
                disk = openDisk()
                cache = MemoCacheRepository(disk.memoDao())
                online = false
                shown.value = emptyList()
                val offline = presenter()
                collector = launch { pages().collectLatest { offline.collectFrom(it) } }
                withTimeout(10_000) { shown.first { it.map(Memo::content) == listOf("remote v2") } }
                assertEquals("remote v2", cache.getCachedMemo("A", "memos/remote")!!.content)
                assertEquals(3, calls)
            } finally {
                collector?.cancelAndJoin()
                disk.close()
                app.deleteDatabase(name)
            }
        }

    @Test fun offlineAttachmentsRemainReadableWhenOnlyPartOfTheRemoteListWasCached() =
        runBlocking {
            val cache =
                org.example.memosm.data.offline
                    .AttachmentCacheStore(database.cachedAttachmentMetaDao())
            val attachment =
                org.example.memosm.model
                    .Attachment(name = "attachments/cached", filename = "photo", type = "image/jpeg")
            cache.ingestPage("A", listOf(attachment), "uncached-next-page", true)
            val offline = context { error("Offline browsing must not request the server") }
            val items = withTimeout(10_000) { attachmentPages(offline, cache, { false }, { true }).asSnapshot() }
            assertEquals(listOf(attachment.name), items.map { it.name })
            assertEquals("uncached-next-page", cache.getQuery("A")!!.nextPageToken)
        }

    @Test fun commentsContinuePastOneHundredAndKeepWholeThreadOrdering() =
        runBlocking {
            val requests = mutableListOf<String?>()

            fun comment(index: Int) =
                Memo(
                    name = "memos/comment-$index",
                    content = "$index",
                    createTime = Instant.fromEpochMilliseconds(index.toLong()),
                )
            val account =
                context { token ->
                    requests += token
                    if (token == null) {
                        ListMemoCommentsResponse((1..100).map(::comment), "next", 101)
                    } else {
                        ListMemoCommentsResponse(listOf(comment(101)), null, 101)
                    }
                }
            val comments =
                withTimeout(10_000) {
                    memoPages(account, repository, query, null, 100, { true }, { true }).asSnapshot { scrollTo(100) }
                }
            assertEquals((1..101).map { "$it" }, comments.map { it.content })
            assertEquals(listOf(null, "next"), requests)
            assertTrue(requireNotNull(repository.getQuery("A", query.key)).complete)
        }

    @Test fun personalSearchExcludesPendingArchivesDespiteRetainedRemoteSearchMembership() =
        runBlocking {
            val memo = Memo(name = "memos/search", content = "search", creator = "users/me", state = MemoState.NORMAL)
            val user = UserSnapshot(name = "users/me")
            val search = MemoQuery(CacheListType.SEARCH, search = MemoSearchFilter(query = "search"))
            repository.seedMemos("A", CacheListType.USER, listOf(memo), replace = false)
            repository.ingestPage("A", search.key, CacheListType.SEARCH, listOf(memo), nextPageToken = null, refresh = true)
            val account = context { error("A fully cached offline query must not request the server") }

            suspend fun results() =
                withTimeout(10_000) {
                    memoPages(account, repository, search, user, 100, { false }, { true }).asSnapshot()
                }
            assertEquals(listOf(memo.name), results().map { it.name })
            val archived = memo.copy(state = MemoState.ARCHIVED)
            repository.commitMutation(
                PendingOp.new(
                    "A",
                    PendingOpType.UPDATE,
                    memo.name,
                    payloadJson = GsonProvider.gson.toJson(archived),
                    updateMask = "state",
                ),
                archived,
            )
            assertEquals(1, database.memoDao().getQueryMemos("A", search.key).size)
            assertTrue(results().isEmpty())
        }

    @Test fun aResponseFromAnInactiveAccountNeverPersistsItsComments() =
        runBlocking {
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var current = true
            val account =
                context {
                    requested.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    ListMemoCommentsResponse(listOf(Memo(name = "memos/old", content = "old")), null, 1)
                }
            val read =
                launch {
                    memoPages(account, repository, query, null, 100, { true }, { current }).asSnapshot()
                }
            withTimeout(10_000) { requested.await() }
            current = false
            read.cancel() // flatMapLatest cancels the originating pager when the active account changes.
            release.complete(Unit)
            withTimeout(10_000) { read.join() }
            assertNull(repository.getCachedMemo("A", "memos/old"))
        }
}
