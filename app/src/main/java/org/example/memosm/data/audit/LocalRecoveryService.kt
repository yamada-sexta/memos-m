package org.example.memosm.data.audit

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoDao
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpDao
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Local-only recovery export/import. The archive contains cached memo rows and
 * queued operation metadata/payloads needed to continue offline work. It never
 * contains account credentials or audit payloads. Files are private, atomic,
 * size-limited, and protected by a SHA-256 checksum over the canonical body.
 */
class LocalRecoveryService(
    private val context: Context,
    private val memoDao: MemoDao,
    private val pendingOpDao: PendingOpDao,
    private val audit: SyncAuditLogger? = null
) {
    private val gson = Gson()

    suspend fun exportAccount(accountId: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(accountId.isNotBlank()) { "Account is required" }
            val rows = CacheListType.entries.flatMap { type ->
                memoDao.getMemos(accountId, type.name)
            }.distinctBy { "${it.accountId}\u0000${it.listType}\u0000${it.name}" }
            val ops = pendingOpDao.getOps(accountId)
            val payload = RecoveryPayload(
                accountId = accountId,
                exportedAt = System.currentTimeMillis(),
                memos = rows,
                pendingOps = ops.map { RecoveryPendingOp.from(it) }
            )
            val body = gson.toJson(payload)
            val envelope = JsonObject().apply {
                addProperty("format", FORMAT)
                addProperty("version", VERSION)
                addProperty("body", body)
                addProperty("checksum", sha256(body.toByteArray(StandardCharsets.UTF_8)))
            }
            val directory = recoveryDirectory().apply { mkdirs() }
            val target = File(directory, "memos-recovery-${System.currentTimeMillis()}-${randomSuffix()}.json")
            writeAtomically(target, gson.toJson(envelope).toByteArray(StandardCharsets.UTF_8))
            audit?.record(accountId, "EXPORT", "SUCCESS", detailCode = "memo_rows=${rows.size};outbox=${ops.size}")
            target
        }.onFailure {
            audit?.record(accountId, "EXPORT", "FAILED", detailCode = "validation_or_io")
        }
    }

    /** Imports by merge: existing local rows remain unless an imported key replaces them. */
    suspend fun importFile(accountId: String, source: File): Result<ImportSummary> = withContext(Dispatchers.IO) {
        runCatching {
            require(accountId.isNotBlank()) { "Account is required" }
            val safeSource = validateSource(source)
            val bytes = safeSource.readBytes()
            require(bytes.size <= MAX_ARCHIVE_BYTES) { "Recovery file is too large" }
            val root = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject
            require(root.get("format")?.asString == FORMAT) { "Unsupported recovery file" }
            require(root.get("version")?.asInt == VERSION) { "Unsupported recovery version" }
            val body = root.get("body")?.asString ?: error("Missing recovery body")
            val expected = root.get("checksum")?.asString ?: error("Missing recovery checksum")
            require(MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), sha256(body.toByteArray(StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8))) {
                "Recovery checksum mismatch"
            }
            val payload = gson.fromJson(body, RecoveryPayload::class.java) ?: error("Invalid recovery body")
            require(payload.accountId == accountId) { "Recovery belongs to a different account" }
            require(payload.memos.size <= MAX_ROWS && payload.pendingOps.size <= MAX_ROWS) { "Recovery contains too many rows" }
            require(payload.memos.all { it.accountId == accountId && it.name.isNotBlank() && it.memoJson.isNotBlank() }) { "Invalid memo row" }
            require(payload.pendingOps.all { it.accountId == accountId && it.id.isNotBlank() && it.type.isNotBlank() }) { "Invalid outbox row" }
            memoDao.insertMemos(payload.memos)
            pendingOpDao.insertAll(payload.pendingOps.map { it.toPendingOp() })
            val result = ImportSummary(payload.memos.size, payload.pendingOps.size)
            audit?.record(accountId, "IMPORT", "SUCCESS", detailCode = "memo_rows=${result.memoCount};outbox=${result.pendingOpCount}")
            result
        }.onFailure {
            audit?.record(accountId, "IMPORT", "FAILED", detailCode = "validation_or_io")
        }
    }

    fun recoveryDirectory(): File = File(context.noBackupFilesDir, RECOVERY_DIRECTORY)

    private fun validateSource(source: File): File {
        require(source.exists() && source.isFile) { "Recovery file does not exist" }
        val canonical = source.canonicalFile
        val root = recoveryDirectory().canonicalFile
        require(canonical.parentFile == root) { "Recovery file must be in the protected recovery directory" }
        require(!java.nio.file.Files.isSymbolicLink(source.toPath())) { "Symbolic links are not accepted" }
        return canonical
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        require(bytes.size <= MAX_ARCHIVE_BYTES) { "Recovery file is too large" }
        val temp = File(target.parentFile, ".${target.name}.tmp")
        try {
            temp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temp.renameTo(target)) throw IOException("Could not finalize recovery file")
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun randomSuffix(): String = ByteArray(8).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    data class ImportSummary(val memoCount: Int, val pendingOpCount: Int)

    private data class RecoveryPayload(
        val accountId: String,
        val exportedAt: Long,
        val memos: List<CachedMemo>,
        val pendingOps: List<RecoveryPendingOp>
    )

    private data class RecoveryPendingOp(
        val id: String,
        val accountId: String,
        val type: String,
        val memoName: String?,
        val parentName: String?,
        val payloadJson: String?,
        val updateMask: String?,
        val baseUpdateTime: String?,
        val createdAt: Long,
        val attemptCount: Int,
        val lastError: String?,
        val lastAttemptAt: Long,
        val permanentlyFailed: Boolean
    ) {
        fun toPendingOp() = PendingOp(id, accountId, type, memoName, parentName, payloadJson, updateMask, baseUpdateTime, createdAt, attemptCount, lastError, lastAttemptAt, permanentlyFailed)
        companion object { fun from(op: PendingOp) = RecoveryPendingOp(op.id, op.accountId, op.type, op.memoName, op.parentName, op.payloadJson, op.updateMask, op.baseUpdateTime, op.createdAt, op.attemptCount, op.lastError, op.lastAttemptAt, op.permanentlyFailed) }
    }

    companion object {
        private const val FORMAT = "memosm-local-recovery"
        private const val VERSION = 1
        private const val MAX_ARCHIVE_BYTES = 25 * 1024 * 1024
        private const val MAX_ROWS = 50_000
        private const val RECOVERY_DIRECTORY = "recovery"

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
