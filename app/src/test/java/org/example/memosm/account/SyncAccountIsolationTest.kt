package org.example.memosm.account

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.SyncAuditDao
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.*
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.viewmodel.AccountSession
import org.junit.Assert.*
import org.junit.Test

class SyncAccountIsolationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `two activity sync managers replay a shared queued delete only once`() = runTest {
        val op = PendingOp.new("shared-account", PendingOpType.DELETE, "memos/1")
        var queued: PendingOp? = op
        var deletes = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val api = stub<MemosApi> { name, _ ->
            check(name == "deleteMemo") { name }
            deletes++
            started.complete(Unit)
            release.await()
            Unit
        }
        val repository = SyncRepository(stub<PendingOpDao> { name, _ ->
            when (name) {
                "getOps" -> listOfNotNull(queued)
                "getOp" -> queued
                "deleteOp" -> { queued = null; Unit }
                else -> error(name)
            }
        })
        val preferences = DataStoreManager(MemoryPreferences())
        fun manager(): SyncManager {
            val session = AccountSession(backgroundScope)
            session.activate(Account(id = op.accountId), api, OkHttpClient())
            return SyncManager(
                scope = backgroundScope,
                repository = repository,
                memoCacheRepository = MemoCacheRepository(stub<MemoDao> { _, _ -> Unit }),
                dataStoreManager = preferences,
                workScheduler = object : SyncWorkScheduler {
                    override fun schedule(accountId: String) {}
                    override fun cancel(accountId: String) {}
                },
                auditLogger = SyncAuditLogger(stub<SyncAuditDao> { _, _ -> Unit }),
                accountSession = session,
                currentUserProvider = { null },
                onMemoSynced = { _, _ -> }, onMemoDeleted = {},
                onCommentsRefresh = {}, onConflict = {}
            )
        }
        val first = manager()
        val second = manager()
        first.pushPendingChanges()
        started.await()
        second.pushPendingChanges()
        runCurrent()
        assertEquals(1, deletes)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, deletes)
        assertNull(queued)
        assertFalse(first.isSyncing.value)
        assertFalse(second.isSyncing.value)
    }

    @Test fun `all conflict resolutions reject another accounts operation without touching storage or API`() = runTest {
        val sessions = AccountSession(backgroundScope)
        val api = stub<MemosApi> { name, _ -> error("Foreign API call: $name") }
        sessions.activate(Account(id = "B"), api, OkHttpClient())
        val repository = SyncRepository(stub<PendingOpDao> { name, _ -> error("Foreign storage call: $name") })
        val memos = MemoCacheRepository(stub<MemoDao> { name, _ -> error("Foreign cache call: $name") })
        val audit = SyncAuditLogger(stub<SyncAuditDao> { name, _ -> error("Unexpected audit: $name") })
        val sync = SyncManager(
            scope = backgroundScope, repository = repository, memoCacheRepository = memos,
            dataStoreManager = DataStoreManager(MemoryPreferences()),
            workScheduler = object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            }, auditLogger = audit,
            accountSession = sessions,
            currentUserProvider = { null },
            onMemoSynced = { _, _ -> error("Foreign memo callback") },
            onMemoDeleted = {}, onCommentsRefresh = {}, onConflict = {}
        )
        val memo = Memo(name = "memos/1", content = "A")
        val conflict = ConflictItem("op-a", "A", memo.name!!, memo, memo)
        ConflictResolution.entries.forEach { sync.resolveConflict(conflict, it, "merge") }
        assertEquals("B", sessions.current?.account?.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `unchanged queue reappears on same account reconnect and stays isolated on switch`() = runTest {
        val sessions = AccountSession(backgroundScope)
        val api = stub<MemosApi> { name, _ -> error(name) }
        val a = Account(id = "A")
        sessions.activate(a, api, OkHttpClient())
        val op = PendingOp.new("A", PendingOpType.CREATE, "offline-1")
        val repository = SyncRepository(stub<PendingOpDao> { name, args ->
            check(name == "getOpsFlow")
            flowOf(if (args[0] == "A") listOf(op) else emptyList())
        })
        val sync = SyncManager(backgroundScope, repository, MemoCacheRepository(stub { name, _ -> error(name) }),
            DataStoreManager(MemoryPreferences()), object : SyncWorkScheduler {
                override fun schedule(accountId: String) {}
                override fun cancel(accountId: String) {}
            }, SyncAuditLogger(stub { name, _ -> error(name) }), sessions,
            { null }, onMemoSynced = { _, _ -> }, onMemoDeleted = {},
            onCommentsRefresh = {}, onConflict = {})
        val snapshots = mutableListOf<SyncManager.AccountPendingOps>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            sync.currentPendingOps.collect { snapshots += it }
        }
        sync.startObserving(sessions.contexts.map { it?.account?.id })
        runCurrent()
        assertEquals(listOf(op), snapshots.last().ops)
        val reconnected = sessions.activate(a, api, OkHttpClient())
        runCurrent()
        assertSame(reconnected, snapshots.last().context)
        assertEquals(listOf(op), snapshots.last().ops)
        sessions.activate(Account(id = "B"), api, OkHttpClient())
        runCurrent()
        assertEquals("B", snapshots.last().context.account.id)
        assertTrue(snapshots.last().ops.isEmpty())
        assertTrue(snapshots.all { snap -> snap.ops.all { it.accountId == snap.context.account.id } })
    }

    @Test fun `replay executor rejects foreign op even when memo resource names match`() = runTest {
        val api = stub<MemosApi> { name, _ -> error("Foreign API call: $name") }
        val executor = OpReplayExecutor(
            api, SyncRepository(stub { name, _ -> error(name) }),
            MemoCacheRepository(stub { name, _ -> error(name) }),
            SyncAuditLogger(stub { name, _ -> error(name) }), "B"
        )
        val op = PendingOp.new("A", PendingOpType.DELETE, "memos/1")
        try {
            executor.replay(op)
            fail("Foreign op should be rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
