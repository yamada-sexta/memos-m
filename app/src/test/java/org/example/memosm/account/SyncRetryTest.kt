package org.example.memosm.account

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
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
import org.example.memosm.viewmodel.AccountSession
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRetryTest {
    @Test fun `manual retry attempts the server despite the previous offline assessment`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.online = false
        fixture.manager.syncNow()
        runCurrent()
        assertEquals(0, fixture.attempts)

        fixture.manager.syncNow(force = true)
        runCurrent()
        assertEquals(1, fixture.attempts)
        assertNull(fixture.queued)
        assertFalse(fixture.manager.isSyncing.value)
    }

    @Test fun `manual retry immediately retries a failed operation while automatic sync backs off`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.failure = IOException("Server unavailable")
        fixture.manager.syncNow(force = true)
        runCurrent()
        assertEquals(1, fixture.attempts)
        assertEquals(1, fixture.queued?.attemptCount)
        assertFalse(fixture.manager.isSyncing.value)

        fixture.manager.syncNow()
        runCurrent()
        assertEquals(1, fixture.attempts)

        fixture.online = false
        fixture.failure = null
        fixture.manager.syncNow(force = true)
        runCurrent()
        assertEquals(2, fixture.attempts)
        assertNull(fixture.queued)
        assertFalse(fixture.manager.isSyncing.value)
    }

    @Test fun `every manual retry tries again even if the server keeps failing`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.online = false
        fixture.failure = IOException("Still offline")
        repeat(3) { attempt ->
            fixture.manager.syncNow(force = true)
            runCurrent()
            assertEquals(attempt + 1, fixture.attempts)
            assertEquals(attempt + 1, fixture.queued?.attemptCount)
            assertFalse(fixture.manager.isSyncing.value)
        }
    }

    @Test fun `manual retry retries permanent failures that automatic sync leaves queued`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.queued = fixture.queued!!.copy(
            permanentlyFailed = true, attemptCount = 4,
            lastAttemptAt = System.currentTimeMillis(), lastError = "Previously rejected"
        )
        fixture.manager.syncNow()
        runCurrent()
        assertEquals(0, fixture.attempts)
        assertNotNull(fixture.queued)

        fixture.manager.syncNow(force = true)
        runCurrent()
        assertEquals(1, fixture.attempts)
        assertNull(fixture.queued)
    }

    @Test fun `overlapping manual retries do not cancel or duplicate the active replay`() = runTest {
        val fixture = Fixture(backgroundScope)
        fixture.release = CompletableDeferred()
        fixture.manager.syncNow(force = true)
        fixture.manager.syncNow(force = true)
        assertTrue(fixture.manager.isSyncing.value)
        runCurrent()
        assertEquals(1, fixture.attempts)

        fixture.manager.syncNow(force = true)
        runCurrent()
        assertEquals(1, fixture.attempts)
        fixture.release!!.complete(Unit)
        runCurrent()
        assertNull(fixture.queued)
        assertFalse(fixture.manager.isSyncing.value)
    }

    private class Fixture(scope: CoroutineScope) {
        var online = true
        var queued: PendingOp? = PendingOp.new("retry-account", PendingOpType.DELETE, "memos/1")
        var failure: Exception? = null
        var release: CompletableDeferred<Unit>? = null
        var attempts = 0

        private val api = stub<MemosApi> { name, _ ->
            check(name == "deleteMemo") { name }
            attempts++
            release?.await()
            failure?.let { throw it }
            Unit
        }
        private val session = AccountSession(scope).apply {
            activate(Account(id = "retry-account"), api, OkHttpClient())
        }
        private val repository = SyncRepository(stub<PendingOpDao> { name, args ->
            when (name) {
                "getOps" -> listOfNotNull(queued)
                "getOp" -> queued
                "deleteOp" -> { queued = null; Unit }
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
            dataStoreManager = DataStoreManager(MemoryPreferences()),
            workScheduler = object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            },
            auditLogger = SyncAuditLogger(stub<SyncAuditDao> { name, _ ->
                if (name == "count") 0 else Unit
            }),
            accountSession = session,
            currentUserProvider = { null }, isOnlineProvider = { online },
            onMemoSynced = { _, _ -> }, onMemoDeleted = {},
            onCommentsRefresh = {}, onConflict = {}
        )
    }
}
