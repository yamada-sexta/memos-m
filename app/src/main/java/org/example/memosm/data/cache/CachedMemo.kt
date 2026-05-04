package org.example.memosm.data.cache

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoSyncState

/**
 * Cache list type to distinguish between user memos, explore memos, etc.
 */
enum class CacheListType {
    USER,
    EXPLORE,
    ARCHIVED
}

/**
 * Room entity for caching memos locally.
 * Stores the full Memo as JSON for simplicity and flexibility.
 */
@Entity(tableName = "cached_memos")
data class CachedMemo(
    @PrimaryKey
    val cacheKey: String,
    val accountId: String,      // Account this memo belongs to
    val listType: String,       // CacheListType.name() for Room compatibility
    val memoJson: String,       // Serialized Memo object
    val displayOrder: Int,      // Order in the list (0 = first)
    val cachedAt: Long,         // Timestamp when cached
    val syncState: String = MemoSyncState.SYNCED.name
) {
    companion object {
        private val gson = org.example.memosm.api.GsonProvider.gson

        fun fromMemo(
            memo: Memo,
            accountId: String,
            listType: CacheListType,
            order: Int
        ): CachedMemo {
            val cacheKey = memo.localId ?: memo.name ?: "${listType.name.lowercase()}_${memo.content.hashCode()}_$order"
            return CachedMemo(
                cacheKey = cacheKey,
                accountId = accountId,
                listType = listType.name,
                memoJson = gson.toJson(memo),
                displayOrder = order,
                cachedAt = System.currentTimeMillis(),
                syncState = memo.syncState.name
            )
        }
    }

    fun toMemo(): Memo? {
        return try {
            gson.fromJson(memoJson, Memo::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
