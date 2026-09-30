package org.example.memosm.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Location
import org.example.memosm.model.Visibility
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import kotlin.time.Instant

/**
 * Manages draft storage in the app's cache directory using streaming JSON parsing.
 * Uses GSON's JsonReader/JsonWriter to avoid loading entire JSON into memory,
 * which prevents OOM errors when drafts contain large base64-encoded attachments.
 *
 * Drafts are stored per-account as JSON files: drafts_{accountId}.json
 */
class DraftManager(private val context: Context) {

    private val gson = Gson()
    private val mutex = Mutex()

    companion object {
        private const val TAG = "DraftManager"
        private const val DRAFTS_DIR = "drafts"
        private fun draftsFileName(accountId: String) = "drafts_$accountId.json"
    }

    private fun getDraftsDir(): File {
        val dir = File(context.cacheDir, DRAFTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getDraftsFile(accountId: String): File {
        return File(getDraftsDir(), draftsFileName(accountId))
    }

    /**
     * Get all drafts for an account using streaming JSON parsing.
     * Reads one draft at a time to minimize memory usage.
     */
    suspend fun getDrafts(accountId: String): List<Draft> = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = getDraftsFile(accountId)
                if (!file.exists()) return@withContext emptyList()

                val drafts = mutableListOf<Draft>()

                JsonReader(BufferedReader(FileReader(file))).use { reader ->
                    if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            // Parse each draft object individually
                            val draft = parseDraft(reader)
                            if (draft != null) {
                                drafts.add(draft)
                            }
                        }
                        reader.endArray()
                    }
                }

