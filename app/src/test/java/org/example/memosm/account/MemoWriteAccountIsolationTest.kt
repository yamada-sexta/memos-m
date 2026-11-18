package org.example.memosm.account

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.forAccount
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoWriteAccountIsolationTest {
    @Test fun `write completing after account switch persists for original account without stale callback`() =
        runTest {
            val fixture = MemoEditorSubmissionTest.Fixture(backgroundScope)
            var success = false
            fixture.actions.updateMemoPinned(Memo(name = "memos/1", content = "A"), true) { success = true }
            runCurrent()
            fixture.sessions.activate(Account(id = "B"), stub<MemosApi> { _, _ -> error("No network from UI") }, OkHttpClient())
            fixture.state.value = fixture.state.value.forAccount(Account(id = "B"))
            val stateB = fixture.state.value
            fixture.commit.complete(Unit)
            runCurrent()
            assertEquals("A", fixture.ops.single().accountId)
            assertEquals(listOf("A"), fixture.scheduled)
            assertFalse(success)
            assertEquals(stateB, fixture.state.value)
        }

    @Test fun `removed originating account cannot be recreated by queued local write`() =
        runTest {
            val fixture = MemoEditorSubmissionTest.Fixture(backgroundScope)
            fixture.actions.updateMemoPinned(Memo(name = "memos/1", content = "A"), true)
            fixture.accountPresent = false
            fixture.commit.complete(Unit)
            runCurrent()
            assertTrue(fixture.ops.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            assertNotNull(fixture.state.value.error)
        }

    @Test fun `stable draft supplied identity survives local queue submission`() =
        runTest {
            val fixture = MemoEditorSubmissionTest.Fixture(backgroundScope)
            fixture.commit.complete(Unit)
            fixture.actions.createMemo("Draft", Visibility.PRIVATE, memoId = "stable-draft-id")
            runCurrent()
            assertEquals("stable-draft-id", fixture.ops.single().id)
            assertEquals("offline-stable-draft-id", fixture.ops.single().memoName)
        }

    @Test fun `selected thread is unchanged by suspended comment for another parent`() =
        runTest {
            val fixture = MemoEditorSubmissionTest.Fixture(backgroundScope)
            fixture.actions.createComment(Memo(name = "memos/A", content = "A"), "Reply")
            runCurrent()
            val parentB = Memo(name = "memos/B", content = "B")
            fixture.actions.selectMemo(parentB)
            fixture.commit.complete(Unit)
            runCurrent()
            assertEquals("memos/A", fixture.ops.single().parentName)
            assertEquals(parentB, fixture.state.value.detailPane.selectedMemo)
        }
}
