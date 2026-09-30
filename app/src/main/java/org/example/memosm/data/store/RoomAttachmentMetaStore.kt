package org.example.memosm.data.store

import org.example.memosm.api.GsonProvider
import org.example.memosm.data.media.CachedAttachmentMeta
import org.example.memosm.data.media.CachedAttachmentMetaDao
import org.example.memosm.model.Attachment

/**
 * Storage adapter for cached attachment metadata, backed by the
 * `cached_attachment_meta` Room table.
 *
 * Attachment metadata is a single flat per-account list ordered by
 * `createTime` (newest first) with no buckets, parent grouping or replace
 * semantics, so the surface is kept minimal (per-account, suspend).
 *
 * Business code must not use this class directly - it goes through
 * `data/offline/AttachmentCacheStore`.
 */
class RoomAttachmentMetaStore(
    private val dao: CachedAttachmentMetaDao
) {

    /** Insert or update the metadata of [attachments] for [accountId]. */
    suspend fun upsertAll(accountId: String, attachments: List<Attachment>) {
        val rows = attachments.mapNotNull { it.toRow(accountId) }
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    /**
     * Cached attachment metadata for [accountId], newest first. [limit]
     * bounds the result (null = all rows). The full `Attachment` is restored
     * from the stored JSON; rows that fail to deserialize are skipped.
     */
    suspend fun getAll(accountId: String, limit: Int? = null): List<Attachment> {
        val rows = if (limit == null) dao.getAll(accountId) else dao.getAll(accountId, limit)
        return rows.mapNotNull { row ->
            try {
                GsonProvider.gson.fromJson(row.attachmentJson, Attachment::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Remove all attachment metadata of an account. */
    suspend fun clear(accountId: String) = dao.clear(accountId)

    /** Number of cached metadata rows for an account. */
    suspend fun count(accountId: String): Int = dao.count(accountId)

    private fun Attachment.toRow(accountId: String): CachedAttachmentMeta? {
        val attachmentName = name ?: return null
        return CachedAttachmentMeta(
            accountId = accountId,
            attachmentName = attachmentName,
            filename = filename,
            type = displayType,
            size = size?.toLongOrNull() ?: 0L,
            createTime = createTime?.toEpochMilliseconds() ?: 0L,
            memoName = memo,
            attachmentJson = GsonProvider.gson.toJson(this)
        )
    }
}
