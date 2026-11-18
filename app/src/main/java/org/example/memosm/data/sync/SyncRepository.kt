package org.example.memosm.data.sync

import kotlinx.coroutines.flow.Flow
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.MemoCacheRepository

/** Durable operations and their outcomes. Payloads are never compacted over an in-flight operation. */
class SyncRepository(
    private val dao: PendingOpDao,
) {
    suspend fun getOps(accountId: String): List<PendingOp> = dao.getOps(accountId)

    suspend fun getOp(id: String): PendingOp? = dao.getOp(id)

    fun getOpsFlow(accountId: String): Flow<List<PendingOp>> = dao.getOpsFlow(accountId)

    suspend fun clearForAccount(accountId: String) = BackupCoordinator.withStorageLock { dao.deleteOpsForAccount(accountId) }

    suspend fun retryAll(accountId: String) = BackupCoordinator.withStorageLock { dao.retryAll(accountId) }

    suspend fun retryAuthentication(accountId: String) = BackupCoordinator.withStorageLock { dao.retryAuthentication(accountId) }

    suspend fun markFailed(
        id: String,
        attemptCount: Int,
        lastError: String?,
        lastAttemptAt: Long,
        permanentlyFailed: Boolean = false,
    ) = dao.markFailed(id, attemptCount, lastError, lastAttemptAt, permanentlyFailed)

    suspend fun setStatus(
        id: String,
        status: PendingOpStatus,
        serverJson: String? = null,
    ) = dao.setStatus(id, status.name, serverJson)

    suspend fun discard(
        op: PendingOp,
        cache: MemoCacheRepository,
    ) = cache.discardMutation(op)

    suspend fun resolveConflict(
        item: ConflictItem,
        resolution: ConflictResolution,
        mergedContent: String?,
        cache: MemoCacheRepository,
    ) {
        val op = dao.getOp(item.opId) ?: return
        require(op.accountId == item.accountId)
        if (op.status != PendingOpStatus.CONFLICT.name || resolution == ConflictResolution.LATER) return
        val resolved =
            when (resolution) {
                ConflictResolution.KEEP_SERVER -> null
                ConflictResolution.KEEP_LOCAL -> op.payloadJson
                ConflictResolution.MERGE -> GsonProvider.gson.toJson(decodeMemo(op).copy(content = requireNotNull(mergedContent)))
                ConflictResolution.LATER -> return
            }
        val resolvedMask =
            if (resolution == ConflictResolution.MERGE) {
                (
                    op.updateMask
                        .orEmpty()
                        .split(',')
                        .map(String::trim)
                        .filter(String::isNotEmpty) + "content"
                ).distinct()
                    .joinToString(",")
            } else {
                null
            }
        cache.resolveMutation(op, item.serverMemo, resolved, resolvedMask)
    }
}
