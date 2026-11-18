package org.example.memosm.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingOpDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(op: PendingOp)

    @Query("SELECT * FROM pending_ops WHERE accountId = :accountId ORDER BY sequence ASC, createdAt ASC, id ASC")
    suspend fun getOps(accountId: String): List<PendingOp>

    @Query("SELECT * FROM pending_ops WHERE id = :id LIMIT 1")
    suspend fun getOp(id: String): PendingOp?

    @Query("SELECT * FROM pending_ops WHERE accountId = :accountId ORDER BY sequence ASC, createdAt ASC, id ASC")
    fun getOpsFlow(accountId: String): kotlinx.coroutines.flow.Flow<List<PendingOp>>

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun deleteOp(id: String)

    @Query("DELETE FROM pending_ops WHERE accountId = :accountId")
    suspend fun deleteOpsForAccount(accountId: String)

    @Query(
        "UPDATE pending_ops SET attemptCount = :attemptCount, lastError = :lastError, " +
            "lastAttemptAt = :lastAttemptAt, permanentlyFailed = :permanentlyFailed WHERE id = :id",
    )
    suspend fun markFailed(
        id: String,
        attemptCount: Int,
        lastError: String?,
        lastAttemptAt: Long,
        permanentlyFailed: Boolean,
    )

    @Query("UPDATE pending_ops SET status = :status, conflictServerJson = :conflictServerJson WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: String,
        conflictServerJson: String? = null,
    )

    @Query(
        "UPDATE pending_ops SET status = 'PENDING', permanentlyFailed = 0, lastError = NULL, lastAttemptAt = 0 WHERE accountId = :accountId AND status != 'CONFLICT'",
    )
    suspend fun retryAll(accountId: String)

    @Query(
        "UPDATE pending_ops SET status = 'PENDING', lastError = NULL, lastAttemptAt = 0 WHERE accountId = :accountId AND status = 'AUTH_REQUIRED'",
    )
    suspend fun retryAuthentication(accountId: String)
}
