package org.example.memosm.data.media

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity caching attachment metadata for offline listing.
 *
 * Separate from [CachedAttachment] (which tracks downloaded binaries): the
 * Resources page needs the metadata of every known attachment, not just the
 * ones whose bytes were downloaded. Rows are written from successful online
 * list fetches and can be backfilled from the `memoJson` of cached memos.
 *
 * Primary key is (accountId, attachmentName): attachment names are only
 * unique per server, matching the keying of `cached_attachments`.
 * [attachmentJson] keeps the full serialized `Attachment` so the cache read
 * is a lossless round-trip.
 */
@Entity(
    tableName = "cached_attachment_meta",
    primaryKeys = ["accountId", "attachmentName"],
    indices = [Index(value = ["accountId", "createTime"])]
)
data class CachedAttachmentMeta(
    val accountId: String,
    val attachmentName: String,     // attachment.name (e.g. "attachments/123")
    val filename: String,
    val type: String,               // MIME type
    val size: Long = 0L,            // File size in bytes
    val createTime: Long = 0L,      // Epoch millis, for ordering (newest first)
    val memoName: String? = null,   // Source memo, when known
    val attachmentJson: String      // Full serialized Attachment (lossless)
)
