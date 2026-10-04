package org.example.memosm.data.cache

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.model.Memo
import org.junit.Assert.*
import org.junit.Test

class MultiAccountCacheTest {
    @Test fun duplicateNamesAndRefreshesRemainAccountIsolatedAndPreserveOutbox() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MemoCacheDatabase::class.java
        ).build()
        try {
            val dao = db.memoDao()
            val local = Memo(name = "memos/1", content = "unsent A")
            dao.insertMemo(CachedMemo.fromMemo(local, "A", CacheListType.USER, 0))
            dao.insertMemo(CachedMemo.fromMemo(local.copy(content = "server B"), "B", CacheListType.USER, 0))
            db.pendingOpDao().insert(PendingOp.new("A", PendingOpType.UPDATE, local.name))
            dao.cacheRemoteMemos("A", "USER", listOf(
                CachedMemo.fromMemo(local.copy(content = "stale A"), "A", CacheListType.USER, 0),
                CachedMemo.fromMemo(Memo(name = "memos/2", content = "new A"), "A", CacheListType.USER, 1)
            ), replace = true)
            assertEquals("unsent A", dao.getMemoByName("A", "memos/1")?.toMemo()?.content)
            assertEquals("server B", dao.getMemoByName("B", "memos/1")?.toMemo()?.content)
            dao.cacheRemoteMemos("A", "USER", emptyList(), replace = true)
            assertEquals(listOf("memos/1"), dao.getMemos("A", "USER").map { it.name })
            assertEquals(1, db.pendingOpDao().getOps("A").size)
            dao.deleteAllForAccount("A")
            assertEquals(1, dao.getMemos("B", "USER").size)
        } finally { db.close() }
    }
}
