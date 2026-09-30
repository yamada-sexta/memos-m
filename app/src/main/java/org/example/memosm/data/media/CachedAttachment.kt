package org.example.memosm.data.media

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity tracking attachment files downloaded for offline use.
 * The bytes live in filesDir/offline_media/{accountId}/{attachmentName}/{filename}
 * (see `OfflineMediaPaths`; moved from cacheDir in Phase 2).
 *
 * Primary key is (accountId, attachmentName): attachment names are only unique
 * per server, so two accounts on different servers can both have
 * "attachments/123" with different content. Keying by name alone would let one
 * account's download overwrite the other's row and file.
 */
@Entity(
    tableName = "cached_attachments",
    primaryKeys = ["accountId", "attachmentName"],
    indices = [Index(value = ["accountId", "lastAccessedAt"])]
)
data class CachedAttachment(
    val accountId: String,
    val attachmentName: String,     // attachment.name (e.g. "attachments/123")
    val memoName: String,           // Memo this attachment belongs to
    val url: String,                // Full resolved URL the file was downloaded from
    val localPath: String,          // Absolute path of the local file
    val size: Long = 0L,            // File size in bytes
    val downloadedAt: Long = 0L,    // When it was downloaded
    val lastAccessedAt: Long = 0L   // LRU eviction key
)
