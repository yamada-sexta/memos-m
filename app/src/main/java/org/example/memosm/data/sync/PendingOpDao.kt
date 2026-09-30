package org.example.memosm.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingOpDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(op: PendingOp)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ops: List<PendingOp>)

    @Query("SELECT * FROM pending_ops WHERE accountId = :accountId ORDER BY createdAt ASC")
    suspend fun getOps(accountId: String): List<PendingOp>

    @Query("SELECT * FROM pending_ops WHERE id = :id LIMIT 1")
    suspend fun getOp(id: String): PendingOp?

    @Query("SELECT * FROM pending_ops WHERE accountId = :accountId ORDER BY createdAt ASC")
    fun getOpsFlow(accountId: String): kotlinx.coroutines.flow.Flow<List<PendingOp>>

    @Query("SELECT * FROM pending_ops WHERE accountId = :accountId AND memoName = :memoName AND type = :type ORDER BY createdAt DESC LIMIT 1")
    suspend fun getLatestForMemo(accountId: String, memoName: String, type: String): PendingOp?

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun deleteOp(id: String)

    @Query("DELETE FROM pending_ops WHERE accountId = :accountId")
    suspend fun deleteOpsForAccount(accountId: String)

    @Query(
        "UPDATE pending_ops SET attemptCount = :attemptCount, lastError = :lastError, " +
            "lastAttemptAt = :lastAttemptAt, permanentlyFailed = :permanentlyFailed WHERE id = :id"
    )
    suspend fun markFailed(
        id: String,
        attemptCount: Int,
        lastError: String?,
        lastAttemptAt: Long,
        permanentlyFailed: Boolean
    )

    @Query("UPDATE pending_ops SET memoName = CASE WHEN memoName = :oldName THEN :newName ELSE memoName END, parentName = CASE WHEN parentName = :oldName THEN :newName ELSE parentName END WHERE accountId = :accountId AND (memoName = :oldName OR parentName = :oldName)")
    suspend fun renameMemo(accountId: String, oldName: String, newName: String)

    @Query("UPDATE pending_ops SET baseUpdateTime = :baseUpdateTime WHERE id = :id")
    suspend fun setBaseUpdateTime(id: String, baseUpdateTime: String?)
}
