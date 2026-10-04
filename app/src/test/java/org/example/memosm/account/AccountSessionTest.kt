package org.example.memosm.account

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.sync.ConflictItem
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.Shortcut
import org.example.memosm.viewmodel.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountSessionTest {
    private val api = stub<MemosApi> { name, _ -> error("Unexpected API call: $name") }
    private val client = OkHttpClient()

    @Test fun `returning to A rejects requests from its earlier activation`() = runTest {
        val sessions = AccountSession(backgroundScope)
        val a = Account(id = "A")
        val old = sessions.activate(a, api, client)
        sessions.activate(Account(id = "B"), api, client)
        val current = sessions.activate(a, api, client)
        val state = MutableStateFlow(MemosUiState())
        sessions.update(state, old) { it.copy(textCacheCount = 99) }
        assertEquals(0, state.value.textCacheCount)
        sessions.update(state, current) { it.copy(textCacheCount = 3) }
        assertEquals(3, state.value.textCacheCount)
        assertTrue(current.generation > old.generation)
    }

    @Test fun `switch cancels reads while independently captured writes retain their account`() = runTest {
        val sessions = AccountSession(backgroundScope)
        val context = sessions.activate(Account(id = "A", hostUrl = "a", accessToken = "token-a"), api, client)
        val release = CompletableDeferred<Unit>()
        val read = sessions.readScope.launch { release.await() }
        var writtenFor: Account? = null
        val write = backgroundScope.launch { release.await(); writtenFor = context.account }
        runCurrent()
        sessions.activate(Account(id = "B", hostUrl = "b", accessToken = "token-b"), api, client)
        release.complete(Unit)
        runCurrent()
        assertTrue(read.isCancelled)
        assertTrue(write.isCompleted)
        assertEquals("A", writtenFor?.id)
        assertEquals("token-a", writtenFor?.accessToken)
    }

    @Test fun `version discovery preserves same account work and cannot upgrade an old activation`() = runTest {
        val sessions = AccountSession(backgroundScope)
        val context = sessions.activate(Account(id = "A"), api, client, networkReady = false)
        val release = CompletableDeferred<Unit>()
        val read = sessions.readScope.launch { release.await() }
        runCurrent()
        val detected = stub<MemosApi> { name, _ -> error(name) }
        assertTrue(sessions.completeConnection(context, detected))
        assertSame(context, sessions.current)
        assertSame(detected, context.api)
        assertTrue(context.networkReady)
        assertFalse(read.isCancelled)
        release.complete(Unit)
        runCurrent()
        assertTrue(read.isCompleted)
        sessions.activate(Account(id = "B"), api, client)
        assertFalse(sessions.completeConnection(context, api))
        assertEquals("B", sessions.current?.account?.id)
    }

    @Test fun `transition clears every account owned surface including older leftover state`() {
        val memo = Memo(name = "memos/1", content = "A")
        val shortcut = Shortcut(name = "users/1/shortcuts/1", filter = "A")
        val op = PendingOp.new("A", PendingOpType.UPDATE, memo.name)
        val old = MemosUiState(
            accounts = listOf(Account(id = "B", isActive = true)),
            userMemoList = MemoListState(PaginatedListState(listOf(memo)), listOf(shortcut), shortcut, "#A"),
            attachmentList = AttachmentListState(cellWidth = 144f),
            detailPane = DetailPaneState(memo, PaginatedListState(listOf(memo))),
            draft = DraftState(currentEditingDraftId = "draft-a", isDraftLoaded = true),
            pendingOps = listOf(op), pendingOpsCount = 1,
            conflict = ConflictItem(op.id, "A", memo.name!!, memo, memo),
            textCacheCount = 45, isOnline = true,
            appSettings = AppSettings(pageSize = 25)
        )
        val next = old.forAccount(Account(id = "B", hostUrl = "b", accessToken = "token-b"))
        assertEquals(MemoListState(), next.userMemoList)
        assertEquals(DetailPaneState(), next.detailPane)
        assertEquals(DraftState(), next.draft)
        assertTrue(next.pendingOps.isEmpty())
        assertNull(next.conflict)
        assertFalse(next.isOnline)
        assertEquals(0, next.textCacheCount)
        assertEquals(25, next.appSettings.pageSize)
        assertEquals(144f, next.attachmentList.cellWidth)
        assertEquals("b", next.session.hostUrl)
        assertEquals(SessionState(), next.forAccount(null).session)
    }
}
