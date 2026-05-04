package org.example.memosm.data.cache

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.MemoSyncState
import org.example.memosm.model.toDraft

private const val TAG = "MemoCacheRepository"

/**
 * Repository for caching and retrieving memos from local storage.
 */
class MemoCacheRepository(private val memoDao: MemoDao) {

    /**
     * Cache a list of memos for a specific account and list type.
     * Replaces any existing cached memos for that list.
     */
    suspend fun cacheMemos(accountId: String, listType: CacheListType, memos: List<Memo>) {
        try {
            // Clear existing cache for this list type first
            memoDao.deleteMemos(accountId, listType.name)

            // Cache non-null-named memos with their order preserved
            val cachedMemos = memos
                .filter { it.name != null }
                .mapIndexed { index, memo -> CachedMemo.fromMemo(memo, accountId, listType, index) }

            if (cachedMemos.isNotEmpty()) {
                memoDao.insertMemos(cachedMemos)
                Log.d(TAG, "Cached ${cachedMemos.size} memos for $listType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching memos for $listType", e)
        }
    }

    /**
     * Retrieve cached memos for a specific account and list type.
     * Returns empty list if no cache exists or on error.
     */
    suspend fun getCachedMemos(accountId: String, listType: CacheListType): List<Memo> {
        return try {
            val cached = memoDao.getMemos(accountId, listType.name)
            val memos = cached.mapNotNull { it.toMemo() }
            Log.d(TAG, "Retrieved ${memos.size} cached memos for $listType")
            memos
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving cached memos for $listType", e)
            emptyList()
        }
    }

    fun observeCachedMemos(accountId: String, listType: CacheListType): Flow<List<Memo>> {
        return memoDao.observeMemos(accountId, listType.name).map { cached ->
            cached.mapNotNull { it.toMemo() }
        }
    }

    fun observeUnsyncedMemos(accountId: String): Flow<List<Memo>> {
        return memoDao.observeMemosBySyncStateNot(accountId, MemoSyncState.SYNCED.name).map { cached ->
            cached.mapNotNull { it.toMemo() }.sortedByDescending { memo ->
                memo.displayTime?.toEpochMilliseconds() ?: 0L
            }
        }
    }

    suspend fun cacheAttachments(accountId: String, attachments: List<Attachment>) {
        try {
            memoDao.deleteAttachments(accountId)
            val cached = attachments.mapIndexed { index, attachment ->
                CachedAttachment.fromAttachment(attachment, accountId, index)
            }
            if (cached.isNotEmpty()) {
                memoDao.insertAttachments(cached)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error caching attachments", e)
        }
    }

    suspend fun getCachedAttachments(accountId: String): List<Attachment> {
        return try {
            memoDao.getAttachments(accountId).mapNotNull { it.toAttachment() }
        } catch (e: Exception) {
            Log.e(TAG, "Error retrieving cached attachments", e)
            emptyList()
        }
    }

    fun observeCachedAttachments(accountId: String): Flow<List<Attachment>> {
        return memoDao.observeAttachments(accountId).map { cached ->
            cached.mapNotNull { it.toAttachment() }
        }
    }

    suspend fun saveDraft(accountId: String, draft: Draft) {
        val listType = if (draft.state == MemoState.ARCHIVED) {
            CacheListType.ARCHIVED
        } else {
            CacheListType.USER
        }
        val memo = draft.toMemo().copy(
            localId = draft.id,
            syncState = draft.syncState,
            displayTime = kotlin.time.Instant.fromEpochMilliseconds(draft.updatedAt),
            updateTime = kotlin.time.Instant.fromEpochMilliseconds(draft.updatedAt),
            createTime = kotlin.time.Instant.fromEpochMilliseconds(draft.createdAt)
        )
        memoDao.insertMemo(CachedMemo.fromMemo(memo, accountId, listType, 0))
    }

    suspend fun getDrafts(accountId: String): List<Draft> {
        return memoDao.getMemosBySyncStateNot(accountId, MemoSyncState.SYNCED.name)
            .mapNotNull { it.toMemo() }
            .sortedByDescending { memo -> memo.displayTime?.toEpochMilliseconds() ?: 0L }
            .map { memo ->
                val draft = memo.toDraft()
                draft.copy(
                    id = memo.localId ?: draft.id,
                    syncState = memo.effectiveSyncState
                )
            }
    }

    suspend fun deleteDraft(accountId: String, draftId: String) {
        memoDao.deleteMemoByKey(accountId, draftId)
    }

    suspend fun clearDrafts(accountId: String) {
        getDrafts(accountId).forEach { draft ->
            memoDao.deleteMemoByKey(accountId, draft.id)
        }
    }

    /**
     * Clear all cached memos for an account.
     */
    suspend fun clearCache(accountId: String) {
        try {
            memoDao.deleteAllForAccount(accountId)
            memoDao.deleteAttachments(accountId)
            Log.d(TAG, "Cleared cache for account $accountId")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing cache for account $accountId", e)
        }
    }

    /**
     * Clear all cached memos.
     */
    suspend fun clearAllCache() {
        try {
            memoDao.deleteAll()
            memoDao.deleteAllAttachments()
            Log.d(TAG, "Cleared all cache")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all cache", e)
        }
    }
}
