package org.example.memosm.data.audit

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncAuditDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: SyncAuditEntry)

    @Query("SELECT * FROM sync_audit_log ORDER BY occurredAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SyncAuditEntry>>

    @Query("SELECT COUNT(*) FROM sync_audit_log")
    suspend fun count(): Int

    @Query("DELETE FROM sync_audit_log WHERE id IN (SELECT id FROM sync_audit_log ORDER BY occurredAt ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}
