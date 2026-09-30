package org.example.memosm.api

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.example.memosm.data.StreamingBase64
import org.example.memosm.model.Attachment
import java.io.File

/**
 * Specialized API client for streaming attachment uploads.
 * Uses OkHttp directly (bypassing Retrofit) to stream large files
 * without loading the entire content into memory.
 */
class StreamingAttachmentApi(
    private val client: OkHttpClient,
    private val baseUrl: String
) {
    companion object {
        private const val TAG = "StreamingAttachmentApi"
    }

    private val gson = GsonProvider.gson

    /**
     * Create an attachment by streaming the file content as base64.
     * The entire file is never loaded into memory at once.
     *
     * @param filename The name of the file
     * @param mimeType The MIME type of the file
     * @param contentUri The Uri to read the file content from
     * @param context Android context for content resolver
     * @return The created Attachment, or null if upload failed
     */
    suspend fun createAttachmentStreaming(
        filename: String,
        mimeType: String,
        contentUri: Uri,
        context: Context
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting streaming upload for $filename (mimeType=$mimeType)")

            val requestBody = StreamingAttachmentRequestBody(
                filename = filename,
                mimeType = mimeType,
                contentUri = contentUri,
                context = context
            )

            val url = "${baseUrl.trimEnd('/')}/api/v1/attachments"
            Log.d(TAG, "Upload URL: $url")

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                Log.e(TAG, "Upload failed with code ${response.code}: ${response.message}")
                Log.e(TAG, "Error response body: $errorBody")
                return@withContext null
            }

            val responseBody = response.body.string()

            Log.d(TAG, "Response body: $responseBody")
            val attachment = gson.fromJson(responseBody, Attachment::class.java)
            Log.d(TAG, "Upload successful: ${attachment.name}")
            attachment
        } catch (e: Exception) {
            Log.e(TAG, "Streaming upload failed", e)
            null
        }
    }

    /**
     * Create an attachment by streaming from a local file.
     */
    suspend fun createAttachmentFromFile(
        file: File,
        mimeType: String,
        attachmentId: String? = null
    ): Attachment? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting streaming upload for file ${file.name}")

            val requestBody = StreamingFileAttachmentRequestBody(
                filename = file.name,
                mimeType = mimeType,
                file = file
            )

            // Forward the clientId as the API attachmentId so a retried
            // streaming upload reconciles server-side instead of duplicating.
            val base = "${baseUrl.trimEnd('/')}/api/v1/attachments"
            val url = if (attachmentId != null) {
                "$base?attachmentId=" + java.net.URLEncoder.encode(attachmentId, "UTF-8")
            } else {
                base
            }
            Log.d(TAG, "Upload URL: $url")

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body.string()
                Log.e(TAG, "Upload failed with code ${response.code}: ${response.message}")
                Log.e(TAG, "Error response body: $errorBody")
                return@withContext null
            }

            val responseBody = response.body.string()

            Log.d(TAG, "Response body: $responseBody")
            val attachment = gson.fromJson(responseBody, Attachment::class.java)
            Log.d(TAG, "Upload successful: ${attachment.name}")
            attachment
        } catch (e: Exception) {
            Log.e(TAG, "Streaming upload failed", e)
            null
        }
    }

    /**
     * RequestBody that streams the attachment JSON with base64 content from a Uri.
     *
     * The JSON is manually constructed to allow streaming the base64 content
     * without loading it all into memory. Format:
     * {"filename":"...","type":"...","content":"<base64>"}
     */
    private class StreamingAttachmentRequestBody(
        private val filename: String,
        private val mimeType: String,
        private val contentUri: Uri,
        private val context: Context
    ) : RequestBody() {

        override fun contentType() = "application/json".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            // Build JSON manually to stream the base64 content
            // Escape special characters in filename
            val escapedFilename = escapeJsonString(filename)
            val escapedMimeType = escapeJsonString(mimeType)

            // Write JSON opening and fields
            sink.writeUtf8("{\"filename\":\"")
            sink.writeUtf8(escapedFilename)
            sink.writeUtf8("\",\"type\":\"")
            sink.writeUtf8(escapedMimeType)
            sink.writeUtf8("\",\"content\":\"")

            // Stream base64 content directly to the sink
            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
                StreamingBase64.encodeToStream(inputStream, sink.outputStream())
            }

            // Close the JSON
            sink.writeUtf8("\"}")
        }

        private fun escapeJsonString(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }

    /**
     * RequestBody that streams the attachment JSON with base64 content from a File.
     */
    private class StreamingFileAttachmentRequestBody(
        private val filename: String,
        private val mimeType: String,
        private val file: File
    ) : RequestBody() {

        override fun contentType() = "application/json".toMediaType()

        override fun writeTo(sink: BufferedSink) {
            // Build JSON manually to stream the base64 content
            val escapedFilename = escapeJsonString(filename)
            val escapedMimeType = escapeJsonString(mimeType)

            // Write JSON opening and fields
            sink.writeUtf8("{\"filename\":\"")
            sink.writeUtf8(escapedFilename)
            sink.writeUtf8("\",\"type\":\"")
            sink.writeUtf8(escapedMimeType)
            sink.writeUtf8("\",\"content\":\"")

            // Stream base64 content directly to the sink
            file.inputStream().use { inputStream ->
                StreamingBase64.encodeToStream(inputStream, sink.outputStream())
            }

            // Close the JSON
            sink.writeUtf8("\"}")
        }

        private fun escapeJsonString(s: String): String {
            return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }
    }
}
