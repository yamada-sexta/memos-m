package org.example.memosm.account

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.SyncAuditDao
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpDao
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.SyncManager
import org.example.memosm.data.sync.SyncRepository
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.viewmodel.AccountSession
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRetryTest {
    @Test fun `pushing changes immediately retries a failed operation`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.preferences.saveLastSyncTime("retry-account", 123L)
        fixture.failure = IOException("Server unavailable")
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertEquals(1, fixture.attempts)
        assertEquals(1, fixture.queued?.attemptCount)
        assertEquals(123L, fixture.preferences.lastSyncTime("retry-account").first())
        assertFalse(fixture.manager.isSyncing.value)

        fixture.failure = null
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertEquals(2, fixture.attempts)
        assertNull(fixture.queued)
        assertTrue(fixture.preferences.lastSyncTime("retry-account").first() > 123L)
        assertFalse(fixture.manager.isSyncing.value)
    }

    @Test fun `every manual retry tries again even if the server keeps failing`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.failure = IOException("Still offline")
        repeat(3) { attempt ->
            fixture.manager.pushPendingChanges()
            runCurrent()
            assertEquals(attempt + 1, fixture.attempts)
            assertEquals(attempt + 1, fixture.queued?.attemptCount)
            assertEquals(0L, fixture.preferences.lastSyncTime("retry-account").first())
            assertFalse(fixture.manager.isSyncing.value)
        }
    }

    @Test fun `pushing changes retries previously rejected operations`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.queued = fixture.queued!!.copy(
            permanentlyFailed = true, attemptCount = 4,
            lastAttemptAt = System.currentTimeMillis(), lastError = "Previously rejected"
        )
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertEquals(1, fixture.attempts)
        assertNull(fixture.queued)
    }

    @Test fun `overlapping manual retries do not cancel or duplicate the active replay`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.release = CompletableDeferred()
        fixture.manager.pushPendingChanges()
        fixture.manager.pushPendingChanges()
        assertTrue(fixture.manager.isSyncing.value)
        runCurrent()
        assertEquals(1, fixture.attempts)

        fixture.manager.pushPendingChanges()
        runCurrent()
        assertEquals(1, fixture.attempts)
        fixture.release!!.complete(Unit)
        runCurrent()
        assertNull(fixture.queued)
        assertFalse(fixture.manager.isSyncing.value)
    }

    @Test fun `failed pushes schedule another server attempt`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.failure = IOException("Offline")
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertEquals(1, fixture.attempts)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, fixture.attempts)
        assertNotNull(fixture.queued)
    }

    @Test fun `failed memo creation remains queued and is only acknowledged after a server response`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.queued = PendingOp.new(
            "retry-account", PendingOpType.CREATE, "local-1",
            payloadJson = GsonProvider.gson.toJson(Memo(content = "Unsynced memo"))
        )
        fixture.failure = IOException("Response unavailable")
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertNotNull(fixture.queued)
        assertEquals("local-1", fixture.queued?.memoName)
        assertEquals(0, fixture.acknowledgedMemos)
        assertEquals(0L, fixture.preferences.lastSyncTime("retry-account").first())

        fixture.failure = null
        fixture.manager.pushPendingChanges()
        runCurrent()
        assertNull(fixture.queued)
        assertEquals(1, fixture.acknowledgedMemos)
        assertTrue(fixture.preferences.lastSyncTime("retry-account").first() > 0L)
    }

    private class Fixture(scope: CoroutineScope) {
        var queued: PendingOp? = PendingOp.new("retry-account", PendingOpType.DELETE, "memos/1")
        var failure: Exception? = null
        var release: CompletableDeferred<Unit>? = null
        var attempts = 0
        var acknowledgedMemos = 0
        val preferences = DataStoreManager(MemoryPreferences())

        private val api = stub<MemosApi> { name, _ ->
            check(name == "deleteMemo" || name == "createMemo") { name }
            attempts++
            release?.await()
            failure?.let { throw it }
            if (name == "createMemo") Memo(name = "memos/1", content = "Unsynced memo") else Unit
        }
        private val session = AccountSession(scope).apply {
            activate(Account(id = "retry-account"), api, OkHttpClient())
        }
        private val repository = SyncRepository(stub<PendingOpDao> { name, args ->
            when (name) {
                "getOps" -> listOfNotNull(queued)
                "getOp" -> queued
                "deleteOp" -> { queued = null; Unit }
                "renameMemo" -> { queued = queued!!.copy(memoName = args[2] as String); Unit }
                "markFailed" -> {
                    queued = queued!!.copy(
                        attemptCount = args[1] as Int,
                        lastError = args[2] as String?,
                        lastAttemptAt = args[3] as Long,
                        permanentlyFailed = args[4] as Boolean
                    )
                    Unit
                }
                else -> error(name)
            }
        })
        val manager = SyncManager(
            scope = scope, repository = repository,
            memoCacheRepository = MemoCacheRepository(stub<MemoDao> { _, _ -> Unit }),
            dataStoreManager = preferences,
            workScheduler = object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            },
            auditLogger = SyncAuditLogger(stub<SyncAuditDao> { name, _ ->
                if (name == "count") 0 else Unit
            }),
            accountSession = session,
            currentUserProvider = { null },
            onMemoSynced = { _, _ -> acknowledgedMemos++ }, onMemoDeleted = {},
            onCommentsRefresh = {}, onConflict = {}
        )
    }
}
