package org.example.memosm.data.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.api.MemosApi
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.model.Attachment
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Threshold for streaming upload (2MB), mirrored from AttachmentManager. */
private const val STREAMING_THRESHOLD = 2 * 1024 * 1024L
private const val TAG = "AttachmentUploadQueue"

/**
 * Attempt ceiling after which a queued upload is retired as permanently
 * failed, even without a definitive server rejection: one poisoned row must
 * not retry (and head-of-line block the queue) forever.
 */
private const val MAX_ATTEMPTS = 10

/**
 * A 4xx response (except 408/429) is a permanent rejection: retrying the
 * same upload cannot succeed. Shared between the queue replay and the
 * worker so the classification never diverges.
 */
internal fun isPermanentUploadError(error: Exception): Boolean =
    error is HttpException && error.code() in 400..499 && error.code() !in setOf(408, 429)

/**
 * Durable offline queue for attachment uploads.
 *
 * An upload that cannot complete right now (offline, or the request threw) is
 * persisted to the `attachment_uploads` table together with an app-private copy
 * of the bytes (content:// URIs do not survive process death). Each row carries
 * a stable [AttachmentUpload.clientId] that is forwarded as the API
 * `attachmentId` so a retried upload reconciles server-side instead of
 * duplicating the attachment.
 *
 * Replay honours the same size split as the live path: small files go through
 * the regular base64 endpoint, files larger than [STREAMING_THRESHOLD] stream.
 */
