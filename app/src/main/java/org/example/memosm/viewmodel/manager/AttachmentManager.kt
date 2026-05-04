package org.example.memosm.viewmodel.manager

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.example.memosm.api.MemosApi
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.model.Attachment

private const val ATTACHMENT_PAGE_SIZE = 20

/**
 * Threshold for using streaming upload (2MB).
 * Files larger than this will use streaming to avoid OOM.
 */
private const val STREAMING_THRESHOLD = 2 * 1024 * 1024L
private const val TAG = "AttachmentManager"

class AttachmentManager(
    private val scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val streamingApiProvider: () -> StreamingAttachmentApi?,
    cacheCallbacks: CacheCallbacks<Attachment>? = null,
    initialCellWidth: Float = 120f
) : BaseListManager<Attachment>(scope, cacheCallbacks = cacheCallbacks) {


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
     */
    suspend fun uploadAttachment(uri: Uri, context: Context): Attachment? {
        val api = apiProvider() ?: return null
        val streamingApi = streamingApiProvider()
        try {
            Log.d(TAG, "uploadAttachment: starting upload for uri=$uri")

            val contentResolver = context.contentResolver
            val resolverMimeType = contentResolver.getType(uri)
            val mimeType =
                if (resolverMimeType == null || resolverMimeType == "application/octet-stream") {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: resolverMimeType
                    ?: "application/octet-stream"
                } else {
                    resolverMimeType
                }
            val fileName = getFileName(uri, context) ?: "unknown_file"

            // Get file size to determine upload method
            val fileSize = getFileSize(uri, context)
            Log.d(
                TAG,
                "uploadAttachment: fileName=$fileName, mimeType=$mimeType, fileSize=$fileSize bytes (threshold=$STREAMING_THRESHOLD)"
            )

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
            }

            return attachment
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed with exception", e)
            return null
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
        fun resolveResourceUrl(hostUrl: String, relativeUrl: String?): String? {
            if (relativeUrl.isNullOrBlank()) return null
            if (relativeUrl.startsWith("http")) return relativeUrl

            val cleanHost = hostUrl.trimEnd('/')
            val cleanRelative = relativeUrl.trimStart('/')

            val result = "$cleanHost/$cleanRelative"
            Log.d(
                "MemosDebug",
                "AttachmentManager.resolve: host=$hostUrl, relative=$relativeUrl -> $result"
            )
            return result
        }

        fun getAttachmentUrl(hostUrl: String, attachment: Attachment?): String? {
            if (attachment == null) return null

            val url = if (!attachment.externalLink.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, attachment.externalLink)
            } else if (!attachment.name.isNullOrBlank()) {
                resolveResourceUrl(hostUrl, "file/${attachment.name}/${attachment.filename}")
            } else {
                null
            }
            Log.d(
                "MemosDebug",
                "AttachmentManager.getUrl: name=${attachment.name}, ext=${attachment.externalLink} -> $url"
            )
            return url
        }
    }
}
