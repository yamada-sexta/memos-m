package org.example.memosm.viewmodel.manager

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.example.memosm.account.stub
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.*
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.viewmodel.AccountSession
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class MemoCacheReconcilerTest {
    private fun missing(code: Int = 404) = HttpException(Response.error<Any>(code, "missing".toResponseBody()))

    @Test fun `only a confirmed 404 removes the cache and displayed memo`() = runTest {
        val deleted = mutableListOf<String>()
        val notifications = mutableListOf<String>()
        val api = stub<MemosApi> { _, args ->
            if (args[0] == "memos/gone") throw missing()
            Memo(name = args[0] as String, content = "retained")
        }
        val repo = MemoCacheRepository(stub<MemoDao> { name, args -> when (name) {
            "reconciliationCandidates" -> listOf(CachedMemoCandidate("memos/gone", 10), CachedMemoCandidate("memos/later-page", 10))
            "deleteMissingMemo" -> { deleted += args[1] as String; 1 }
            else -> error(name)
        } })
        val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
        val reconciler = MemoCacheReconciler(session, repo) { notifications += it }
        reconciler.start(); runCurrent()
        assertEquals(listOf("memos/gone"), deleted)
        assertEquals(deleted, notifications)
    }

    @Test fun `transactional protection of a pending edit also preserves the displayed copy`() = runTest {
        val api = stub<MemosApi> { _, _ -> throw missing() }
        val repo = MemoCacheRepository(stub<MemoDao> { name, _ -> when (name) {
            "reconciliationCandidates" -> listOf(CachedMemoCandidate("memos/edited", 10))
            "deleteMissingMemo" -> 0
            else -> error(name)
        } })
        val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
        var notified = false
        MemoCacheReconciler(session, repo) { notified = true }.apply { start() }
        runCurrent()
        assertFalse(notified)
    }

    @Test fun `transport auth server errors and timeouts preserve every cached copy`() = runTest {
        for (failure in listOf(IOException("Offline"), missing(401), missing(403), missing(500))) {
            var deletions = 0
            val api = stub<MemosApi> { _, _ -> throw failure }
            val repo = MemoCacheRepository(stub<MemoDao> { name, _ -> when (name) {
                "reconciliationCandidates" -> (1..8).map { CachedMemoCandidate("memos/$it", 10) }
                "deleteMissingMemo" -> { deletions++; 1 }
                else -> error(name)
            } })
            val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
            MemoCacheReconciler(session, repo) {}.start(); runCurrent()
            assertEquals(0, deletions)
        }
        var deletions = 0
        val api = stub<MemosApi> { _, _ -> awaitCancellation() }
        val repo = MemoCacheRepository(stub<MemoDao> { name, _ -> when (name) {
            "reconciliationCandidates" -> listOf(CachedMemoCandidate("memos/slow", 10))
            "deleteMissingMemo" -> { deletions++; 1 }
            else -> error(name)
        } })
        val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
        MemoCacheReconciler(session, repo) {}.start(); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(0, deletions)
    }

    @Test fun `checks are bounded and overlapping refreshes coalesce`() = runTest {
        var requests = 0
        var snapshots = 0
        var active = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val api = stub<MemosApi> { _, _ ->
            requests++; active++; peak = maxOf(peak, active)
            release.await(); active--; Memo(content = "retained")
        }
        val repo = MemoCacheRepository(stub<MemoDao> { name, _ ->
            check(name == "reconciliationCandidates")
            snapshots++; (1..9).map { CachedMemoCandidate("memos/$it", 10) }
        })
        val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
        val reconciler = MemoCacheReconciler(session, repo) {}
        reconciler.start(); runCurrent()
        assertEquals(4, requests)
        reconciler.start(setOf("memos/5")); runCurrent()
        release.complete(Unit); runCurrent()
        assertEquals(1, snapshots)
        assertEquals(8, requests)
        assertEquals(4, peak)
    }

    @Test fun `late 404 for A cannot delete after an A B A activation`() = runTest {
        val release = CompletableDeferred<Unit>()
        var deleted = false
        val api = stub<MemosApi> { _, _ -> withContext(NonCancellable) { release.await() }; throw missing() }
        val repo = MemoCacheRepository(stub<MemoDao> { name, _ -> when (name) {
            "reconciliationCandidates" -> listOf(CachedMemoCandidate("memos/1", 10))
            "deleteMissingMemo" -> { deleted = true; 1 }
            else -> error(name)
        } })
        val session = AccountSession(backgroundScope).apply { activate(Account(id = "A"), api, OkHttpClient()) }
        MemoCacheReconciler(session, repo) {}.start(); runCurrent()
        session.activate(Account(id = "B"), api, OkHttpClient())
        session.activate(Account(id = "A"), api, OkHttpClient())
        release.complete(Unit); runCurrent()
        assertFalse(deleted)
    }
}
