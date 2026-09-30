package org.example.memosm.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * Repository for the offline write queue (pending operations).
 */
class SyncRepository(private val dao: PendingOpDao) {

    suspend fun enqueue(op: PendingOp) {
        // Consecutive edits to the same memo can be collapsed safely: each
        // UPDATE payload is a complete optimistic memo, so only the newest
        // payload is needed while retaining the original server base version.
        if (op.type == PendingOpType.UPDATE.name && op.memoName != null) {
            val previous = dao.getLatestForMemo(
                op.accountId, op.memoName, PendingOpType.UPDATE.name
            )
            if (previous != null && previous.createdAt <= op.createdAt) {
                val mergedMask = (listOfNotNull(previous.updateMask, op.updateMask)
                    .flatMap { it.split(',') }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                    .joinToString(","))
                dao.insert(
                    op.copy(
                        id = previous.id,
                        createdAt = previous.createdAt,
                        baseUpdateTime = previous.baseUpdateTime ?: op.baseUpdateTime,
                        updateMask = mergedMask.ifBlank { op.updateMask }
                    )
                )
                return
            }
        }
        dao.insert(op)
    }

    suspend fun getOps(accountId: String): List<PendingOp> = dao.getOps(accountId)

    suspend fun getOp(id: String): PendingOp? = dao.getOp(id)

    fun getOpsFlow(accountId: String): Flow<List<PendingOp>> = dao.getOpsFlow(accountId)

    suspend fun deleteOp(id: String) = dao.deleteOp(id)

    suspend fun clearForAccount(accountId: String) = dao.deleteOpsForAccount(accountId)

    suspend fun markFailed(
        id: String,
        attemptCount: Int,
        lastError: String?,
        lastAttemptAt: Long,
        permanentlyFailed: Boolean = false
    ) = dao.markFailed(id, attemptCount, lastError, lastAttemptAt, permanentlyFailed)

    suspend fun renameMemo(accountId: String, oldName: String, newName: String) =
        dao.renameMemo(accountId, oldName, newName)

    suspend fun setBaseUpdateTime(id: String, baseUpdateTime: String?) =
        dao.setBaseUpdateTime(id, baseUpdateTime)
}
