package org.example.memosm.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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
    suspend fun insertMemo(memo: CachedMemo)

    /**
     * Get all cached memos for a specific account and list type.
     * Ordered by displayOrder to maintain original server order.
     */
    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND listType = :listType ORDER BY displayOrder ASC")
    suspend fun getMemos(accountId: String, listType: String): List<CachedMemo>

    /**
     * First [limit] cached memos for an account and list type. Used by the
     * list prefill, which only needs the top rows for first paint - reading
     * the whole pre-downloaded history here would deserialize thousands of
     * JSON rows on every cold start.
     */
    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND listType = :listType ORDER BY displayOrder ASC LIMIT :limit")
    suspend fun getMemosLimited(accountId: String, listType: String, limit: Int): List<CachedMemo>

    /**
     * Get cached memos for a specific account, list type and parent memo
     * (used for cached comments).
     */
    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND listType = :listType AND parentName = :parentName ORDER BY displayOrder ASC")
    suspend fun getMemosByParent(
        accountId: String, listType: String, parentName: String
    ): List<CachedMemo>

    /**
     * Full-text-ish search over cached memos for an account.
     * Query/tag patterns are pre-escaped LIKE patterns; empty pattern disables the filter.
     * [listTypes] controls which cache lists are searched: the default memo
     * search covers USER/ARCHIVED/EXPLORE (comment and search-queue rows must
     * not surface in memo search results), while an Explore-tab search
     * restricts itself to EXPLORE rows only.
     */
    @Query(
        "SELECT * FROM cached_memos WHERE accountId = :accountId " +
            "AND listType IN (:listTypes) " +
            "AND (:queryPattern = '' OR content LIKE :queryPattern ESCAPE '\\') " +
            "AND (:startTs = 0 OR createTime >= :startTs) " +
            "AND (:endTs = 0 OR createTime <= :endTs) " +
            "ORDER BY createTime DESC LIMIT :limit"
    )
    suspend fun searchMemosByContent(
        accountId: String,
        listTypes: List<String>,
        queryPattern: String,
        startTs: Long,
        endTs: Long,
        limit: Int
    ): List<CachedMemo>

    @Query("SELECT * FROM cached_memos WHERE accountId = :accountId AND name = :name LIMIT 1")
    suspend fun getMemoByName(accountId: String, name: String): CachedMemo?

    @Query("SELECT COUNT(*) FROM cached_memos WHERE accountId = :accountId")
    suspend fun getCountForAccount(accountId: String): Int

    /**
     * Trim a list type's cache to the [keep] newest memos (by createTime).
     * Used to enforce the per-tier text cache size limit.
     */
    @Query(
        "DELETE FROM cached_memos WHERE accountId = :accountId AND listType = :listType AND name NOT IN " +
            "(SELECT name FROM cached_memos WHERE accountId = :accountId AND listType = :listType ORDER BY createTime DESC LIMIT :keep)"
    )
    suspend fun trimListType(accountId: String, listType: String, keep: Int)

    /**
     * Atomically replace a list type's cache: delete + insert in one
     * transaction so a concurrent writer cannot observe (or be wiped by) the
     * intermediate empty state.
     */
    @Transaction
    suspend fun replaceMemos(accountId: String, listType: String, memos: List<CachedMemo>) {
        deleteMemos(accountId, listType)
        insertMemos(memos)
    }

    /**
     * Atomically persist a memo across list types: removes any previous row
     * for the same memo name (e.g. when it moved between NORMAL/ARCHIVED)
     * and inserts the fresh row in one transaction.
     */
    @Transaction
    suspend fun saveMemoState(memo: CachedMemo) {
        deleteMemoByName(memo.accountId, memo.name)
        insertMemo(memo)
    }

    /**
     * Delete all cached memos for a specific account and list type.
     */
    @Query("DELETE FROM cached_memos WHERE accountId = :accountId AND listType = :listType")
    suspend fun deleteMemos(accountId: String, listType: String)

    /**
     * Delete a memo across all list types for an account.
     */
    @Query("DELETE FROM cached_memos WHERE accountId = :accountId AND name = :name")
    suspend fun deleteMemoByName(accountId: String, name: String)

    /**
     * Delete all cached memos for a specific account.
     */
    @Query("DELETE FROM cached_memos WHERE accountId = :accountId")
    suspend fun deleteAllForAccount(accountId: String)
}
