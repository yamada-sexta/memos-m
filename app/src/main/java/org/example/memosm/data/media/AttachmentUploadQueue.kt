package org.example.memosm.data.media

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.sync.WorkManagerSyncWorkScheduler
import org.example.memosm.data.sync.isRejectedMutation
import org.example.memosm.model.Attachment
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

private const val STREAMING_THRESHOLD = 2 * 1024 * 1024L

/** Staged bytes and actual upload results. The account's outbox worker owns all network replay. */
class AttachmentUploadQueue(
    private val context: Context,
    private val dao: AttachmentUploadDao,
    private val auditLogger: SyncAuditLogger,
    private val accountExists: suspend (String) -> Boolean = { true },
) {
    private val uploadDir get() = File(context.noBackupFilesDir, "attachment_uploads")

    suspend fun enqueue(
        accountId: String,
        uri: Uri,
        filename: String,
        mimeType: String,
        size: Long,
    ): String? = stage(accountId, filename, mimeType, size) { context.contentResolver.openInputStream(uri) }

    /** New upload rows and the memo commit share one transaction; preparation cannot start replay. */
    suspend fun <T> withPreparedAttachments(
        accountId: String,
        attachments: List<Attachment>,
        commit: suspend (List<Attachment>, List<AttachmentUpload>) -> T,
    ): T =
        withContext(Dispatchers.IO) {
            val staged = mutableListOf<AttachmentUpload>()
            try {
                val prepared =
                    attachments.map { attachment ->
                        if (!attachment.name.isNullOrBlank()) return@map attachment
                        attachment.clientId?.let { id ->
                            require(
                                dao.getById(id)?.accountId == accountId,
                            ) { "Staged attachment is missing or belongs to another account" }
                            return@map attachment
                        }
                        val mimeType = attachment.mimeType ?: attachment.type
                        val id =
                            if (attachment.content != null) {
                                stage(accountId, attachment.filename, mimeType, 0, staged) {
                                    android.util.Base64InputStream(
                                        java.io.ByteArrayInputStream(attachment.content.toByteArray(Charsets.US_ASCII)),
                                        android.util.Base64.DEFAULT,
                                    )
                                }
                            } else {
                                val path = requireNotNull(attachment.localPath) { "Attachment has no source" }
                                val file = File(path).canonicalFile
                                val root = File(context.filesDir, "restored_backup_files").canonicalFile
                                require(file.isFile && file.path.startsWith(root.path + File.separator)) { "Draft attachment is missing" }
                                stage(accountId, attachment.filename, mimeType, file.length(), staged) { file.inputStream() }
                            }
                        requireNotNull(id) { "Could not save attachment" }
                        attachment.copy(clientId = id, localPath = stagedFile(id).absolutePath, content = null)
                    }
                commit(prepared, staged)
            } finally {
                // A completed commit owns its files. Failed/cancelled preparation owns none.
                withContext(NonCancellable) {
                    try {
                        BackupCoordinator.withStorageLock {
                            staged.forEach { if (dao.getById(it.id) == null) File(it.localPath).delete() }
                        }
                    } catch (error: Exception) {
                        android.util.Log.w("AttachmentUploads", "Could not clean abandoned preparation", error)
                    }
                }
            }
        }

    private suspend fun stage(
        accountId: String,
        filename: String,
        mimeType: String,
        size: Long,
        staged: MutableList<AttachmentUpload>? = null,
        openSource: () -> java.io.InputStream?,
    ): String? =
        withContext(Dispatchers.IO) {
            val generation = BackupCoordinator.generation
            val id = UUID.randomUUID().toString()
            check(uploadDir.isDirectory || uploadDir.mkdirs()) { "Could not create upload directory" }
            val file = stagedFile(id)
            var committed = false
            try {
                openSource()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                        output.fd.sync()
                    }
                } ?: return@withContext null
                val row =
                    AttachmentUpload(
                        id,
                        accountId,
                        id,
                        file.absolutePath,
                        filename,
                        mimeType,
                        if (size > 0) size else file.length(),
                        System.currentTimeMillis(),
                    )
                if (staged != null) {
                    staged += row
                    committed = true // The enclosing preparation now owns cleanup.
                } else {
                    BackupCoordinator.withStorageLock {
                        check(BackupCoordinator.generation == generation && !BackupCoordinator.restoring.value) { "Storage was restored" }
                        check(accountExists(accountId)) { "Account was removed" }
                        withContext(NonCancellable) {
                            dao.upsert(row)
                            committed = true
                        }
                    }
                    try {
                        schedule(accountId)
                    } catch (
                        error: Exception,
                    ) {
                        android.util.Log.w("AttachmentUploads", "Could not schedule saved upload", error)
                    }
                }
                id
            } finally {
                if (!committed) file.delete()
            }
        }

    /** Only prior-process, unowned UUID files are eligible; active staging remains untouched. */
    suspend fun cleanupOrphanedFiles(createdBefore: Long) =
        withContext(Dispatchers.IO) {
            BackupCoordinator.withStorageLock {
                val owned = dao.getAll().map { File(it.localPath).canonicalPath }.toSet()
                uploadDir.listFiles().orEmpty().forEach { file ->
                    if (file.isFile && file.lastModified() < createdBefore &&
                        runCatching { UUID.fromString(file.name).toString() == file.name }.getOrDefault(false) &&
                        file.canonicalPath !in owned
                    ) {
                        file.delete()
                    }
                }
            }
        }

    fun schedule(accountId: String) = WorkManagerSyncWorkScheduler(context).schedule(accountId)

    fun cancel(accountId: String) = WorkManagerSyncWorkScheduler(context).cancel(accountId)

    suspend fun get(clientId: String): AttachmentUpload? = dao.getById(clientId)

    fun stagedFile(clientId: String) = File(uploadDir, clientId)

    suspend fun hasPending(accountId: String) = dao.getForAccount(accountId).any { it.serverJson == null }

    suspend fun retryAuthentication(accountId: String) = BackupCoordinator.withStorageLock { dao.retryAuthentication(accountId) }

    suspend fun retryAll(accountId: String) = BackupCoordinator.withStorageLock { dao.retryAll(accountId) }

    suspend fun resolved(clientId: String): Attachment? =
        dao.getById(clientId)?.serverJson?.let {
            GsonProvider.gson.fromJson(it, Attachment::class.java)
        }

    suspend fun clearForAccount(accountId: String) =
        withContext(Dispatchers.IO) {
            cancel(accountId)
            BackupCoordinator.withStorageLock {
                dao.getForAccount(accountId).forEach { File(it.localPath).delete() }
                dao.deleteForAccount(accountId)
            }
        }

    /** Caller must establish that no draft or mutation references this attachment. */
    suspend fun discard(clientId: String) =
        withContext(Dispatchers.IO) {
            BackupCoordinator.withStorageLock {
                val row = dao.getById(clientId) ?: return@withStorageLock
                dao.delete(row.id)
                File(row.localPath).delete()
            }
        }

    /** Reclaim prior-process results; current composers may own newer rows without a persisted reference. */
    suspend fun cleanupCompletedUploads(
        accountId: String,
        drafts: DraftManager,
        settings: DataStoreManager,
        cache: MemoCacheRepository,
        outbox: org.example.memosm.data.sync.SyncRepository,
        createdBefore: Long,
    ) = withContext(Dispatchers.IO) {
        BackupCoordinator.withStorageLock {
            // Strict reads fail closed: uncertain ownership must preserve the bytes and mapping.
            val draftAttachments = drafts.getDrafts(accountId, strict = true).flatMap { it.attachments.orEmpty() }
            val pending = outbox.getOps(accountId)
            val restored = settings.restoredEdits(accountId).first().toString()
            for (row in dao.getForAccount(accountId)) {
                if (row.serverJson == null || row.createdAt >= createdBefore) continue
                if (draftAttachments.any { it.clientId == row.clientId || it.localPath == row.localPath } ||
                    pending.any { it.payloadJson?.contains(row.clientId) == true || it.payloadJson?.contains(row.localPath) == true } ||
                    restored.contains(row.clientId) || restored.contains(row.localPath) ||
                    cache.referencesAttachment(accountId, row.clientId, row.localPath)
                ) {
                    continue
                }
                val file = File(row.localPath).canonicalFile
                if (file.parentFile != uploadDir.canonicalFile) continue
                if (!file.exists() || file.delete()) dao.delete(row.id)
            }
        }
    }

    /** Returns whether runnable transient failures remain; permanent failures preserve both row and bytes. */
    suspend fun replay(
        accountId: String,
        api: MemosApi,
        streamingApi: StreamingAttachmentApi?,
        isCurrent: suspend () -> Boolean = { true },
    ): Boolean =
        withContext(Dispatchers.IO) {
            var retry = false
            for (row in dao.getForAccount(accountId)) {
                if (!isCurrent()) break
                if (row.serverJson != null || row.permanentlyFailed || row.authRequired) continue
                try {
                    val file = File(row.localPath)
                    require(file.isFile) { "Staged attachment is missing" }
                    val uploaded =
                        try {
                            if (row.size > STREAMING_THRESHOLD && streamingApi != null) {
                                streamingApi.createAttachmentFromFile(file, row.mimeType, row.clientId, filename = row.filename)
                            } else {
                                val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                                api.createAttachment(
                                    Attachment(filename = row.filename, type = row.mimeType, content = encoded),
                                    row.clientId,
                                )
                            }
                        } catch (error: HttpException) {
                            if (error.code() != 409) throw error
                            api.getAttachment("attachments/${row.clientId}")
                        }
                    if (uploaded?.name.isNullOrBlank()) throw IOException("Server returned no attachment identity")
                    BackupCoordinator.withStorageLock {
                        if (isCurrent() && dao.getById(row.id) != null) {
                            dao.complete(row.id, GsonProvider.gson.toJson(uploaded))
                            // Keep bytes for drafts and pending memo previews until their references are released.
                        }
                    }
                    auditLogger.record(accountId, "ATTACHMENT_UPLOAD", "SUCCESS", "CREATE", row.clientId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!isCurrent()) break
                    val auth = error is HttpException && error.code() in setOf(401, 403)
                    val rejected = isRejectedMutation(error)
                    BackupCoordinator.withStorageLock {
                        if (isCurrent() && dao.getById(row.id) != null) {
                            dao.markFailed(row.id, row.attemptCount + 1, error.message, System.currentTimeMillis())
                            dao.setFailure(row.id, rejected, auth)
                        }
                    }
                    retry = retry || (!rejected && !auth)
                    auditLogger.record(
                        accountId,
                        "ATTACHMENT_UPLOAD",
                        if (auth) {
                            "AUTH_REQUIRED"
                        } else if (rejected) {
                            "REJECTED"
                        } else {
                            "RETRY"
                        },
                        "CREATE",
                        row.clientId,
                        if (error is HttpException) "http_${error.code()}" else error.javaClass.simpleName,
                    )
                }
            }
            retry
        }

    companion object {
        // Retained only as the durable work-name identifier used by old cancellation/backup callers.
        fun workName(accountId: String) = WorkManagerSyncWorkScheduler.workName(accountId)
    }
}
