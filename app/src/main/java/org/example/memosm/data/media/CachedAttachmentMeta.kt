package org.example.memosm.data.media

import android.util.Log
import androidx.room.Entity
import androidx.room.Index
import com.google.gson.JsonParseException
import org.example.memosm.api.GsonProvider
import org.example.memosm.model.Attachment
import kotlin.time.Instant

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
    indices = [Index(value = ["accountId", "createTime"])],
)
data class CachedAttachmentMeta(
    val accountId: String,
    val attachmentName: String, // attachment.name (e.g. "attachments/123")
    val filename: String,
    val type: String, // MIME type
    val size: Long = 0L, // File size in bytes
    val createTime: Long = 0L, // Epoch millis, for ordering (newest first)
    val memoName: String? = null, // Source memo, when known
    val attachmentJson: String, // Full serialized Attachment (lossless)
)

fun Attachment.toCachedMetadata(
    accountId: String,
    memoName: String? = memo,
): CachedAttachmentMeta =
    CachedAttachmentMeta(
        accountId = accountId,
        attachmentName = requireNotNull(name) { "Attachment has no server identity" },
        filename = filename,
        type = displayType,
        size = size?.toLongOrNull() ?: 0,
        createTime = createTime?.toEpochMilliseconds() ?: 0,
        memoName = memoName,
        attachmentJson = GsonProvider.gson.toJson(this),
    )

/** Keep usable offline metadata even if an older cached JSON body is incomplete or damaged. */
fun CachedAttachmentMeta.toAttachment(): Attachment {
    val cached =
        try {
            GsonProvider.gson.fromJson(attachmentJson, Attachment::class.java)
        } catch (error: JsonParseException) {
            Log.w("AttachmentCache", "Could not decode cached attachment metadata", error)
            null
        }
    return (cached ?: Attachment(filename = filename, type = type)).copy(
        name = attachmentName,
        filename = cached?.filename ?: filename,
        type = cached?.type ?: type,
        createTime = cached?.createTime ?: createTime.takeIf { it != 0L }?.let(Instant::fromEpochMilliseconds),
        size = cached?.size ?: size.takeIf { it != 0L }?.toString(),
    )
}
