package org.example.memosm.data.cache

import androidx.room.Entity
import androidx.room.Index
import org.example.memosm.api.GsonProvider
import org.example.memosm.model.Memo

/**
 * Cache list type to distinguish between user memos, explore memos, etc.
 */
enum class CacheListType {
    USER,
    EXPLORE,
    ARCHIVED,
    COMMENT,
    SEARCH
}

/**
 * Room entity for caching memos locally.
 * Stores the full Memo as JSON for rendering, plus indexed columns
 * (content/timestamps/tags...) used for offline search and sync conflict detection.
 */
@Entity(
    tableName = "cached_memos",
    primaryKeys = ["accountId", "listType", "name"],
    indices = [
        Index("accountId"),
        Index(value = ["accountId", "content"]),
        Index(value = ["accountId", "createTime"])
    ]
)
data class CachedMemo(
    val name: String,           // Unique memo identifier (e.g., "memos/123")
    val accountId: String,      // Account this memo belongs to
    val listType: String,       // CacheListType.name() for Room compatibility
    val memoJson: String,       // Serialized Memo object
    val displayOrder: Int,      // Order in the list (0 = first)
    val cachedAt: Long,         // Timestamp when cached
    val content: String = "",       // memo.content for local search
    val createTime: Long = 0L,      // epoch millis, memo.createTime for local search
    val updateTime: Long = 0L,      // epoch millis, memo.updateTime for conflict detection
    val visibility: String = "",    // Visibility.name()
    val state: String = "",         // MemoState.name()
    val tags: String = "",          // JSON array string for local tag filtering
    val pinned: Boolean = false,    // memo.pinned
    val parentName: String? = null  // For comments: the parent memo name
) {
    companion object {
        private val gson = GsonProvider.gson

        fun fromMemo(
            memo: Memo,
            accountId: String,
            listType: CacheListType,
            order: Int,
            parentName: String? = null
        ): CachedMemo {
            return CachedMemo(
                name = memo.name ?: "",
                accountId = accountId,
                listType = listType.name,
                memoJson = gson.toJson(memo),
                displayOrder = order,
                cachedAt = System.currentTimeMillis(),
                content = memo.content.orEmpty(),
                createTime = memo.createTime?.toEpochMilliseconds() ?: 0L,
                updateTime = memo.updateTime?.toEpochMilliseconds() ?: 0L,
                visibility = memo.visibility?.name ?: "",
                state = memo.state?.name ?: "",
                tags = gson.toJson(memo.tags ?: emptyList<String>()),
                pinned = memo.pinned ?: false,
                parentName = parentName
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
