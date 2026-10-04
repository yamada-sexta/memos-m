package org.example.memosm.account

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.*
import org.example.memosm.data.media.*
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.data.store.RoomAttachmentMetaStore
import org.example.memosm.data.sync.PreDownloadManager
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class PreDownloadReconciliationTest {
    @Test fun `partial full download retains cached history and never advances the cursor`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.fetch = { token ->
            if (token == null) ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), "next")
            else throw IOException("Network blocked between pages")
        }
        fixture.start(); runCurrent()
        assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
        assertEquals(0, fixture.prunes)
        assertTrue(fixture.manager.state.value is PreDownloadState.Failed)
        assertEquals(0L, fixture.preferences.textSyncCursor("A").first())
    }

    @Test fun `pruning waits until every page has succeeded including empty intermediate pages`() = runTest {
        val fixture = Fixture(backgroundScope)
        val release = CompletableDeferred<Unit>()
        fixture.fetch = { token -> when (token) {
            null -> ListMemosResponse(emptyList(), "next")
            else -> { release.await(); ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), null) }
        } }
        fixture.start(); runCurrent()
        assertEquals(setOf("memos/old"), fixture.names())
        assertEquals(0, fixture.prunes)
        release.complete(Unit); runCurrent()
        assertEquals(setOf("memos/fresh"), fixture.names())
        assertTrue(fixture.manager.state.value is PreDownloadState.Done)
    }

    @Test fun `incremental download merges history and does not infer deletion from absent entries`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.preferences.saveTextSyncCursor("A", System.currentTimeMillis())
        fixture.fetch = { ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), null) }
        fixture.start(); runCurrent()
        assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
        assertEquals(0, fixture.prunes)
        assertTrue(fixture.manager.state.value is PreDownloadState.Done)
    }

    @Test fun `cancelled paging preserves cached history`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.fetch = { token ->
            if (token == null) ListMemosResponse(listOf(Memo(name = "memos/fresh", content = "fresh")), "next")
            else awaitCancellation()
        }
        fixture.start(); runCurrent()
        fixture.manager.cancel(); runCurrent()
        assertEquals(setOf("memos/old", "memos/fresh"), fixture.names())
        assertEquals(0, fixture.prunes)
    }


    @Test fun `a response without a memo collection cannot erase cached history`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.fetch = { ListMemosResponse(null, null) }
        fixture.start(); runCurrent()
        assertEquals(setOf("memos/old"), fixture.names())
        assertEquals(0, fixture.prunes)
    }

    private class Fixture(scope: CoroutineScope) {
        val preferences = DataStoreManager(MemoryPreferences())
        var prunes = 0
        var fetch: suspend (String?) -> ListMemosResponse = { error("No response configured") }
        private val rows = mutableListOf(CachedMemo.fromMemo(Memo(name = "memos/old", content = "old"), "A", CacheListType.USER, 0).copy(cachedAt = 1))
        fun names() = rows.map { it.name }.toSet()
        private val repository = MemoCacheRepository(stub<MemoDao> { name, args -> when (name) {
            "getCountForAccount" -> rows.size
            "getMemos" -> rows.filter { it.accountId == args[0] && it.listType == args[1] }
            "cacheRemoteMemos" -> {
                if (args[3] == true) rows.removeAll { it.accountId == args[0] && it.listType == args[1] }
                @Suppress("UNCHECKED_CAST") val fresh = args[2] as List<CachedMemo>
                for (memo in fresh) { rows.removeAll { it.name == memo.name && it.listType == memo.listType }; rows.add(memo) }
                Unit
            }
            "pruneMissingFromList" -> {
                prunes++
                @Suppress("UNCHECKED_CAST") val missing = args[2] as List<String>
                rows.removeAll { it.accountId == args[0] && it.listType == args[1] && it.name in missing && it.cachedAt <= args[3] as Long }
                Unit
            }
            "trimListType" -> Unit
            else -> error(name)
        } })
        private val api = stub<MemosApi> { name, args -> when (name) {
            "buildMemoCreatorFilter" -> "creator == 'users/A'"
            "listMemos" -> if (args[2] == "ARCHIVED") ListMemosResponse(emptyList(), null) else fetch(args[1] as String?)
            else -> error(name)
        } }
        private val media = AttachmentCacheManager(ContextWrapper(null), stub<CachedAttachmentDao> { _, _ -> error("Unexpected media request") }, OkHttpClient(), preferences) { true }
        private val attachments = AttachmentCacheStore(repository,
            RoomAttachmentMetaStore(stub<CachedAttachmentMetaDao> { _, _ -> error("Unexpected metadata request") }), media)
        val manager = PreDownloadManager(scope, repository, preferences, media, attachments,
            { api }, { "A" }, { UserSnapshot(name = "users/A") }, { "" }, { "" }, { true }, { true })
        suspend fun start() {
            preferences.savePreDownloadWifiOnly(false)
            manager.downloadAllText(includeAttachments = false)
        }
    }
}
