package org.example.memosm.viewmodel.manager

import org.example.memosm.data.media.AttachmentUpload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.example.memosm.api.resolveResourceUrl
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession

private const val TAG = "AttachmentManager"

class AttachmentManager(
    private val accountSession: AccountSession,
    private val uploadQueueProvider: () -> AttachmentUploadQueue? = { null },
    private val draftReferenceChecker: suspend (clientId: String) -> Boolean = { false },
    private val outboxReferenceChecker: suspend (clientId: String) -> Boolean = { false },
) {
    /** Every upload is staged before publishing, regardless of connectivity. */
    suspend fun uploadAttachment(
        uri: Uri,
        context: Context,
    ): Attachment? {
        val account = accountSession.current ?: return null
        return enqueueForLater(
            account,
            uri,
            getFileName(uri, context) ?: "unknown_file",
            resolveMimeType(uri, context),
            getFileSize(uri, context),
        )
    }

    suspend fun <T> withPreparedAttachments(
        account: AccountContext,
        attachments: List<Attachment>,
        commit: suspend (List<Attachment>, List<AttachmentUpload>) -> T,
    ): T =
        requireNotNull(uploadQueueProvider()) { "Uploads are unavailable" }
            .withPreparedAttachments(account.account.id, attachments, commit)

    /**
     * Stage the bytes and persist a durable upload row so the attachment is
     * retried after connectivity returns (even across process death). Tells the
     * user it was saved offline and returns a placeholder [Attachment] that
     * keeps the file linked to the memo being composed: no server `name` yet,
     * but the queue clientId (which the outbox replay resolves to the real
     * attachment once the upload lands) plus the staged localPath for local
     * preview. A staging failure leaves the upload genuinely failed (no toast,
     * no row, null).
     */
    private suspend fun enqueueForLater(
        account: AccountContext,
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long,
    ): Attachment? {
        val queue = uploadQueueProvider() ?: return null
        val accountId = account.account.id
        val clientId = queue.enqueue(accountId, uri, fileName, mimeType, fileSize) ?: return null
        return Attachment(
            filename = fileName,
            type = mimeType,
            mimeType = mimeType,
            size = fileSize.takeIf { it > 0 }?.toString(),
            clientId = clientId,
            localPath = queue.stagedFile(clientId).absolutePath,
        )
    }

    /**
     * Discard the queued upload behind [clientId] when its placeholder was
     * removed from the composer and nothing else still references it: neither
     * a persisted draft nor a queued outbox op payload. Keeping the upload on
     * any doubt is deliberate - a server-side orphan beats losing bytes a
     * draft or pending memo still points at. No-op when the row is already
     * gone (uploaded or discarded).
     */
    suspend fun discardQueuedUploadIfOrphaned(clientId: String) {
        val queue = uploadQueueProvider() ?: return
        val account = accountSession.current ?: return
        val row = queue.get(clientId) ?: return
        if (row.accountId != account.account.id) return
        val rowExists = true
        val referencedElsewhere =
            try {
                draftReferenceChecker(clientId) || outboxReferenceChecker(clientId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "discardQueuedUploadIfOrphaned: reference check failed, keeping upload", e)
                true
            }
        if (!accountSession.isCurrent(account) || !shouldDiscardQueuedUpload(rowExists, referencedElsewhere)) return
        queue.discard(clientId)
    }

    private fun resolveMimeType(
        uri: Uri,
        context: Context,
    ): String {
        val resolverMimeType = context.contentResolver.getType(uri)
        return if (resolverMimeType == null || resolverMimeType == "application/octet-stream") {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: resolverMimeType
                ?: "application/octet-stream"
        } else {
            resolverMimeType
        }
    }

    private fun getFileName(
        uri: Uri,
        context: Context,
    ): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) name = it.getString(index)
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                name = name.substring(cut + 1)
            }
        }
        return name
    }

    private fun getFileSize(
        uri: Uri,
        context: Context,
    ): Long {
        var size = 0L
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.SIZE)
                    if (index != -1) {
                        size = it.getLong(index)
                    }
                }
            }
        }
        // If we couldn't get size from cursor, try to read it
        if (size == 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    size = inputStream.available().toLong()
                }
            } catch (e: Exception) {
                Log.w("AttachmentManager", "Could not determine file size", e)
            }
        }
        return size
    }

    companion object {
        /**
         * Discard decision for a queued upload whose placeholder was removed:
         * only when the durable row still exists (something to cancel) AND
         * nothing else references the clientId. Pure seam for unit tests.
         */
        internal fun shouldDiscardQueuedUpload(
            rowExists: Boolean,
            referencedElsewhere: Boolean,
        ) = rowExists && !referencedElsewhere

        fun getAttachmentUrl(
            hostUrl: String,
            attachment: Attachment?,
        ): String? {
            if (attachment == null) return null

            return if (!attachment.externalLink.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, attachment.externalLink)
            } else if (!attachment.name.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, "file/${attachment.name}/${attachment.filename}")
            } else {
                // Queued-upload placeholder: render the staged local copy.
                attachment.localPath
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        android.net.Uri
                            .fromFile(java.io.File(it))
                            .toString()
                    }
            }
        }

        /**
         * Returns the server-generated thumbnail URL used for image previews.
         *
         * Regular external links are deliberately not used here when the attachment has a name:
         * the Memos server owns thumbnail generation and serves it from the attachment file route.
         * Share links are the exception because their token must remain in the URL.
         */
        fun getAttachmentThumbnailUrl(
            hostUrl: String,
            attachment: Attachment?,
        ): String? {
            if (attachment == null) return null

            val externalLink = resolveResourceUrl(hostUrl, attachment.externalLink)
            if (externalLink?.toHttpUrlOrNull()?.queryParameter("share_token") != null) {
                return setQueryParameter(
                    externalLink,
                    "thumbnail",
                    "true",
                )
            }

            if (!attachment.name.isNullOrBlank()) {
                val attachmentUrl =
                    resolveResourceUrl(
                        hostUrl,
                        "file/${attachment.name}/${attachment.filename}",
                    ) ?: return null
                return setQueryParameter(attachmentUrl, "thumbnail", "true")
            }

            // Unnamed attachments cannot be addressed through the server file route.
            return externalLink
        }

        private fun setQueryParameter(
            url: String,
            key: String,
            value: String,
        ): String =
            url
                .toHttpUrlOrNull()
                ?.newBuilder()
                ?.setQueryParameter(key, value)
                ?.build()
                ?.toString() ?: url
    }
}
