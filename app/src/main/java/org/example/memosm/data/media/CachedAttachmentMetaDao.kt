package org.example.memosm.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedAttachmentMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CachedAttachmentMeta>)

    @Query("SELECT * FROM cached_attachment_meta WHERE accountId = :accountId ORDER BY createTime DESC")
    suspend fun getAll(accountId: String): List<CachedAttachmentMeta>

    @Query("SELECT * FROM cached_attachment_meta WHERE accountId = :accountId ORDER BY createTime DESC LIMIT :limit")
    suspend fun getAll(accountId: String, limit: Int): List<CachedAttachmentMeta>

    @Query("DELETE FROM cached_attachment_meta WHERE accountId = :accountId")
    suspend fun clear(accountId: String)

    @Query("SELECT COUNT(*) FROM cached_attachment_meta WHERE accountId = :accountId")
    suspend fun count(accountId: String): Int
}
