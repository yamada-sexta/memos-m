package org.example.memosm.viewmodel.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.example.memosm.MemosApplication
import org.example.memosm.R
import org.example.memosm.api.MemosApi
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.model.Attachment

private const val ATTACHMENT_PAGE_SIZE = 20

/**
 * Threshold for using streaming upload (2MB).
 * Files larger than this will use streaming to avoid OOM.
 */
private const val STREAMING_THRESHOLD = 2 * 1024 * 1024L
private const val TAG = "AttachmentManager"

class AttachmentManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val streamingApiProvider: () -> StreamingAttachmentApi?,
    initialCellWidth: Float = 120f,
    private val uploadQueueProvider: () -> AttachmentUploadQueue? = { null },
    private val accountIdProvider: () -> String? = { null },
    private val draftReferenceChecker: suspend (clientId: String) -> Boolean = { false },
    private val outboxReferenceChecker: suspend (clientId: String) -> Boolean = { false },
    cacheCallbacks: CacheCallbacks<Attachment>? = null
) : BaseListManager<Attachment>(scope, cacheCallbacks = cacheCallbacks, nameProvider = { it.name }) {


    private val _cellWidth = MutableStateFlow(initialCellWidth)
    val cellWidth = _cellWidth.asStateFlow()

    fun updateCellWidth(width: Float) {
        _cellWidth.value = width
    }

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Attachment>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        return withContext(Dispatchers.IO) {
            Log.d("AttachmentManager", "fetchFromApi: pageToken=$pageToken")
            val response =
                api.listAttachments(pageSize = ATTACHMENT_PAGE_SIZE, pageToken = pageToken)
            Log.d(
                "AttachmentManager",
                "fetchFromApi: got ${response.attachments?.size ?: 0} attachments, nextToken=${response.nextPageToken}"
            )
            Pair(response.attachments ?: emptyList(), response.nextPageToken)
        }
    }

    /**
     * Upload an attachment from a Uri.
     * Uses streaming upload for files > 2MB to prevent OOM.
     *
     * When the upload cannot complete (offline, or the request threw), the
     * bytes are staged app-private and the upload is queued for durable retry;
     * the user is told it was saved offline rather than left with a silent
     * failure. Returns the uploaded [Attachment], a placeholder [Attachment]
     * (no server `name`, carrying the queue clientId + staged localPath) when
     * the upload was queued, or null when even staging failed.
     */
    suspend fun uploadAttachment(uri: Uri, context: Context): Attachment? {
        val api = apiProvider()
        val streamingApi = streamingApiProvider()
        try {
            Log.d(TAG, "uploadAttachment: starting upload for uri=$uri")

            val mimeType = resolveMimeType(uri, context)
            val fileName = getFileName(uri, context) ?: "unknown_file"

            // Get file size to determine upload method
            val fileSize = getFileSize(uri, context)
            Log.d(
                TAG,
                "uploadAttachment: fileName=$fileName, mimeType=$mimeType, fileSize=$fileSize bytes (threshold=$STREAMING_THRESHOLD)"
            )

            // Offline (or no API yet): queue for later instead of failing.
            if (api == null) {
                return enqueueForLater(uri, fileName, mimeType, fileSize)
            }

            val useStreaming = fileSize > STREAMING_THRESHOLD && streamingApi != null
            Log.d(
                TAG,
                "uploadAttachment: useStreaming=$useStreaming (hasStreamingApi=${streamingApi != null})"
            )

            val attachment = if (useStreaming) {
                // Use streaming upload for large files
                Log.d(TAG, "uploadAttachment: using STREAMING upload for large file")
                streamingApi.createAttachmentStreaming(fileName, mimeType, uri, context)
            } else {
                // Use regular upload for small files
                Log.d(TAG, "uploadAttachment: using REGULAR upload")
                uploadAttachmentRegular(api, uri, context, fileName, mimeType)
            }

            if (attachment != null) {
                Log.d(TAG, "uploadAttachment: SUCCESS, id=${attachment.name}")
                // Prepend to list locally
                updateState { state ->
                    state.copy(items = listOf(attachment) + state.items)
                }
            } else {
                Log.e(
                    TAG, "uploadAttachment: FAILED - returned null (used streaming=$useStreaming)"
                )
                return enqueueForLater(uri, fileName, mimeType, fileSize)
            }

            return attachment
        } catch (e: CancellationException) {
            // Cancelled uploads must not be queued for retry: the caller
            // abandoned them, so skip the durable side effect below.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed with exception", e)
            return enqueueForLater(uri, getFileName(uri, context) ?: "unknown_file",
                resolveMimeType(uri, context), getFileSize(uri, context))
        }
    }

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
        uri: Uri,
        fileName: String,
        mimeType: String,
        fileSize: Long
    ): Attachment? {
        val queue = uploadQueueProvider() ?: return null
        val accountId = accountIdProvider() ?: return null
        val clientId = queue.enqueue(accountId, uri, fileName, mimeType, fileSize) ?: return null
        withContext(Dispatchers.Main) {
            Toast.makeText(
                MemosApplication.instance,
                MemosApplication.instance.getString(R.string.offline_saved_message),
                Toast.LENGTH_SHORT
            ).show()
        }
        return Attachment(
            filename = fileName,
            type = mimeType,
            mimeType = mimeType,
            size = fileSize.takeIf { it > 0 }?.toString(),
            clientId = clientId,
            localPath = queue.stagedFile(clientId).absolutePath
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
        val rowExists = queue.get(clientId) != null
        val referencedElsewhere = try {
            draftReferenceChecker(clientId) || outboxReferenceChecker(clientId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "discardQueuedUploadIfOrphaned: reference check failed, keeping upload", e)
            true
        }
        if (!shouldDiscardQueuedUpload(rowExists, referencedElsewhere)) return
        queue.discard(clientId)
    }

    private fun resolveMimeType(uri: Uri, context: Context): String {
        val resolverMimeType = context.contentResolver.getType(uri)
        return if (resolverMimeType == null || resolverMimeType == "application/octet-stream") {
            val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: resolverMimeType
            ?: "application/octet-stream"
        } else {
            resolverMimeType
        }
    }

    /**
     * Regular upload that loads entire file into memory.
     * Only used for small files (< 2MB).
     */
    private suspend fun uploadAttachmentRegular(
        api: MemosApi, uri: Uri, context: Context, fileName: String, mimeType: String
    ): Attachment? {
        val base64Content = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val bytes = inputStream.readBytes()
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } ?: return null

        val attachmentToCreate = Attachment(
            filename = fileName, type = mimeType, content = base64Content
        )

        Log.d(
            "AttachmentManager", "uploadAttachment: sending createAttachment request for $fileName"
        )
        return api.createAttachment(attachmentToCreate)
    }

    private fun getFileName(uri: Uri, context: Context): String? {
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

    private fun getFileSize(uri: Uri, context: Context): Long {
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
        internal fun shouldDiscardQueuedUpload(rowExists: Boolean, referencedElsewhere: Boolean) =
            rowExists && !referencedElsewhere

        fun resolveResourceUrl(hostUrl: String, relativeUrl: String?): String? {
            if (relativeUrl.isNullOrBlank()) return null
            if (relativeUrl.startsWith("http")) return relativeUrl

            val cleanHost = hostUrl.trimEnd('/')
            val cleanRelative = relativeUrl.trimStart('/')

            return "$cleanHost/$cleanRelative"
        }

        fun getAttachmentUrl(hostUrl: String, attachment: Attachment?): String? {
            if (attachment == null) return null

            return if (!attachment.externalLink.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, attachment.externalLink)
            } else if (!attachment.name.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, "file/${attachment.name}/${attachment.filename}")
            } else {
                // Queued-upload placeholder: render the staged local copy.
                attachment.localPath?.takeIf { it.isNotBlank() }
                    ?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }
            }
        }

        /**
         * Returns the server-generated thumbnail URL used for image previews.
         *
         * Regular external links are deliberately not used here when the attachment has a name:
         * the Memos server owns thumbnail generation and serves it from the attachment file route.
         * Share links are the exception because their token must remain in the URL.
         */
        fun getAttachmentThumbnailUrl(hostUrl: String, attachment: Attachment?): String? {
            if (attachment == null) return null

            val externalLink = attachment.externalLink
            if (!externalLink.isNullOrBlank() && hasQueryParameter(externalLink, "share_token")) {
                return setQueryParameter(
                    resolveResourceUrl(hostUrl, externalLink) ?: return null,
                    "thumbnail",
                    "true"
                )
            }

            if (!attachment.name.isNullOrBlank()) {
                val attachmentUrl = resolveResourceUrl(
                    hostUrl, "file/${attachment.name}/${attachment.filename}"
                ) ?: return null
                return setQueryParameter(attachmentUrl, "thumbnail", "true")
            }

            // Unnamed attachments cannot be addressed through the server file route.
            return resolveResourceUrl(hostUrl, externalLink)
        }

        private fun hasQueryParameter(url: String, key: String): Boolean {
            val query = url.substringBefore('#').substringAfter('?', missingDelimiterValue = "")
            return query.split('&').any { parameter ->
                parameter.substringBefore('=') == key
            }
        }

        private fun setQueryParameter(url: String, key: String, value: String): String {
            val fragment = url.substringAfter('#', missingDelimiterValue = "")
            val withoutFragment = url.substringBefore('#')
            val base = withoutFragment.substringBefore('?')
            val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
            var replaced = false
            val parameters = query.split('&').filter { it.isNotEmpty() }.map { parameter ->
                if (parameter.substringBefore('=') == key) {
                    replaced = true
                    "$key=$value"
                } else {
                    parameter
                }
            }.toMutableList()

            if (!replaced) parameters += "$key=$value"

            return buildString {
                append(base)
                append('?')
                append(parameters.joinToString("&"))
                if (fragment.isNotEmpty()) {
                    append('#')
                    append(fragment)
                }
            }
        }
    }
}
