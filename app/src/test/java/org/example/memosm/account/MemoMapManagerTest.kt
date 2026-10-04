package org.example.memosm.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.*
import org.example.memosm.viewmodel.*
import org.example.memosm.viewmodel.manager.MemoMapManager
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MemoMapManagerTest {
    @Test fun `all scope combines own and explore caches offline without foreign private memos`() = runTest {
        val h = Harness(this) { _, _ -> error("Offline map must not call the API") }
        h.supported()
        h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
        h.cache(memo("own"), CacheListType.USER)
        h.cache(memo("shared", creator = "users/2", visibility = Visibility.PUBLIC), CacheListType.MAP_EXPLORE)
        h.cache(memo("foreign-private", creator = "users/2"), CacheListType.MAP_ALL)
        h.manager.selectScope(MapScope.ALL); runCurrent()
        assertEquals(setOf("memos/own", "memos/shared"), h.state.value.memoMap.memos.map { it.name }.toSet())
        h.manager.selectScope(MapScope.EXPLORE); runCurrent()
        assertEquals(listOf("memos/shared"), h.state.value.memoMap.memos.map { it.name })
    }

    @Test fun `all scope fetches full accessible location history into its own cache`() = runTest {
        var filter: String? = null
        val h = Harness(this) { _, args ->
            filter = args[4] as String?
            ListMemosResponse(listOf(memo("own"), memo("shared", creator = "users/2", visibility = Visibility.PROTECTED)), null)
        }
        h.supported(); h.manager.selectScope(MapScope.ALL); runCurrent()
        assertEquals("(has_location)", filter)
        assertEquals(2, h.state.value.memoMap.memos.size)
        assertEquals(2, h.rows.count { it.listType == CacheListType.MAP_ALL.name })
    }

    private fun memo(id: String, latitude: Double = 0.0, creator: String = "users/1",
        visibility: Visibility = Visibility.PRIVATE) = Memo(name = "memos/$id", content = id,
        creator = creator, visibility = visibility, state = MemoState.NORMAL, location = Location(latitude = latitude, longitude = 0.0))

    private fun failure(code: Int, message: String = "error") =
        HttpException(Response.error<Any>(code, message.toResponseBody()))

    private class Harness(scope: TestScope, handler: suspend (String, List<Any?>) -> Any?) {
        val settings = DataStoreManager(MemoryPreferences())
        val state = MutableStateFlow(MemosUiState(session = SessionState(currUser = UserSnapshot(name = "users/1")),
            connectionState = ConnectionState.ONLINE, isOnline = true))
        val session = AccountSession(scope.backgroundScope)
        val rows = mutableListOf<CachedMemo>()
        val writes = mutableListOf<String>()
        val dao = stub<MemoDao> { method, args -> when (method) {
            "getMemos" -> rows.filter { it.accountId == args[0] && it.listType == args[1] }
            "cacheRemoteMemos" -> {
                writes += args[0] as String
                @Suppress("UNCHECKED_CAST") val incoming = args[2] as List<CachedMemo>
                incoming.forEach { row -> rows.removeAll { it.accountId == row.accountId && it.listType == row.listType && it.name == row.name }; rows += row }
                Unit
            }
            "pruneMissingFromList" -> {
                @Suppress("UNCHECKED_CAST") val names = args[2] as List<String>
                rows.removeAll { it.accountId == args[0] && it.listType == args[1] && it.name in names }; Unit
            }
            "trimListType" -> Unit
            else -> error("Unexpected DAO call $method")
        } }
        val api = stub<MemosApi> { method, args -> when (method) {
            "getInstanceProfile" -> InstanceProfile(version = "26.10")
            "buildMemoCreatorFilter" -> "creator == 'users/1'"
            "getConstants" -> org.example.memosm.api.ApiConstants("general", "locale", "memoVisibility",
                org.example.memosm.api.MemoCreatorFilterStyle.RESOURCE_NAME, "pinned desc", "create_time desc", "create_time asc",
                "username", "email", "display_name", "avatar_url", "description", "password", "display_name", "url", "title", "filter")
            else -> handler(method, args)
        } }
        val manager = MemoMapManager(session, state, MemoCacheRepository(dao), settings)
        var context = session.activate(Account(id = "A", hostUrl = "https://a.example", accessToken = "token"), api, OkHttpClient())
        fun supported() { state.value = state.value.copy(memoMap = state.value.memoMap.copy(capability = MapCapability.SUPPORTED)) }
        fun cache(memo: Memo, type: CacheListType = CacheListType.MAP_USER) {
            rows += CachedMemo.fromMemo(memo, context.account.id, type, rows.size)
        }
    }

    @Test fun `successful empty API proves support and is remembered offline`() = runTest {
        val h = Harness(this) { _, _ -> ListMemosResponse(emptyList(), null) }
        h.manager.verify(); runCurrent()
        assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
        assertNotNull(h.settings.snapshotJson("map_support", "A").first())
        h.state.value = h.state.value.copy(memoMap = MemoMapState(), connectionState = ConnectionState.OFFLINE)
        h.manager.restoreSupport(h.context)
        assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
    }

    @Test fun `unknown location filter hides map but transient errors do not erase verified support`() = runTest {
        for (code in listOf(400, 404, 501)) {
            val h = Harness(this) { _, _ -> throw failure(code, "undeclared reference to has_location") }
            h.supported(); h.manager.verify(); runCurrent()
            assertEquals(MapCapability.UNSUPPORTED, h.state.value.memoMap.capability)
        }
        for (code in listOf(401, 403, 429, 500, 503)) {
            val h = Harness(this) { _, _ -> throw failure(code) }
            h.supported(); h.manager.verify(); runCurrent()
            assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
        }
        val h = Harness(this) { _, _ -> throw IOException("offline") }
        h.manager.verify(); runCurrent()
        assertEquals(MapCapability.UNKNOWN, h.state.value.memoMap.capability)
    }

    @Test fun `a server that ignores location filtering does not get the map`() = runTest {
        val h = Harness(this) { _, _ -> ListMemosResponse(listOf(memo("no-location").copy(location = null)), null) }
        h.manager.verify(); runCurrent()
        assertEquals(MapCapability.UNSUPPORTED, h.state.value.memoMap.capability)
    }

    @Test fun `an invalid saved view does not mark the location API unsupported`() = runTest {
        val h = Harness(this) { _, _ -> throw failure(400,
            "undeclared reference to 'space_id' in filter: has_location && space_id == 'x'") }
        h.supported(); h.manager.selectView(Shortcut(filter = "space_id == 'x'")); runCurrent()
        assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
        assertTrue(h.state.value.memoMap.loadFailed)
    }

    @Test fun `cached capability cannot transfer to another URL or version`() = runTest {
        val h = Harness(this) { _, _ -> ListMemosResponse(emptyList(), null) }
        h.settings.saveSnapshotJson("map_support", "A", GsonProvider.gson.toJson(MapSupport("https://old.example", "26.10", MapCapability.SUPPORTED)))
        h.manager.restoreSupport(h.context)
        assertEquals(MapCapability.UNKNOWN, h.state.value.memoMap.capability)
        h.settings.saveSnapshotJson("map_support", "A", GsonProvider.gson.toJson(MapSupport("https://a.example", "0.30", MapCapability.SUPPORTED)))
        h.state.value = h.state.value.copy(session = h.state.value.session.copy(instanceProfile = InstanceProfile(version = "26.10")))
        h.manager.restoreSupport(h.context)
        assertEquals(MapCapability.UNKNOWN, h.state.value.memoMap.capability)
    }

    @Test fun `version change invalidates cached support even when the new probe fails transiently`() = runTest {
        val h = Harness(this) { _, _ -> throw IOException("offline") }
        h.settings.saveSnapshotJson("map_support", "A", GsonProvider.gson.toJson(MapSupport("https://a.example", "0.30", MapCapability.SUPPORTED)))
        h.supported(); h.manager.verify(); runCurrent()
        assertEquals(MapCapability.UNKNOWN, h.state.value.memoMap.capability)
    }

    @Test fun `all pages are fetched deduplicated and scoped instead of using feed pagination`() = runTest {
        val requests = mutableListOf<List<Any?>>()
        val h = Harness(this) { method, args ->
            assertEquals("listMemos", method); requests += args
            if (args[1] == null) ListMemosResponse(listOf(memo("one"), memo("bad", 91.0), memo("other", creator = "users/2")), "next")
            else ListMemosResponse(listOf(memo("one"), memo("two")), null)
        }
        h.supported(); h.manager.open(); runCurrent()
        assertEquals(listOf("memos/one", "memos/two"), h.state.value.memoMap.memos.map { it.name })
        assertTrue(h.state.value.memoMap.complete)
        assertEquals(2, requests.size)
        assertEquals(500, requests[0][0])
        assertTrue((requests[0][4] as String).contains("has_location"))
        assertTrue((requests[0][4] as String).contains("creator"))
    }

    @Test fun `failed paging preserves partial locations and does not prune old cache`() = runTest {
        var fail = true
        val h = Harness(this) { _, args ->
            if (args[1] == null) ListMemosResponse(listOf(memo("one")), "next")
            else if (fail) throw IOException("offline") else ListMemosResponse(listOf(memo("two")), null)
        }
        h.cache(memo("old")); h.supported(); h.manager.open(); runCurrent()
        assertTrue(h.state.value.memoMap.loadFailed)
        assertFalse(h.state.value.memoMap.complete)
        assertEquals(listOf("memos/one"), h.state.value.memoMap.memos.map { it.name })
        assertTrue(h.rows.any { it.name == "memos/old" })
        fail = false; h.manager.load(); runCurrent()
        assertTrue(h.state.value.memoMap.complete)
        assertFalse(h.rows.any { it.name == "memos/old" })
    }

    @Test fun `repeated page tokens stop loading without dropping cached history`() = runTest {
        val h = Harness(this) { _, _ -> ListMemosResponse(listOf(memo("one")), "same") }
        h.supported(); h.manager.open(); runCurrent()
        assertTrue(h.state.value.memoMap.loadFailed)
        assertFalse(h.state.value.memoMap.isLoading)
    }

    @Test fun `offline map uses cache and pending edits and never exposes private locations in Explore`() = runTest {
        val h = Harness(this) { _, _ -> error("Offline must not fetch") }
        h.cache(memo("one")); h.cache(memo("private"), CacheListType.EXPLORE)
        h.cache(memo("public", creator = "users/2", visibility = Visibility.PUBLIC), CacheListType.EXPLORE)
        val local = memo("local").copy(name = "offline-local")
        h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE, pendingOps = listOf(
            PendingOp.new("A", PendingOpType.DELETE, "memos/one"),
            PendingOp.new("A", PendingOpType.CREATE, local.name, payloadJson = GsonProvider.gson.toJson(local))
        ))
        h.supported(); h.manager.open(); runCurrent()
        assertEquals(listOf("offline-local"), h.state.value.memoMap.memos.map { it.name })
        h.manager.selectScope(MapScope.EXPLORE); runCurrent()
        assertEquals(listOf("memos/public"), h.state.value.memoMap.memos.map { it.name })
        assertTrue(h.state.value.memoMap.isOffline)
    }

    @Test fun `server-only saved views are unavailable offline and do not contaminate unfiltered cache`() = runTest {
        val h = Harness(this) { _, args ->
            assertTrue((args[4] as String).contains("content.contains"))
            ListMemosResponse(listOf(memo("filtered")), null)
        }
        h.supported(); h.manager.selectView(Shortcut(name = "view", title = "view", filter = "content.contains('filtered')")); runCurrent()
        assertEquals(listOf("memos/filtered"), h.state.value.memoMap.memos.map { it.name })
        assertTrue(h.rows.isEmpty())
        h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
        h.manager.load(); runCurrent()
        assertTrue(h.state.value.memoMap.filterUnavailable)
        assertTrue(h.state.value.memoMap.memos.isEmpty())
    }

    @Test fun `late capability response cannot enable a different account`() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = Harness(this) { _, _ -> withContext(NonCancellable) { release.await(); ListMemosResponse(emptyList(), null) } }
        h.manager.verify(); runCurrent()
        h.manager.reset()
        h.context = h.session.activate(Account(id = "B", hostUrl = "https://b.example", accessToken = "b"), h.api, OkHttpClient())
        h.state.value = h.state.value.forAccount(h.context.account)
        release.complete(Unit); runCurrent()
        assertEquals(MapCapability.UNKNOWN, h.state.value.memoMap.capability)
        assertNull(h.settings.snapshotJson("map_support", "B").first())
    }

    @Test fun `late map response cannot publish or cache for the next activation`() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = Harness(this) { _, _ -> withContext(NonCancellable) { release.await(); ListMemosResponse(listOf(memo("old")), null) } }
        h.supported(); h.manager.open(); runCurrent()
        h.manager.reset()
        h.context = h.session.activate(Account(id = "B", hostUrl = "https://b.example", accessToken = "b"), h.api, OkHttpClient())
        h.state.value = h.state.value.forAccount(h.context.account)
        release.complete(Unit); runCurrent()
        assertTrue(h.state.value.memoMap.memos.isEmpty())
        assertTrue(h.writes.isEmpty())
    }

    @Test fun `an edit during paging wins over an old server response`() = runTest {
        val release = CompletableDeferred<Unit>()
        val h = Harness(this) { _, _ -> release.await(); ListMemosResponse(listOf(memo("one")), null) }
        h.supported(); h.manager.open(); runCurrent()
        h.manager.upsert(memo("one", latitude = 50.0))
        release.complete(Unit); runCurrent()
        assertEquals(50.0, h.state.value.memoMap.memos.single().location!!.latitude!!, 0.0)
        h.manager.upsert(memo("one").copy(state = MemoState.ARCHIVED))
        assertTrue(h.state.value.memoMap.memos.isEmpty())
    }
}
