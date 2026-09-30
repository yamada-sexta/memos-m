package org.example.memosm.data.cache

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.example.memosm.model.Memo

private const val TAG = "MemoCacheRepository"

/**
 * Repository for caching and retrieving memos from local storage.
 */
class MemoCacheRepository(private val memoDao: MemoDao) {

    /**
     * Cache a list of memos for a specific account and list type.
     *
     * @param replace when true the existing cache for that list is replaced
     * (first page of a fresh fetch); when false the new items are merged into
     * the existing cache (subsequent pages accumulated for offline paging).
     * @param parentName for [CacheListType.COMMENT] rows, the parent memo name.
     */
    suspend fun cacheMemos(
        accountId: String,
        listType: CacheListType,
        memos: List<Memo>,
        replace: Boolean = true,
        parentName: String? = null
    ) {
        try {
            // Cache non-null-named memos with their order preserved
            val cachedMemos = memos
                .filter { it.name != null }
                .mapIndexed { index, memo ->
                    CachedMemo.fromMemo(memo, accountId, listType, index, parentName)
                }

            if (replace) {
                // Atomic delete+insert in one transaction: a concurrent writer
                // never observes (or gets wiped by) the intermediate empty state.
                memoDao.replaceMemos(accountId, listType.name, cachedMemos)
            } else if (cachedMemos.isNotEmpty()) {
                memoDao.insertMemos(cachedMemos)
            }
            Log.d(TAG, "Cached ${cachedMemos.size} memos for $listType (replace=$replace)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error caching memos for $listType", e)
        }
    }

    /**
     * Retrieve cached memos for a specific account and list type.
     * [limit] bounds the result (null = all rows): the list prefill only needs
     * the top rows for first paint and would otherwise deserialize the whole
     * pre-downloaded history on every cold start.
     * Returns empty list if no cache exists or on error.
     */
    suspend fun getCachedMemos(
        accountId: String,
        listType: CacheListType,
        parentName: String? = null,
        limit: Int? = null
    ): List<Memo> {
        return try {
            val cached = if (parentName != null) {
                memoDao.getMemosByParent(accountId, listType.name, parentName)
            } else if (limit != null) {
                memoDao.getMemosLimited(accountId, listType.name, limit)
            } else {
                memoDao.getMemos(accountId, listType.name)
            }
            val memos = cached.mapNotNull { it.toMemo() }
            Log.d(TAG, "Retrieved ${memos.size} cached memos for $listType")
            memos
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving cached memos for $listType", e)
            emptyList()
        }
    }

    /**
     * Return a single cached memo by name (across all list types), or null.
     */
    suspend fun getCachedMemo(accountId: String, name: String): Memo? {
        return try {
            memoDao.getMemoByName(accountId, name)?.toMemo()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving cached memo $name", e)
            null
        }
    }

    /**
     * Local search over the cached memos of an account.
     * Tags are AND-matched against the memo's tag set; results are deduped by
     * memo name. [explore] restricts the search to the EXPLORE cache rows
     * (PUBLIC/PROTECTED only) for the Explore-tab search; otherwise the
     * user-owned lists (USER/ARCHIVED/EXPLORE) are searched.
     */
    suspend fun searchCachedMemos(
        accountId: String,
        query: String,
        tags: List<String> = emptyList(),
        startMillis: Long = 0L,
        endMillis: Long = 0L,
        limit: Int = 200,
        explore: Boolean = false
    ): List<Memo> {
        return try {
            val queryPattern = if (query.isBlank()) "" else "%${escapeLike(query)}%"
            val listTypes = if (explore) {
                listOf(CacheListType.EXPLORE.name)
            } else {
                listOf(CacheListType.USER.name, CacheListType.ARCHIVED.name, CacheListType.EXPLORE.name)
            }
            val cached = memoDao.searchMemosByContent(
                accountId = accountId,
                listTypes = listTypes,
                queryPattern = queryPattern,
                startTs = startMillis,
                endTs = endMillis,
                limit = limit * 2 // extra headroom so tag filtering doesn't starve the result set
            )
            val selectedTags = tags.mapNotNull { it.removePrefix("#") }.filter { it.isNotBlank() }
            cached.mapNotNull { it.toMemo() }
                .distinctBy { it.name }
                .filter { memo ->
                    val memoTags = memo.tags.orEmpty()
                    selectedTags.all { tag -> memoTags.contains(tag) }
                }
                .take(limit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error searching cached memos", e)
            emptyList()
        }
    }

    /**
     * Insert or update a single cached memo (optimistic local write / sync result).
     */
    suspend fun upsertCachedMemo(
        accountId: String,
        memo: Memo,
        listType: CacheListType = CacheListType.USER,
        parentName: String? = null,
        order: Int = 0
    ) {
        try {
            memo.name ?: return
            memoDao.insertMemo(CachedMemo.fromMemo(memo, accountId, listType, order, parentName))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting cached memo", e)
        }
    }

    /**
     * Atomically persist a memo across list types: removes any stale row for
     * the same name (e.g. when it moved between NORMAL/ARCHIVED) and inserts
     * the fresh row in one transaction. Replaces the previous
     * removeCachedMemo + upsertCachedMemo pair, which could race with a
     * concurrent writer and leave the row missing.
     */
    suspend fun upsertCachedMemoState(
        accountId: String,
        memo: Memo,
        listType: CacheListType = CacheListType.USER,
        order: Int = 0
    ) {
        try {
            memo.name ?: return
            memoDao.saveMemoState(
                CachedMemo.fromMemo(memo, accountId, listType, order, null)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error upserting cached memo state", e)
        }
    }

    /**
     * Remove a memo from the cache across all list types (local delete).
     */
    suspend fun removeCachedMemo(accountId: String, name: String) {
        try {
            memoDao.deleteMemoByName(accountId, name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error removing cached memo", e)
        }
    }

    /**
     * Clear all cached memos for an account.
     */
    suspend fun clearCache(accountId: String) {
        try {
            memoDao.deleteAllForAccount(accountId)
            Log.d(TAG, "Cleared cache for account $accountId")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for account $accountId", e)
        }
    }

    /**
     * Number of cached memos for an account (used for cache analysis).
     */
    suspend fun getCachedCount(accountId: String): Int {
        return try {
            memoDao.getCountForAccount(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error counting cached memos", e)
            0
        }
    }

    /**
     * Trim a list type's cache to the [keep] newest memos. Enforces the
     * per-tier text cache size limit after a full pre-download.
     */
    suspend fun trimCachedMemos(accountId: String, listType: CacheListType, keep: Int) {
        if (keep <= 0) return
        try {
            memoDao.trimListType(accountId, listType.name, keep)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming cached memos for $listType", e)
        }
    }

    private fun escapeLike(value: String): String = value
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
}
