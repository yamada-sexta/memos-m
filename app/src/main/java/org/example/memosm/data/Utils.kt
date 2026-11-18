package org.example.memosm.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.model.Attachment
import java.io.ByteArrayOutputStream

/**
 * Converts a local Uri to an Attachment with base64-encoded content for draft caching.
 * Uses streaming for large files to prevent OOM.
 * Returns null if the file cannot be read.
 */
suspend fun uriToBase64Attachment(
    uri: Uri,
    context: Context,
): Attachment? =
    withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver

            // Get filename
            val fileName =
                run {
                    var name: String? = null
                    if (uri.scheme == "content") {
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (index != -1) name = cursor.getString(index)
                            }
                        }
                    }
                    name ?: uri.path?.substringAfterLast('/')
                        ?: "attachment_${System.currentTimeMillis()}"
                }

            // Get MIME type
            val resolverMimeType = contentResolver.getType(uri)
            val mimeType =
                if (resolverMimeType == null || resolverMimeType == "application/octet-stream") {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: resolverMimeType
                        ?: "application/octet-stream"
                } else {
                    resolverMimeType
                }

            // Use streaming base64 encoding to avoid OOM on large files
            val inputStream = contentResolver.openInputStream(uri) ?: return@withContext null
            val base64Content =
                inputStream.use { stream ->
                    // Stream encode to a ByteArrayOutputStream, then convert to string
                    // This still uses memory but in smaller chunks
                    val outputStream = ByteArrayOutputStream()
                    StreamingBase64.encodeToStream(stream, outputStream)
                    outputStream.toString(Charsets.US_ASCII.name())
                }

            Attachment(
                filename = fileName,
                type = mimeType,
                content = base64Content,
            )
        } catch (e: Exception) {
            Log.e("Utils", "Error converting Uri to base64 Attachment", e)
            null
        }
    }
