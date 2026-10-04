package org.example.memosm.data.cache

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Memo
import org.junit.Assert.*
import org.junit.Test

class MemoReconciliationTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext, MemoCacheDatabase::class.java).build()

    @Test fun missingMemoIsRemovedAcrossListsAndSearchForOnlyItsOwnAccount() = runBlocking {
        val db = database()
        try {
            val dao = db.memoDao()
            for (account in listOf("A", "B")) for (type in listOf(CacheListType.USER, CacheListType.EXPLORE, CacheListType.ARCHIVED)) {
                dao.insertMemo(CachedMemo.fromMemo(Memo(name = "memos/deleted", content = "deleted content"), account, type, 0).copy(cachedAt = 10))
            }
            assertEquals(listOf(CachedMemoCandidate("memos/deleted", 10)), dao.reconciliationCandidates("A"))
            assertEquals(3, dao.deleteMissingMemo("A", "memos/deleted", 10))
            assertNull(dao.getMemoByName("A", "memos/deleted"))
            val repo = MemoCacheRepository(dao)
            assertTrue(repo.searchCachedMemos("A", "deleted").isEmpty())
            assertEquals(1, repo.searchCachedMemos("B", "deleted").size)
            assertNotNull(dao.getMemoByName("B", "memos/deleted"))
        } finally { db.close() }
    }

    @Test fun editsQueuedDuringTheRequestAndNewerResponsesPreventDeletion() = runBlocking {
        val db = database()
        try {
            val dao = db.memoDao()
            val memo = Memo(name = "memos/edited", content = "unsynced local edit")
            dao.insertMemo(CachedMemo.fromMemo(memo, "A", CacheListType.USER, 0).copy(cachedAt = 10))
            val snapshot = dao.reconciliationCandidates("A").single()
            val op = PendingOp.new("A", PendingOpType.UPDATE, memo.name)
            db.pendingOpDao().insert(op)
            assertEquals(0, dao.deleteMissingMemo("A", snapshot.name, snapshot.cachedAt))
            assertEquals(memo.content, dao.getMemoByName("A", memo.name!!)?.toMemo()?.content)
            db.pendingOpDao().deleteOp(op.id)
            dao.insertMemo(CachedMemo.fromMemo(memo, "A", CacheListType.USER, 0).copy(cachedAt = 11))
            assertEquals(0, dao.deleteMissingMemo("A", snapshot.name, snapshot.cachedAt))
            assertEquals(1, dao.deleteMissingMemo("A", snapshot.name, 11))
        } finally { db.close() }
    }

    @Test fun fullListPruningPreservesPendingChangesAndEntriesCachedAfterTheDownloadStarted() = runBlocking {
        val db = database()
        try {
            val dao = db.memoDao()
            for (name in listOf("gone", "pending", "newer")) {
                dao.insertMemo(CachedMemo.fromMemo(Memo(name = "memos/$name", content = name), "A", CacheListType.USER, 0)
                    .copy(cachedAt = if (name == "newer") 11 else 10))
            }
            db.pendingOpDao().insert(PendingOp.new("A", PendingOpType.UPDATE, "memos/pending"))
            dao.pruneMissingFromList("A", "USER", listOf("memos/gone", "memos/pending", "memos/newer"), 10)
            assertEquals(setOf("memos/pending", "memos/newer"), dao.getMemos("A", "USER").map { it.name }.toSet())
        } finally { db.close() }
    }
}
