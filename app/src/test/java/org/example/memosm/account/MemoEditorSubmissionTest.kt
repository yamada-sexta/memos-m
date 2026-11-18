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
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.AccountSession
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.delegates.DraftDelegate
import org.example.memosm.viewmodel.delegates.MemoActionDelegateImpl
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoEditorSubmissionTest {
    internal class Fixture(
        scope: CoroutineScope,
    ) {
        val commit = CompletableDeferred<Unit>()
        var fail = false
        var accountPresent = true
        val ops = mutableListOf<PendingOp>()
        val scheduled = mutableListOf<String>()
        val state = MutableStateFlow(MemosUiState(isOnline = true))
        val sessions =
            AccountSession(scope).apply {
                activate(Account(id = "A"), stub<MemosApi> { method, _ -> error("UI must never send $method") }, OkHttpClient())
            }
        val actions =
            MemoActionDelegateImpl(
                scope,
                state,
                stub<DraftDelegate> { _, _ -> Unit },
                { null },
                MemoCacheRepository(
                    stub<MemoDao> { method, args ->
                        check(method == "commitMutation")
                        commit.await()
                        check(!fail) { "Disk full" }
                        ops += args[0] as PendingOp
                        Unit
                    },
                ),
                sessions,
                DraftManager(ContextWrapper(null)),
                object : SyncWorkScheduler {
                    override fun schedule(accountId: String) {
                        scheduled += accountId
                    }

                    override fun cancel(accountId: String) {}
                },
                accountExists = { accountPresent },
            )
    }

    @Test fun `editor success and scheduling wait for local transaction`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            var success = false
            fixture.actions.createMemo("Saved", Visibility.PRIVATE, onSuccess = { success = true })
            runCurrent()
            assertFalse(success)
            assertTrue(fixture.scheduled.isEmpty())
            fixture.commit.complete(Unit)
            runCurrent()
            assertTrue(success)
            assertEquals(listOf("A"), fixture.scheduled)
            assertEquals("CREATE", fixture.ops.single().type)
        }

    @Test fun `failed local transaction keeps editor open and does not schedule`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.fail = true
            fixture.commit.complete(Unit)
            var success = false
            var failed = false
            fixture.actions.updateMemo(
                Memo(name = "memos/1", content = "Original"),
                "Saved",
                Visibility.PRIVATE,
                emptyList(),
                onSuccess = { success = true },
                onError = { failed = true },
            )
            runCurrent()
            assertTrue(failed)
            assertFalse(success)
            assertTrue(fixture.ops.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            assertNotNull(fixture.state.value.error)
        }

    @Test fun `unchanged editor submission succeeds without a mutation or scheduling`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            val memo =
                Memo(
                    name = "memos/1",
                    content = "unchanged",
                    location =
                        org.example.memosm.model
                            .Location(placeholder = "keep this location"),
                )
            var success = false
            fixture.actions.updateMemo(memo, memo.content, memo.visibility, emptyList(), onSuccess = { success = true })
            runCurrent()
            assertTrue(success)
            assertTrue(fixture.ops.isEmpty())
            assertTrue(fixture.scheduled.isEmpty())
            assertNull(fixture.state.value.error)
        }

    @Test fun `explicit location removal queues only the location field`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.commit.complete(Unit)
            val memo =
                Memo(
                    name = "memos/1",
                    content = "unchanged",
                    location =
                        org.example.memosm.model
                            .Location(placeholder = "remove this location"),
                )
            fixture.actions.updateMemo(memo, memo.content, memo.visibility, emptyList(), location = null)
            runCurrent()
            assertEquals("location", fixture.ops.single().updateMask)
        }

    @Test fun `comments queue with parent and stable identity before success`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            var success = false
            fixture.actions.createComment(Memo(name = "memos/parent", content = "Parent"), "Reply", onSuccess = { success = true })
            runCurrent()
            assertFalse(success)
            fixture.commit.complete(Unit)
            runCurrent()
            val op = fixture.ops.single()
            assertTrue(success)
            assertEquals("memos/parent", op.parentName)
            assertEquals("offline-comment-${op.id}", op.memoName)
        }
}
