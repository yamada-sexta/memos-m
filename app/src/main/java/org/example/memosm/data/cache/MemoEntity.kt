package org.example.memosm.data.cache

import androidx.room.Entity
import androidx.room.Index
import org.example.memosm.api.GsonProvider
import org.example.memosm.model.Memo

/** One identity and one visible value, shared by every collection containing a memo. */
@Entity(
    tableName = "memo_entities",
    primaryKeys = ["accountId", "localId"],
    indices = [
        Index(value = ["accountId", "remoteName"], unique = true),
        Index(value = ["accountId", "createTime"]),
    ],
)
data class MemoEntity(
    val accountId: String,
    val localId: String,
    val remoteName: String?,
    val serverJson: String?,
    val visibleJson: String,
    val cachedAt: Long,
    val content: String,
    val createTime: Long,
    val displayTime: Long,
    val updateTime: Long,
    val visibility: String,
    val state: String,
    val tags: String,
    val pinned: Boolean,
    val parentId: String?,
    val deleted: Boolean = false,
    val serverVerified: Boolean = true,
) {
    fun visibleMemo(): Memo = GsonProvider.gson.fromJson(visibleJson, Memo::class.java).copy(name = localId, remoteName = remoteName)

    fun serverMemo(): Memo? = serverJson?.let { GsonProvider.gson.fromJson(it, Memo::class.java) }

    fun project(
        memo: Memo?,
        now: Long = System.currentTimeMillis(),
    ): MemoEntity {
        if (memo == null) return copy(deleted = true, cachedAt = now)
        val value = memo.copy(name = localId, parent = parentId ?: memo.parent)
        return copy(
            visibleJson = GsonProvider.gson.toJson(value),
            cachedAt = now,
            content = value.content,
            createTime = value.createTime?.toEpochMilliseconds() ?: 0,
            displayTime = (value.displayTime ?: value.createTime)?.toEpochMilliseconds() ?: 0,
            updateTime = value.updateTime?.toEpochMilliseconds() ?: 0,
            visibility = value.visibility.name,
            state = value.state?.name.orEmpty(),
            tags = GsonProvider.gson.toJson(value.tags.orEmpty()),
            pinned = value.pinned == true,
            deleted = false,
        )
    }

    companion object {
        fun fromMemo(
            accountId: String,
            memo: Memo,
            localId: String = requireNotNull(memo.name),
            parentId: String? = memo.parent,
            remote: Boolean = true,
        ): MemoEntity {
            val json = GsonProvider.gson.toJson(memo.copy(name = localId, parent = parentId ?: memo.parent))
            return MemoEntity(
                accountId,
                localId,
                memo.name.takeIf { remote },
                GsonProvider.gson.toJson(memo).takeIf { remote },
                json,
                System.currentTimeMillis(),
                memo.content,
                memo.createTime?.toEpochMilliseconds() ?: 0,
                (memo.displayTime ?: memo.createTime)?.toEpochMilliseconds() ?: 0,
                memo.updateTime?.toEpochMilliseconds() ?: 0,
                memo.visibility.name,
                memo.state?.name.orEmpty(),
                GsonProvider.gson.toJson(memo.tags.orEmpty()),
                memo.pinned == true,
                parentId,
                serverVerified = remote,
            )
        }
    }
}

@Entity(
    tableName = "memo_memberships",
    primaryKeys = ["accountId", "queryKey", "localId"],
    indices = [Index(value = ["accountId", "localId"]), Index(value = ["accountId", "listType"])],
)
data class MemoMembership(
    val accountId: String,
    val queryKey: String,
    val localId: String,
    val listType: String,
    val parentName: String?,
    val position: Int,
    val generation: Long,
    val cachedAt: Long,
)

/** Exists even for a successfully fetched empty collection. Loading belongs to Paging. */
@Entity(tableName = "memo_queries", primaryKeys = ["accountId", "key"])
data class CachedQuery(
    val accountId: String,
    val key: String,
    val listType: String,
    val parentName: String? = null,
    val loaded: Boolean = false,
    val complete: Boolean = false,
    val nextPageToken: String? = null,
    val generation: Long = 0,
    val lastFullSnapshotAt: Long = 0,
    val incrementalCursor: Long = 0,
)

fun defaultQueryKey(
    listType: CacheListType,
    parentName: String? = null,
): String = listType.name + parentName?.let { ":$it" }.orEmpty()