                drafts
            } catch (e: Exception) {
                Log.e(TAG, "Error reading drafts for account $accountId", e)
                emptyList()
            }
        }
    }

    /**
     * Parse a single Draft from the JsonReader.
     */
    private fun parseDraft(reader: JsonReader): Draft? {
        try {
            var id: String? = null
            var content = ""
            var visibility = Visibility.PRIVATE
            var attachments = mutableListOf<Attachment>()
            var location: Location? = null
            var createdAt = System.currentTimeMillis()
            var updatedAt = System.currentTimeMillis()

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "content" -> content = reader.nextString()
                    "visibility" -> visibility = try {
                        Visibility.valueOf(reader.nextString())
                    } catch (e: Exception) {
                        Visibility.PRIVATE
                    }

                    "attachments" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val attachment = parseAttachment(reader)
                            if (attachment != null) {
                                attachments.add(attachment)
                            }
                        }
                        reader.endArray()
                    }

                    "location" -> location = parseLocation(reader)
                    "createdAt" -> createdAt = reader.nextLong()
                    "updatedAt" -> updatedAt = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return if (id != null) {
                Draft(id, content, visibility, attachments, location, createdAt, updatedAt)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing draft", e)
            reader.skipValue()
            return null
        }
    }

    /**
     * Parse a single Attachment from the JsonReader.
     * The base64 content is read as a string - streaming happens at file I/O level.
     */
    private fun parseAttachment(reader: JsonReader): Attachment? {
        try {
            var name: String? = null
            var createTime: Instant? = null
            var filename = ""
            var content: String? = null
            var externalLink: String? = null
            var type = ""
            var mimeType: String? = null
            var size: String? = null
            var memo: String? = null
            var clientId: String? = null
            var localPath: String? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "name" -> name = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

//                    "createTime" -> createTime = if (reader.peek() == JsonToken.NULL) {
//                        reader.nextNull(); null
//                    } else reader.nextString()
                    "createTime" -> createTime = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull()
                        null
                    } else {
                        // You must parse the string into an Instant object
                        Instant.parse(reader.nextString())
                    }

                    "filename" -> filename = reader.nextString()
                    "content" -> content = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "externalLink" -> externalLink = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "type" -> type = reader.nextString()
                    "mimeType" -> mimeType = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "size" -> size = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "memo" -> memo = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "clientId" -> clientId = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "localPath" -> localPath = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return Attachment(
                name, createTime, filename, content, externalLink, type, mimeType, size, memo,
                clientId, localPath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing attachment", e)
            reader.skipValue()
            return null
        }
    }

    /**
     * Parse a Location from the JsonReader.
     */
    private fun parseLocation(reader: JsonReader): Location? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }

        try {
            var placeholder: String? = null
            var latitude: Double? = null
            var longitude: Double? = null

            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "placeholder" -> placeholder = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextString()

                    "latitude" -> latitude = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextDouble()

                    "longitude" -> longitude = if (reader.peek() == JsonToken.NULL) {
                        reader.nextNull(); null
                    } else reader.nextDouble()

                    else -> reader.skipValue()
                }
            }
            reader.endObject()

            return Location(placeholder, latitude, longitude)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing location", e)
            reader.skipValue()
            return null
        }
    }

    /**
     * Save or update a draft using streaming JSON writing.
     */
    suspend fun saveDraft(accountId: String, draft: Draft): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val drafts = getDraftsInternal(accountId).toMutableList()
                val existingIndex = drafts.indexOfFirst { it.id == draft.id }

                val updatedDraft = draft.copy(updatedAt = System.currentTimeMillis())

                if (existingIndex >= 0) {
                    drafts[existingIndex] = updatedDraft
                } else {
                    drafts.add(0, updatedDraft) // Add at beginning (newest first)
                }

                saveDraftsInternal(accountId, drafts)
            } catch (e: Exception) {
                Log.e(TAG, "Error saving draft for account $accountId", e)
            }
        }
    }

    /**
     * Delete a specific draft by ID.
     */
    suspend fun deleteDraft(accountId: String, draftId: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val drafts = getDraftsInternal(accountId).toMutableList()
                    val removed = drafts.removeAll { it.id == draftId }
                    if (removed) {
                        saveDraftsInternal(accountId, drafts)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error deleting draft $draftId for account $accountId", e)
                }
            }
        }

    /**
     * Clear all drafts for an account.
     */
    suspend fun clearDrafts(accountId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val file = getDraftsFile(accountId)
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing drafts for account $accountId", e)
            }
        }
    }

    /**
     * Raw substring check over the persisted draft file: true when any draft
     * for [accountId] mentions [needle] (e.g. a queued-upload clientId).
     * Race-tolerant by design - it answers "is this string referenced" without
     * deserializing, so a concurrent draft save can only widen the match.
     */
    suspend fun draftsContain(accountId: String, needle: String): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val file = getDraftsFile(accountId)
                    file.exists() && file.readText().contains(needle)
                } catch (e: Exception) {
                    Log.e(TAG, "Error scanning drafts for account $accountId", e)
                    false
                }
            }
        }

    /**
     * Get draft count for an account (for badge display).
     */
    suspend fun getDraftCount(accountId: String): Int = withContext(Dispatchers.IO) {
        getDrafts(accountId).size
    }

    // Internal helpers (must be called within mutex lock)

    private fun getDraftsInternal(accountId: String): List<Draft> {
        val file = getDraftsFile(accountId)
        if (!file.exists()) return emptyList()

        val drafts = mutableListOf<Draft>()

        try {
            JsonReader(BufferedReader(FileReader(file))).use { reader ->
                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val draft = parseDraft(reader)
                        if (draft != null) {
                            drafts.add(draft)
                        }
                    }
                    reader.endArray()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing drafts JSON", e)
        }

        return drafts
    }

    /**
     * Save drafts using streaming JSON writing.
     * Writes each draft object individually to avoid building the entire JSON in memory.
     */
    private fun saveDraftsInternal(accountId: String, drafts: List<Draft>) {
        val file = getDraftsFile(accountId)

        JsonWriter(BufferedWriter(FileWriter(file))).use { writer ->
            writer.setIndent("") // Compact output

            writer.beginArray()
            for (draft in drafts) {
                writeDraft(writer, draft)
            }
            writer.endArray()
        }
    }

    /**
     * Write a single Draft to the JsonWriter.
     */
    private fun writeDraft(writer: JsonWriter, draft: Draft) {
        writer.beginObject()

        writer.name("id").value(draft.id)
        writer.name("content").value(draft.content)
        writer.name("visibility").value(draft.visibility.name)

        writer.name("attachments")
        writer.beginArray()
        for (attachment in draft.attachments) {
            writeAttachment(writer, attachment)
        }
        writer.endArray()

        writer.name("location")
        if (draft.location != null) {
            writeLocation(writer, draft.location)
        } else {
            writer.nullValue()
        }

        writer.name("createdAt").value(draft.createdAt)
        writer.name("updatedAt").value(draft.updatedAt)

        writer.endObject()
    }

    /**
     * Write a single Attachment to the JsonWriter.
     */
    private fun writeAttachment(writer: JsonWriter, attachment: Attachment) {
        writer.beginObject()

        writer.name("name")
        if (attachment.name != null) writer.value(attachment.name) else writer.nullValue()

        writer.name("createTime")
        if (attachment.createTime != null) writer.value(attachment.createTime.toString()) else writer.nullValue()

        writer.name("filename").value(attachment.filename)

        writer.name("content")
        if (attachment.content != null) writer.value(attachment.content) else writer.nullValue()

        writer.name("externalLink")
        if (attachment.externalLink != null) writer.value(attachment.externalLink) else writer.nullValue()

        writer.name("type").value(attachment.type)

        writer.name("mimeType")
        if (attachment.mimeType != null) writer.value(attachment.mimeType) else writer.nullValue()

        writer.name("size")
        if (attachment.size != null) writer.value(attachment.size) else writer.nullValue()

        writer.name("memo")
        if (attachment.memo != null) writer.value(attachment.memo) else writer.nullValue()

        // Local-only queued-upload fields, so a draft restored while the
        // upload is still queued keeps pointing at the queue row.
        writer.name("clientId")
        if (attachment.clientId != null) writer.value(attachment.clientId) else writer.nullValue()

        writer.name("localPath")
        if (attachment.localPath != null) writer.value(attachment.localPath) else writer.nullValue()

        writer.endObject()
    }

    /**
     * Write a Location to the JsonWriter.
     */
    private fun writeLocation(writer: JsonWriter, location: Location) {
        writer.beginObject()

        writer.name("placeholder")
        if (location.placeholder != null) writer.value(location.placeholder) else writer.nullValue()

        writer.name("latitude")
        if (location.latitude != null) writer.value(location.latitude) else writer.nullValue()

        writer.name("longitude")
        if (location.longitude != null) writer.value(location.longitude) else writer.nullValue()

        writer.endObject()
    }
}
