package org.example.memosm.data.media

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedAttachmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attachment: CachedAttachment)

    @Query("SELECT * FROM cached_attachments WHERE attachmentName = :attachmentName AND accountId = :accountId LIMIT 1")
    suspend fun getByAttachment(
        accountId: String,
        attachmentName: String,
    ): CachedAttachment?

    @Query("SELECT * FROM cached_attachments WHERE accountId = :accountId")
    suspend fun getAllForAccount(accountId: String): List<CachedAttachment>

    @Query("SELECT * FROM cached_attachments")
    suspend fun getAll(): List<CachedAttachment>

    @Query("SELECT COALESCE(SUM(size), 0) FROM cached_attachments WHERE accountId = :accountId")
    suspend fun getTotalSize(accountId: String): Long

    @Query("SELECT COALESCE(SUM(size), 0) FROM cached_attachments")
    suspend fun getTotalSizeAll(): Long

    @Query("DELETE FROM cached_attachments WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)

    @Query("DELETE FROM cached_attachments WHERE accountId = :accountId AND attachmentName = :attachmentName")
    suspend fun deleteForAttachment(
        accountId: String,
        attachmentName: String,
    )
}
