package org.example.memosm.account

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.*
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.*
import org.example.memosm.data.cache.cachedMemos
import org.example.memosm.data.cache.seedMemos
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.*
import org.example.memosm.viewmodel.*
import org.example.memosm.viewmodel.manager.MemoMapManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MemoMapManagerTest {
    private val fixtures = mutableListOf<Harness>()

    @After fun close() {
        fixtures.forEach {
            it.scope.cancel()
            it.database.close()
        }
    }

    private fun fixture(handler: suspend (String, List<Any?>) -> Any?) = Harness(handler).also(fixtures::add)

    private suspend fun eventually(predicate: () -> Boolean) =
        withTimeout(5_000) {
            while (!predicate()) delay(10)
        }

    private fun memo(
        id: String,
        latitude: Double = 0.0,
        creator: String = "users/1",
        visibility: Visibility = Visibility.PRIVATE,
    ) = Memo(
        name = "memos/$id",
        content = id,
        creator = creator,
        visibility = visibility,
        state = MemoState.NORMAL,
        location = Location(latitude = latitude, longitude = 0.0),
    )

    private fun failure(
        code: Int,
        message: String = "error",
    ) = HttpException(Response.error<Any>(code, message.toResponseBody()))

    private class Harness(
        handler: suspend (String, List<Any?>) -> Any?,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MemoCacheDatabase::class.java).build()
        val cache = MemoCacheRepository(database.memoDao())
        val settings = DataStoreManager(MemoryPreferences())
        val state =
            MutableStateFlow(
                MemosUiState(
                    session = SessionState(currUser = UserSnapshot(name = "users/1")),
                    connectionState = ConnectionState.ONLINE,
                    isOnline = true,
                ),
            )
        val session = AccountSession(scope)
        val api =
            stub<MemosApi> { method, args ->
                when (method) {
                    "getInstanceProfile" -> {
                        InstanceProfile(version = "26.10")
                    }

                    "buildMemoCreatorFilter" -> {
                        "creator == 'users/1'"
                    }

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

                    else -> {
                        handler(method, args)
                    }
                }
            }
        var context = session.activate(Account(id = "A", hostUrl = "https://a.example", accessToken = "token"), api, OkHttpClient())
        val manager = MemoMapManager(session, state, cache, settings)

        fun supported() {
            state.value = state.value.copy(memoMap = state.value.memoMap.copy(capability = MapCapability.SUPPORTED))
        }

        suspend fun cache(
            memo: Memo,
            type: CacheListType = CacheListType.MAP_USER,
        ) = cache.seedMemos("A", type, listOf(memo), replace = false)
    }

    @Test fun offlineScopesObserveCanonicalValuesAndHideForeignPrivateLocations() =
        runBlocking {
            val h = fixture { _, _ -> error("Offline map must not request the server") }
            h.cache(memo("own"), CacheListType.USER)
            h.cache(memo("shared", creator = "users/2", visibility = Visibility.PUBLIC), CacheListType.MAP_EXPLORE)
            h.cache(memo("foreign-private", creator = "users/2"), CacheListType.MAP_ALL)
            h.supported()
            h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
            h.manager.selectScope(MapScope.ALL)
            eventually { h.state.value.memoMap.memos.size == 2 }
            assertEquals(
                setOf("memos/own", "memos/shared"),
                h.state.value.memoMap.memos
                    .map { it.name }
                    .toSet(),
            )
            h.manager.selectScope(MapScope.EXPLORE)
            eventually {
                h.state.value.memoMap.memos
                    .map { it.name } == listOf("memos/shared")
            }
        }

    @Test fun allPagesFlowThroughRoomWithStableDeduplicationAndFiltering() =
        runBlocking {
            val requests = mutableListOf<List<Any?>>()
            val h =
                fixture { method, args ->
                    assertEquals("listMemos", method)
                    requests += args
                    if (args[1] == null) {
                        ListMemosResponse(listOf(memo("one"), memo("bad", 91.0), memo("foreign", creator = "users/2")), "next")
                    } else {
                        ListMemosResponse(listOf(memo("one"), memo("two")), null)
                    }
                }
            h.supported()
            h.manager.open()
            eventually { h.state.value.memoMap.complete }
            assertEquals(
                listOf("memos/one", "memos/two"),
                h.state.value.memoMap.memos
                    .map { it.name },
            )
            assertEquals(2, requests.size)
            assertEquals(500, requests.first()[0])
            assertTrue((requests.first()[4] as String).contains("has_location"))
        }

    @Test fun partialRefreshRetainsOldMembershipUntilTheSnapshotCompletes() =
        runBlocking {
            var fail = true
            val h =
                fixture { _, args ->
                    if (args[1] == null) {
                        ListMemosResponse(listOf(memo("one")), "next")
                    } else if (fail) {
                        throw IOException("offline")
                    } else {
                        ListMemosResponse(listOf(memo("two")), null)
                    }
                }
            // The ordinary feed may retain its body after the complete map query excludes it.
            h.cache(memo("old"), CacheListType.USER)
            h.cache(memo("old"))
            h.supported()
            h.manager.open()
            eventually { h.state.value.memoMap.loadFailed }
            eventually { h.state.value.memoMap.memos.size == 2 }
            assertFalse(h.state.value.memoMap.complete)
            fail = false
            h.manager.load()
            eventually { h.state.value.memoMap.complete }
            assertEquals(
                setOf("memos/one", "memos/two"),
                h.state.value.memoMap.memos
                    .map { it.name }
                    .toSet(),
            )
            assertNotNull(h.cache.getCachedMemo("A", "memos/old"))
            assertTrue(
                h.database
                    .memoDao()
                    .getQueryMemos("A", "MAP_USER")
                    .none { it.name == "memos/old" },
            )
            assertTrue(h.cache.cachedMemos("A", CacheListType.USER).any { it.name == "memos/old" })
        }

    @Test fun openMapUpdatesFromDurableMutationsWithoutListPatching() =
        runBlocking {
            val h = fixture { _, _ -> error("Offline") }
            h.cache(memo("one"), CacheListType.USER)
            h.supported()
            h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
            h.manager.open()
            eventually { h.state.value.memoMap.memos.size == 1 }
            val edited = memo("one", latitude = 50.0)
            h.cache.commitMutation(
                PendingOp.new(
                    "A",
                    PendingOpType.UPDATE,
                    edited.name,
                    payloadJson = GsonProvider.gson.toJson(edited),
                    updateMask = "location",
                ),
                edited,
            )
            eventually {
                h.state.value.memoMap.memos
                    .singleOrNull()
                    ?.location
                    ?.latitude == 50.0
            }
            h.cache.commitMutation(PendingOp.new("A", PendingOpType.DELETE, edited.name), edited)
            eventually {
                h.state.value.memoMap.memos
                    .isEmpty()
            }
        }

    @Test fun completedMapKeepsLocallyCreatedMarkersThroughAcknowledgementAcrossOwnedScopes() =
        runBlocking {
            for (scope in listOf(MapScope.MEMOS, MapScope.ALL)) {
                var requests = 0
                val h =
                    fixture { _, _ ->
                        requests++
                        ListMemosResponse(listOf(memo("confirmed")), null)
                    }
                h.cache(memo("old-feed-only"), CacheListType.USER)
                h.supported()
                h.state.value =
                    h.state.value.copy(
                        memoMap =
                            h.state.value.memoMap
                                .copy(scope = scope),
                    )
                h.manager.open()
                eventually { h.state.value.memoMap.complete }
                assertEquals(
                    listOf("memos/confirmed"),
                    h.state.value.memoMap.memos
                        .map { it.name },
                )

                h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
                h.manager.load()
                val local = memo("pending").copy(name = "offline-pending")
                val op = PendingOp.new("A", PendingOpType.CREATE, local.name, payloadJson = GsonProvider.gson.toJson(local))
                h.cache.commitMutation(op, local)
                eventually {
                    h.state.value.memoMap.memos
                        .any { it.name == local.name }
                }

                val accepted = local.copy(name = "memos/server-created")
                h.cache.acknowledge(op, accepted)
                eventually {
                    h.state.value.memoMap.memos
                        .any { it.name == local.name && it.remoteName == accepted.name }
                }
                assertTrue(
                    h.database
                        .pendingOpDao()
                        .getOps("A")
                        .isEmpty(),
                )
                assertEquals(
                    setOf("memos/confirmed", local.name),
                    h.state.value.memoMap.memos
                        .map { it.name }
                        .toSet(),
                )
                assertEquals(1, requests)
                assertNotNull(h.cache.getCachedMemo("A", "memos/old-feed-only"))

                val moved = memo("old-feed-only", latitude = 50.0)
                val moveOp =
                    PendingOp.new(
                        "A",
                        PendingOpType.UPDATE,
                        moved.name,
                        payloadJson = GsonProvider.gson.toJson(moved),
                        updateMask = "location",
                    )
                h.cache.commitMutation(moveOp, moved)
                eventually {
                    h.state.value.memoMap.memos
                        .any { it.name == moved.name && it.location?.latitude == 50.0 }
                }
                val movedAccepted = moved.copy(updateTime = kotlin.time.Instant.fromEpochMilliseconds(1234))
                h.cache.acknowledge(moveOp, movedAccepted)
                eventually {
                    h.state.value.memoMap.memos
                        .any { it.name == moved.name && it.updateTime == movedAccepted.updateTime }
                }
                assertTrue(
                    h.database
                        .pendingOpDao()
                        .getOps("A")
                        .isEmpty(),
                )
                assertEquals(
                    setOf("memos/confirmed", local.name, moved.name),
                    h.state.value.memoMap.memos
                        .map { it.name }
                        .toSet(),
                )
                assertEquals(1, requests)
            }
        }

    @Test fun successfulEmptyProbePersistsSupportWithoutProvingAnyContentExists() =
        runBlocking {
            val h = fixture { _, _ -> ListMemosResponse(emptyList(), null) }
            h.manager.verify()
            eventually { h.state.value.memoMap.capability == MapCapability.SUPPORTED }
            assertNotNull(h.settings.snapshotJson("map_support", "A").first())
            h.state.value = h.state.value.copy(memoMap = MemoMapState(), connectionState = ConnectionState.OFFLINE)
            h.manager.restoreSupport(h.context)
            assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
        }

    @Test fun onlyExplicitLocationIncompatibilityErasesVerifiedSupport() =
        runBlocking {
            for (code in listOf(400, 404, 501)) {
                val h = fixture { _, _ -> throw failure(code, "undeclared reference to has_location") }
                h.supported()
                h.manager.verify()
                eventually { h.state.value.memoMap.capability == MapCapability.UNSUPPORTED }
            }
            for (code in listOf(401, 403, 429, 500, 503)) {
                val h = fixture { _, _ -> throw failure(code) }
                h.supported()
                h.manager.verify()
                delay(20)
                assertEquals(MapCapability.SUPPORTED, h.state.value.memoMap.capability)
            }
            val ignored = fixture { _, _ -> ListMemosResponse(listOf(memo("none").copy(location = null)), null) }
            ignored.manager.verify()
            eventually { ignored.state.value.memoMap.capability == MapCapability.UNSUPPORTED }
        }

    @Test fun serverOnlyViewsRemainSeparateFromUnfilteredMembershipAndUnavailableOffline() =
        runBlocking {
            val h =
                fixture { _, args ->
                    assertTrue((args[4] as String).contains("content.contains"))
                    ListMemosResponse(listOf(memo("filtered")), null)
                }
            h.supported()
            h.manager.selectView(Shortcut(name = "view", filter = "content.contains('filtered')"))
            eventually { h.state.value.memoMap.complete }
            assertEquals(
                listOf("memos/filtered"),
                h.state.value.memoMap.memos
                    .map { it.name },
            )
            assertTrue(
                h.database
                    .memoDao()
                    .getQueryMemos("A", "MAP_USER")
                    .isEmpty(),
            )
            h.state.value = h.state.value.copy(connectionState = ConnectionState.OFFLINE)
            h.manager.load()
            assertTrue(h.state.value.memoMap.filterUnavailable)
            assertTrue(
                h.state.value.memoMap.memos
                    .isEmpty(),
            )
        }

    @Test fun repeatedTokensEndWithAnErrorWithoutPruningHistory() =
        runBlocking {
            val h = fixture { _, _ -> ListMemosResponse(listOf(memo("one")), "same") }
            h.cache(memo("old"))
            h.supported()
            h.manager.open()
            eventually { h.state.value.memoMap.loadFailed }
            assertFalse(h.state.value.memoMap.isLoading)
            assertNotNull(h.cache.getCachedMemo("A", "memos/old"))
        }

    @Test fun lateAccountResponseCannotPublishOrPersistIntoANewActivation() =
        runBlocking {
            val release = CompletableDeferred<Unit>()
            val h =
                fixture { _, _ ->
                    withContext(NonCancellable) {
                        release.await()
                        ListMemosResponse(listOf(memo("old")), null)
                    }
                }
            h.supported()
            h.manager.open()
            delay(30)
            h.manager.reset()
            h.context = h.session.activate(Account(id = "B"), h.api, OkHttpClient())
            h.state.value = h.state.value.forAccount(h.context.account)
            release.complete(Unit)
            delay(50)
            assertTrue(
                h.state.value.memoMap.memos
                    .isEmpty(),
            )
            assertNull(h.cache.getCachedMemo("A", "memos/old"))
            assertNull(h.cache.getCachedMemo("B", "memos/old"))
        }
}
