package org.example.memosm.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for cached memos.
 */
@Dao
interface MemoDao {

    /**
     * Insert or replace cached memos.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemos(memos: List<CachedMemo>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(attachments: List<CachedAttachment>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemo(memo: CachedMemo)

    /**
     * Get all cached memos for a specific account and list type.
     * Ordered by displayOrder to maintain original server order.
     */
    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND listType = :listType ORDER BY displayOrder ASC")
    suspend fun getMemos(accountId: String, listType: String): List<CachedMemo>

    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND listType = :listType ORDER BY displayOrder ASC")
    fun observeMemos(accountId: String, listType: String): Flow<List<CachedMemo>>

    @Query("SELECT * FROM cached_attachments WHERE accountId = :accountId ORDER BY displayOrder ASC")
    suspend fun getAttachments(accountId: String): List<CachedAttachment>

    @Query("SELECT * FROM cached_attachments WHERE accountId = :accountId ORDER BY displayOrder ASC")
    fun observeAttachments(accountId: String): Flow<List<CachedAttachment>>

    /**
     * Delete all cached memos for a specific account and list type.
     */
    @Query("DELETE FROM cached_memos WHERE accountId = :accountId AND listType = :listType")
    suspend fun deleteMemos(accountId: String, listType: String)

    @Query("DELETE FROM cached_attachments WHERE accountId = :accountId")
    suspend fun deleteAttachments(accountId: String)

    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND syncState != :syncState ORDER BY cachedAt DESC")
    suspend fun getMemosBySyncStateNot(accountId: String, syncState: String): List<CachedMemo>

    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND syncState != :syncState ORDER BY cachedAt DESC")
    fun observeMemosBySyncStateNot(accountId: String, syncState: String): Flow<List<CachedMemo>>

    @Query("DELETE FROM cached_memos WHERE accountId = :accountId AND cacheKey = :cacheKey")
    suspend fun deleteMemoByKey(accountId: String, cacheKey: String)

    /**
     * Delete all cached memos for a specific account.
     */
    @Query("DELETE FROM cached_memos WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)

    /**
     * Delete all cached memos.
     */
    @Query("DELETE FROM cached_memos")
    suspend fun deleteAll()

    @Query("DELETE FROM cached_attachments")
    suspend fun deleteAllAttachments()

}