class AttachmentUploadQueue(
    private val context: Context,
    private val dao: AttachmentUploadDao,
    private val auditLogger: SyncAuditLogger
) {
    private val uploadDir: File
        get() = File(context.noBackupFilesDir, "attachment_uploads")

    /**
     * Copy [uri] into app-private storage and enqueue a durable upload row.
     * Returns the stable clientId, or null when the bytes could not be read.
     */
    suspend fun enqueue(
        accountId: String,
        uri: Uri,
        filename: String,
        mimeType: String,
        size: Long
    ): String? = withContext(Dispatchers.IO) {
        val clientId = UUID.randomUUID().toString()
        val localFile = try {
            copyToLocal(clientId, uri)
        } catch (e: Exception) {
            Log.e(TAG, "enqueue: failed to stage bytes for $filename", e)
            return@withContext null
        }
        val resolvedSize = if (size > 0) size else localFile.length()
        val row = AttachmentUpload(
            id = clientId,
            accountId = accountId,
            clientId = clientId,
            localPath = localFile.absolutePath,
            filename = filename,
            mimeType = mimeType,
            size = resolvedSize,
            createdAt = System.currentTimeMillis()
        )
        dao.upsert(row)
        schedule(accountId)
        Log.d(TAG, "enqueue: queued $filename as $clientId ($resolvedSize bytes)")
        clientId
    }

    /** Enqueue one durable, network-constrained background replay request. */
    fun schedule(accountId: String) {
        if (accountId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<AttachmentUploadWorker>()
            .setInputData(workDataOf(AttachmentUploadWorker.ACCOUNT_ID to accountId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(workName(accountId))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(accountId), ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(accountId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(accountId))
    }

    /**
     * Drop every queued upload of a removed account: cancels its scheduled
     * replay, deletes the staged byte copies and clears the durable rows.
     */
    suspend fun clearForAccount(accountId: String) = withContext(Dispatchers.IO) {
        cancel(accountId)
        dao.getForAccount(accountId).forEach { File(it.localPath).delete() }
        dao.deleteForAccount(accountId)
    }

    /**
     * Drop a single queued upload: delete its staged byte copy and the durable
     * row. No-op when the row is already gone (uploaded, retired, or discarded
     * before). A replay pass that already read the row re-loads it via
     * `dao.getById` right before uploading and skips it when vanished, so a
     * discard landing mid-replay is safe.
     */
    suspend fun discard(clientId: String) = withContext(Dispatchers.IO) {
        val row = dao.getById(clientId) ?: return@withContext
        File(row.localPath).delete()
        dao.delete(row.id)
        Log.d(TAG, "discard: dropped queued upload $clientId")
    }

    /** True while at least one durable upload row is waiting for [accountId]. */
    suspend fun hasPending(accountId: String): Boolean = withContext(Dispatchers.IO) {
        dao.getForAccount(accountId).isNotEmpty()
    }

    /** The durable row for [clientId], or null once the upload finished (or was retired). */
    suspend fun get(clientId: String): AttachmentUpload? = withContext(Dispatchers.IO) {
        dao.getById(clientId)
    }

    /** App-private staged copy of the bytes queued under [clientId] (for local preview). */
    fun stagedFile(clientId: String): File = File(uploadDir, clientId)

    /**
     * Replay every queued upload for [accountId] in creation order. Successful
     * rows are deleted (and their staged file removed) and audited. A failing
     * row never blocks the rest of the pass: permanent rejections
     * ([isPermanentUploadError]) and rows that hit [MAX_ATTEMPTS] are dropped
     * right away, while transient failures are recorded per row and rethrown
     * after the pass so the caller schedules a backoff retry for them.
     */
    suspend fun replay(
        accountId: String,
        api: MemosApi,
        streamingApi: StreamingAttachmentApi?
    ): Unit = withContext(Dispatchers.IO) {
        var firstTransientError: Exception? = null
        val rows = dao.getForAccount(accountId)
        for (row in rows) {
            val current = dao.getById(row.id) ?: continue
            val file = File(current.localPath)
            if (!file.exists()) {
                // The staged bytes are gone; nothing can ever succeed. Drop it.
                dao.delete(current.id)
                recordAudit(accountId, "REJECTED", current.clientId, "missing_local_file")
                continue
            }
            if (current.attemptCount >= MAX_ATTEMPTS) {
                // Retried too many times without a definitive server
                // rejection: retire as permanently failed so the row stops
                // consuming retries and blocking the rows behind it.
                dao.delete(current.id)
                file.delete()
                recordAudit(accountId, "REJECTED", current.clientId, "max_attempts")
                Log.w(
                    TAG,
                    "replay: dropped ${current.filename} after ${current.attemptCount} attempts"
                )
                continue
            }
            try {
                val attachment = upload(current, file, api, streamingApi)
                    ?: throw IOException("createAttachment returned null")
                dao.delete(current.id)
                file.delete()
                recordAudit(accountId, "SUCCESS", current.clientId, null)
                Log.d(TAG, "replay: uploaded ${current.filename} -> ${attachment.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dao.markFailed(
                    current.id,
                    current.attemptCount + 1,
                    e.javaClass.simpleName,
                    System.currentTimeMillis()
                )
                if (isPermanentUploadError(e)) {
                    dao.delete(current.id)
                    file.delete()
                    recordAudit(accountId, "REJECTED", current.clientId, errorCode(e))
                } else {
                    // Continue with the next row instead of aborting the
                    // pass; the rethrow after the loop lets the caller retry
                    // the remaining failures with backoff.
                    recordAudit(accountId, "RETRY", current.clientId, errorCode(e))
                    if (firstTransientError == null) firstTransientError = e
                }
            }
        }
        firstTransientError?.let { throw it }
    }

    private suspend fun upload(
        row: AttachmentUpload,
        file: File,
        api: MemosApi,
        streamingApi: StreamingAttachmentApi?
    ): Attachment? {
        return if (row.size > STREAMING_THRESHOLD && streamingApi != null) {
            streamingApi.createAttachmentFromFile(file, row.mimeType, row.clientId)
        } else {
            val base64 = file.inputStream().use { input ->
                android.util.Base64.encodeToString(input.readBytes(), android.util.Base64.NO_WRAP)
            }
            api.createAttachment(
                Attachment(filename = row.filename, type = row.mimeType, content = base64),
                attachmentId = row.clientId
            )
        }
    }

    private fun copyToLocal(clientId: String, uri: Uri): File {
        uploadDir.mkdirs()
        val target = File(uploadDir, clientId)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("Cannot open input stream for $uri")
        return target
    }

    private suspend fun recordAudit(
        accountId: String,
        outcome: String,
        clientId: String,
        detailCode: String?
    ) = auditLogger.record(
        accountId = accountId,
        event = "ATTACHMENT_UPLOAD",
        outcome = outcome,
        operation = "CREATE",
        target = clientId,
        detailCode = detailCode
    )

    private fun errorCode(error: Exception): String = when (error) {
        is HttpException -> "http_${error.code()}"
        else -> error.javaClass.simpleName.take(64)
    }

    companion object {
        fun workName(accountId: String) = "memosm-attachment-upload-$accountId"
    }
}
