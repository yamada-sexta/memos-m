package org.example.memosm.account

import android.content.ContextWrapper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.audit.SyncAuditDao
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.*
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.Draft
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.*
import org.example.memosm.viewmodel.delegates.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MemoWriteAccountIsolationTest {
    @get:Rule val temp = TemporaryFolder()

    private inner class OfflineFixture(val scope: CoroutineScope) {
        val release = CompletableDeferred<Unit>()
        val sessions = AccountSession(scope)
        val a = Account(id = "A")
        val api = stub<MemosApi> { name, _ -> error("Offline API call: $name") }
        val context = sessions.activate(a, api, OkHttpClient(), networkReady = false)
        val state = MutableStateFlow(MemosUiState())
        val ops = linkedMapOf<String, PendingOp>()
        val memos = linkedMapOf<String, CachedMemo>()
        val visible = linkedMapOf<String, Memo>()
        val drafts = DraftManager(object : ContextWrapper(null) {
            override fun getFilesDir() = File(temp.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(temp.root, "cache").apply { mkdirs() }
        })
        val cache = MemoCacheRepository(stub<MemoDao> { name, args ->
            check(name == "insertMemo")
            val row = args[0] as CachedMemo
            memos[row.name] = row
            Unit
        })
        val sync = SyncManager(scope, SyncRepository(stub<PendingOpDao> { name, args ->
            check(name == "insert")
            release.await()
            val op = args[0] as PendingOp
            ops[op.id] = op
            Unit
        }), cache, DataStoreManager(MemoryPreferences()), object : SyncWorkScheduler {
            override fun schedule(accountId: String) {}
            override fun cancel(accountId: String) {}
        }, SyncAuditLogger(stub<SyncAuditDao> { name, _ ->
            when (name) { "insert" -> Unit; "count" -> 0; else -> error(name) }
        }), sessions, { null },
            onMemoSynced = { _, _ -> }, onMemoDeleted = {}, onCommentsRefresh = {}, onConflict = {})
        val draftDelegate = DraftDelegateImpl(scope, state, drafts, sessions, { actions }, {})
        val actions: MemoActionDelegate = MemoActionDelegateImpl(scope, state,
            stub<MemoListUpdater> { name, args ->
                check(name == "insertMemoIntoUserList")
                val memo = args[0] as Memo
                visible[memo.name!!] = memo
                Unit
            }, draftDelegate, { null }, { null }, sync, cache, sessions, drafts, { false }, {})
    }

    @Test fun `offline create finishing during version discovery keeps UI callback and supplied identity`() = runTest {
        val fixture = OfflineFixture(backgroundScope)
        var success = false
        fixture.actions.createMemo("A", Visibility.PRIVATE, memoId = "stable-id", onSuccess = { success = true })
        runCurrent()
        assertTrue(fixture.sessions.completeConnection(fixture.context, fixture.api))
        fixture.release.complete(Unit)
        runCurrent()
        assertTrue(success)
        assertEquals("stable-id", fixture.ops.values.single().id)
        assertEquals("offline-stable-id", fixture.ops.values.single().memoName)
        assertEquals("A", fixture.memos.values.single().accountId)
        assertEquals("offline-stable-id", fixture.visible.values.single().name)
        assertNotEquals(0, fixture.state.value.draft.composerResetToken)
    }

    @Test fun `bulk draft publish interrupted by switching deduplicates offline retry`() = runTest {
        // Unconfined dispatch lets real draft IO finish without virtual-time polling.
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val fixture = OfflineFixture(scope)
        val draft = Draft(id = "draft-a", content = "Draft A", visibility = Visibility.PRIVATE)
        fixture.drafts.saveDraft("A", draft)
        fixture.state.value = fixture.state.value.copy(draft = DraftState(drafts = listOf(draft)))
        fixture.draftDelegate.publishAllDrafts()
        fixture.sessions.activate(Account(id = "B"), fixture.api, OkHttpClient(), networkReady = false)
        fixture.state.value = fixture.state.value.forAccount(Account(id = "B"))
        fixture.release.complete(Unit)
        assertEquals(1, fixture.ops.size)
        assertTrue(fixture.visible.isEmpty())
        assertEquals(listOf(draft.id), fixture.drafts.getDrafts("A").map { it.id })
        fixture.sessions.activate(fixture.a, fixture.api, OkHttpClient(), networkReady = false)
        fixture.state.value = fixture.state.value.forAccount(fixture.a).copy(draft = DraftState(drafts = listOf(draft)))
        val done = CompletableDeferred<Int>()
        fixture.draftDelegate.publishAllDrafts { done.complete(it) }
        withContext(Dispatchers.Default) { withTimeout(10_000) { done.await() } }
        assertEquals(1, done.getCompleted())
        assertEquals(1, fixture.ops.size)
        assertEquals(1, fixture.memos.size)
        val expected = java.util.UUID.nameUUIDFromBytes(draft.id.toByteArray(Charsets.UTF_8)).toString()
        assertEquals(expected, fixture.ops.values.single().id)
        assertEquals("offline-$expected", fixture.memos.values.single().name)
        assertTrue(fixture.drafts.getDrafts("A").isEmpty())
    }

    @Test fun `write finishing after switch persists for A without mutating B UI or invoking A callback`() = runTest {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val aApi = stub<MemosApi> { name, _ ->
            check(name == "updateMemo")
            calls += "A"
            release.await()
            Memo(name = "memos/1", content = "A", pinned = true)
        }
        val bApi = stub<MemosApi> { name, _ -> error("Write was redirected to B: $name") }
        val sessions = AccountSession(backgroundScope)
        sessions.activate(Account(id = "A"), aApi, OkHttpClient())
        val state = MutableStateFlow(MemosUiState(isOnline = true))
        val writes = mutableListOf<CachedMemo>()
        val memos = MemoCacheRepository(stub<MemoDao> { name, args ->
            check(name == "saveMemoState")
            writes += args[0] as CachedMemo
            Unit
        })
        val sync = SyncManager(
            backgroundScope, SyncRepository(stub<PendingOpDao> { name, _ -> error(name) }), memos,
            DataStoreManager(MemoryPreferences()), object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            }, SyncAuditLogger(stub<SyncAuditDao> { name, _ -> error(name) }),
            sessions, { null },
            onMemoSynced = { _, _ -> }, onMemoDeleted = {}, onCommentsRefresh = {}, onConflict = {}
        )
        val updater = stub<MemoListUpdater> { name, _ -> error("Stale UI update: $name") }
        val delegate = MemoActionDelegateImpl(
            backgroundScope, state, updater,
            stub<DraftDelegate> { name, _ -> error("Stale draft update: $name") },
            { null }, { null }, sync, memos, sessions, DraftManager(ContextWrapper(null)), { true }
        )
        var callback = false
        delegate.updateMemoPinned(Memo(name = "memos/1", content = "A"), true) { callback = true }
        runCurrent()
        sessions.activate(Account(id = "B"), bApi, OkHttpClient())
        state.value = state.value.forAccount(Account(id = "B"))
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf("A"), calls)
        assertEquals(listOf("A"), writes.map { it.accountId })
        assertEquals("A", writes.single().toMemo()?.content)
        assertFalse(callback)
        assertTrue(state.value.userMemoList.list.items.isEmpty())
    }
}
