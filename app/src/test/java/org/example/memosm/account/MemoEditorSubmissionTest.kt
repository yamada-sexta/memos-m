package org.example.memosm.account

import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.audit.SyncAuditDao
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.PendingOpDao
import org.example.memosm.data.sync.SyncManager
import org.example.memosm.data.sync.SyncRepository
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.AccountSession
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.delegates.DraftDelegate
import org.example.memosm.viewmodel.delegates.MemoActionDelegateImpl
import org.example.memosm.viewmodel.delegates.MemoListUpdater
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorSubmissionTest {
    private class Fixture(scope: CoroutineScope) {
        var reject = false
        val cacheWrite = CompletableDeferred<Unit>()
        val stored = mutableListOf<CachedMemo>()
        val state = MutableStateFlow(MemosUiState(isOnline = true))
        val sessions = AccountSession(scope)
        val api = stub<MemosApi> { name, _ ->
            if (reject) error("Server rejected $name")
            when (name) {
                "createMemo", "updateMemo", "createMemoComment" -> Memo(name = "memos/result", content = "Saved")
                else -> error(name)
            }
        }
        init { sessions.activate(Account(id = "editor-submission"), api, OkHttpClient()) }
        val cache = MemoCacheRepository(stub<MemoDao> { name, args ->
            check(name == "insertMemo" || name == "saveMemoState")
            cacheWrite.await()
            stored += args[0] as CachedMemo
            Unit
        })
        val sync = SyncManager(scope, SyncRepository(stub<PendingOpDao> { name, _ -> error(name) }),
            cache, DataStoreManager(MemoryPreferences()), object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            }, SyncAuditLogger(stub<SyncAuditDao> { name, _ -> error(name) }), sessions, { null },
            onMemoSynced = { _, _ -> }, onMemoDeleted = {}, onCommentsRefresh = {}, onConflict = {})
        val actions = MemoActionDelegateImpl(scope, state,
            stub<MemoListUpdater> { _, _ -> Unit }, stub<DraftDelegate> { _, _ -> Unit },
            { null }, { null }, sync, cache, sessions, DraftManager(ContextWrapper(null)), { true })
    }

    @Test fun `create success waits for cache before editor can finish`() = runTest {
        val fixture = Fixture(backgroundScope)
        var success = false
        fixture.actions.createMemo("Saved", Visibility.PRIVATE, onSuccess = { success = true })
        runCurrent()
        assertFalse(success)
        fixture.cacheWrite.complete(Unit)
        runCurrent()
        assertTrue(success)
        assertEquals("Saved", fixture.stored.single().toMemo()!!.content)
    }

    @Test fun `update success waits for cache before editor can finish`() = runTest {
        val fixture = Fixture(backgroundScope)
        var success = false
        fixture.actions.updateMemo(Memo(name = "memos/original", content = "Original"), "Saved",
            Visibility.PRIVATE, emptyList(), onSuccess = { success = true })
        runCurrent()
        assertFalse(success)
        fixture.cacheWrite.complete(Unit)
        runCurrent()
        assertTrue(success)
        assertEquals("Saved", fixture.stored.single().toMemo()!!.content)
    }

    @Test fun `comment success waits for cache before editor can finish`() = runTest {
        val fixture = Fixture(backgroundScope)
        var success = false
        fixture.actions.createComment(Memo(name = "memos/parent", content = "Parent"), "Saved",
            onSuccess = { success = true })
        runCurrent()
        assertFalse(success)
        fixture.cacheWrite.complete(Unit)
        runCurrent()
        assertTrue(success)
        assertEquals("memos/parent", fixture.stored.single().parentName)
    }

    @Test fun `rejected update reports failure and a retry can succeed`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.reject = true
        var failed = false
        var success = false
        val memo = Memo(name = "memos/original", content = "Original")
        fixture.actions.updateMemo(memo, "Saved", Visibility.PRIVATE, emptyList(),
            onError = { failed = true }, onSuccess = { success = true })
        runCurrent()
        assertTrue(failed)
        assertFalse(success)
        assertNotNull(fixture.state.value.error)
        fixture.reject = false
        fixture.cacheWrite.complete(Unit)
        fixture.actions.updateMemo(memo, "Saved", Visibility.PRIVATE, emptyList(), onSuccess = { success = true })
        runCurrent()
        assertTrue(success)
    }

    @Test fun `rejected comment reports failure without success`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.reject = true
        var failed = false
        var success = false
        fixture.actions.createComment(Memo(name = "memos/parent", content = "Parent"), "Saved",
            onError = { failed = true }, onSuccess = { success = true })
        runCurrent()
        assertTrue(failed)
        assertFalse(success)
        assertTrue(fixture.stored.isEmpty())
    }
}
