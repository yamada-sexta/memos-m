package org.example.memosm.data.cache

import org.example.memosm.data.media.AttachmentUpload

import androidx.paging.PagingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.model.Memo

/** All rendered memo values originate in Room; failures are never converted to empty data. */
class MemoCacheRepository(
    private val memoDao: MemoDao,
) {
    fun pagingSource(
        accountId: String,
        queryKey: String,
        order: MemoOrderBy? = null,
        sourceListType: CacheListType? = null,
    ): PagingSource<Int, CachedMemo> = memoDao.pagingSource(accountId, queryKey, order?.name.orEmpty(), sourceListType?.name)

    fun observeQuery(
        accountId: String,
        queryKey: String,
    ): Flow<CachedQuery?> = memoDao.observeQuery(accountId, queryKey)

    suspend fun getQuery(
        accountId: String,
        queryKey: String,
    ): CachedQuery? = memoDao.getQuery(accountId, queryKey)

    fun observeMemo(
        accountId: String,
        name: String,
    ): Flow<Memo?> =
        memoDao.observeEntity(accountId, name).map {
            it?.takeUnless { entity -> entity.deleted }?.visibleMemo()
        }

    fun observeCachedMemos(
        accountId: String,
        listTypes: List<CacheListType>,
    ): Flow<List<Memo>> =
        memoDao.observeMemos(accountId, listTypes.map { it.name }).map { rows -> rows.map { requireNotNull(it.toMemo()) } }

    fun observeQueryMemos(
        accountId: String,
        queryKey: String,
    ): Flow<List<Memo>> = memoDao.observeQueryMemos(accountId, queryKey).map { rows -> rows.map { requireNotNull(it.toMemo()) } }

    suspend fun readCollectionSnapshot(
        accountId: String,
        queryKey: String,
        sourceListTypes: List<CacheListType>,
        allowFallback: Boolean,
    ): Pair<List<Memo>, CachedQuery?> {
        val (rows, query) = memoDao.readCollectionSnapshot(accountId, queryKey, sourceListTypes.map { it.name }, allowFallback)
        return rows.map { requireNotNull(it.toMemo()) } to query
    }

    suspend fun resolveServerName(
        accountId: String,
        localName: String,
    ): String? {
        val entity = memoDao.getEntity(accountId, localName)
        return if (entity != null) entity.remoteName else localName.takeIf { it.startsWith("memos/") }
    }

    suspend fun beginRefresh(
        accountId: String,
        queryKey: String,
        listType: CacheListType,
        parentName: String? = null,
        incremental: Boolean = false,
    ): Long =
        BackupCoordinator.withStorageLock {
            memoDao.beginRefresh(accountId, queryKey, listType, parentName, incremental)
        }

    suspend fun ingestPage(
        accountId: String,
        queryKey: String,
        listType: CacheListType,
        memos: List<Memo>,
        parentName: String? = null,
        nextPageToken: String?,
        refresh: Boolean,
        incremental: Boolean = false,
        checkpointAt: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
        expectedPageToken: String? = null,
    ) = BackupCoordinator.withStorageLock {
        memoDao.ingestPage(
            accountId,
            queryKey,
            listType,
            memos,
            parentName,
            nextPageToken,
            refresh,
            incremental,
            checkpointAt,
            expectedGeneration,
            expectedPageToken,
        )
    }

    suspend fun commitMutation(
        op: PendingOp,
        optimisticMemo: Memo?,
        listType: CacheListType = CacheListType.USER,
        parentName: String? = op.parentName,
        uploads: List<AttachmentUpload> = emptyList(),
    ) = BackupCoordinator.withStorageLock {
        memoDao.commitMutation(op, optimisticMemo, listType, parentName, uploads)
    }

    suspend fun acknowledge(
        op: PendingOp,
        serverMemo: Memo? = null,
    ) = BackupCoordinator.withStorageLock { memoDao.acknowledge(op, serverMemo) }

    suspend fun discardMutation(
        op: PendingOp,
        serverMemo: Memo? = null,
    ) = BackupCoordinator.withStorageLock {
        memoDao.discardMutation(op, serverMemo)
    }

    suspend fun resolveMutation(
        op: PendingOp,
        serverMemo: Memo,
        resolvedPayload: String? = null,
        resolvedMask: String? = null,
    ) = BackupCoordinator.withStorageLock {
        memoDao.resolveMutation(op, serverMemo, resolvedPayload, resolvedMask)
    }

    suspend fun getCachedMemo(
        accountId: String,
        name: String,
    ): Memo? =
        memoDao
            .getEntity(accountId, name)
            ?.takeUnless {
                it.deleted
            }?.visibleMemo()

    suspend fun clearCache(accountId: String) = BackupCoordinator.withStorageLock { memoDao.deleteAllForAccount(accountId) }

    suspend fun getCachedCount(accountId: String): Int = memoDao.getCountForAccount(accountId)

    suspend fun referencesAttachment(
        accountId: String,
        clientId: String,
        localPath: String,
    ): Boolean = memoDao.referencesAttachment(accountId, clientId, localPath)

    suspend fun trimCachedMemos(
        accountId: String,
        listType: CacheListType,
        keep: Int,
    ) = BackupCoordinator.withStorageLock {
        if (keep > 0) {
            memoDao.trimListType(accountId, listType.name, keep)
            memoDao.deleteCleanOrphans(accountId)
        }
    }
}
