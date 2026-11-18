package org.example.memosm.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.example.memosm.data.cache.CachedQuery
import org.example.memosm.data.cache.MemoMembership

const val RESOURCE_QUERY = "RESOURCES"

@Dao
interface CachedAttachmentMetaDao {
    @Query("SELECT * FROM cached_attachment_meta WHERE accountId = :accountId ORDER BY createTime DESC, attachmentName ASC")
    fun pagingSource(accountId: String): androidx.paging.PagingSource<Int, CachedAttachmentMeta>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedAttachmentMeta>)

    @Query("SELECT * FROM cached_attachment_meta WHERE accountId = :accountId ORDER BY createTime DESC")
    suspend fun getAll(accountId: String): List<CachedAttachmentMeta>

    @Query("SELECT * FROM cached_attachment_meta WHERE accountId = :accountId ORDER BY createTime DESC LIMIT :limit")
    suspend fun getAll(
        accountId: String,
        limit: Int,
    ): List<CachedAttachmentMeta>

    @Query("DELETE FROM cached_attachment_meta WHERE accountId = :accountId")
    suspend fun clear(accountId: String)

    @Query("SELECT COUNT(*) FROM cached_attachment_meta WHERE accountId = :accountId")
    suspend fun count(accountId: String): Int

    @Query("SELECT * FROM memo_queries WHERE accountId=:accountId AND `key`='RESOURCES'")
    suspend fun getQuery(accountId: String): CachedQuery?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putQuery(query: CachedQuery)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMemberships(memberships: List<MemoMembership>)

    @Query("SELECT localId FROM memo_memberships WHERE accountId=:accountId AND queryKey='RESOURCES' AND generation!=:generation")
    suspend fun unseen(
        accountId: String,
        generation: Long,
    ): List<String>

    @Query("DELETE FROM memo_memberships WHERE accountId=:accountId AND queryKey='RESOURCES' AND generation!=:generation")
    suspend fun pruneMemberships(
        accountId: String,
        generation: Long,
    )

    @Query("SELECT visibleJson FROM memo_entities WHERE accountId=:accountId AND deleted=0")
    suspend fun memoBodies(accountId: String): List<String>

    @Query("DELETE FROM cached_attachment_meta WHERE accountId=:accountId AND attachmentName IN (:names)")
    suspend fun deleteMetadata(
        accountId: String,
        names: List<String>,
    )

    @Transaction
    suspend fun beginRefresh(accountId: String): Long {
        val previous = getQuery(accountId) ?: CachedQuery(accountId, RESOURCE_QUERY, RESOURCE_QUERY)
        val generation = previous.generation + 1
        putQuery(previous.copy(generation = generation, complete = false, nextPageToken = null))
        return generation
    }

    @Transaction
    suspend fun ingestPage(
        accountId: String,
        rows: List<CachedAttachmentMeta>,
        nextPageToken: String?,
        refresh: Boolean,
        expectedGeneration: Long? = null,
        expectedPageToken: String? = null,
    ) {
        val previous = getQuery(accountId)
        if (expectedGeneration != null && (
                previous?.generation != expectedGeneration ||
                    (!refresh && previous.nextPageToken != expectedPageToken)
            )
        ) {
            throw kotlinx.coroutines.CancellationException("Resource refresh was superseded")
        }
        val generation = (previous?.generation ?: 0) + if (refresh && expectedGeneration == null) 1 else 0
        val now = System.currentTimeMillis()
        upsertAll(rows)
        putMemberships(
            rows.mapIndexed { index, row ->
                MemoMembership(accountId, RESOURCE_QUERY, row.attachmentName, RESOURCE_QUERY, null, index, generation, now)
            },
        )
        if (nextPageToken == null) {
            val referenced =
                memoBodies(accountId)
                    .flatMap { json ->
                        org.example.memosm.api.GsonProvider.gson
                            .fromJson(json, org.example.memosm.model.Memo::class.java)
                            .attachments
                            .orEmpty()
                            .mapNotNull { it.name }
                    }.toSet()
            (unseen(accountId, generation) - referenced).chunked(500).forEach { deleteMetadata(accountId, it) }
            pruneMemberships(accountId, generation)
        }
        putQuery(
            CachedQuery(
                accountId,
                RESOURCE_QUERY,
                RESOURCE_QUERY,
                loaded = true,
                complete = nextPageToken == null,
                nextPageToken = nextPageToken,
                generation = generation,
                lastFullSnapshotAt = if (nextPageToken == null) now else previous?.lastFullSnapshotAt ?: 0,
            ),
        )
    }
}
