package org.example.memosm.data.audit

import org.example.memosm.data.backup.BackupCategory
import org.example.memosm.data.backup.BackupSelection
import org.example.memosm.data.backup.BackupService
import org.example.memosm.data.backup.RestoreSelection
import java.io.File

/** Compatibility facade. Old callers cannot restore operations into the live outbox. */
class LocalRecoveryService(private val backup: BackupService, private val audit: SyncAuditLogger? = null) {
    suspend fun exportAccount(accountId: String): Result<File> {
        val result = backup.export(BackupSelection(setOf(accountId)))
        audit?.record(accountId, "EXPORT", if (result.isSuccess) "SUCCESS" else "FAILED", detailCode = "mmbackup")
        return result
    }

    suspend fun importFile(accountId: String, source: File): Result<ImportSummary> {
        val prepared = backup.inspect(source).getOrElse { return Result.failure(it) }
        return prepared.use {
            if (it.manifest.accounts.none { data -> data.identity.id == accountId }) return@use Result.failure(IllegalArgumentException("Backup belongs to a different account"))
            val result = backup.restore(it, RestoreSelection(setOf(accountId), setOf(BackupCategory.CACHE)))
            audit?.record(accountId, "IMPORT", if (result.isSuccess) "SUCCESS" else "FAILED", detailCode = "cache_only")
            result.map { summary -> ImportSummary(summary.memoCount, 0) }
        }
    }
    fun recoveryDirectory(): File = backup.recoveryDirectory()
    data class ImportSummary(val memoCount: Int, val pendingOpCount: Int)
}
