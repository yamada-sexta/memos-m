package org.example.memosm.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.example.memosm.model.Attachment

@Entity(tableName = "cached_attachments")
data class CachedAttachment(
    @PrimaryKey
    val cacheKey: String,
    val accountId: String,
    val attachmentJson: String,
    val displayOrder: Int,
    val cachedAt: Long
) {
    companion object {
        private val gson = org.example.memosm.api.GsonProvider.gson

        fun fromAttachment(
            attachment: Attachment,
            accountId: String,
            order: Int
        ): CachedAttachment {
            val key = attachment.name ?: "${attachment.filename}#$order"
            return CachedAttachment(
                cacheKey = key,
                accountId = accountId,
                attachmentJson = gson.toJson(attachment),
                displayOrder = order,
                cachedAt = System.currentTimeMillis()
            )
        }
    }

    fun toAttachment(): Attachment? {
        return runCatching { gson.fromJson(attachmentJson, Attachment::class.java) }.getOrNull()
    }
}
