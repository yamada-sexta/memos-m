package org.example.memosm.data.cache

import kotlinx.coroutines.flow.first
import org.example.memosm.model.Memo

/** Test setup uses the same ingestion path as real network responses. */
suspend fun MemoCacheRepository.seedMemos(
    accountId: String,
    listType: CacheListType,
    memos: List<Memo>,
    replace: Boolean = true,
    parentName: String? = null,
) {
    val key = defaultQueryKey(listType, parentName)
    val generation = beginRefresh(accountId, key, listType, parentName, incremental = !replace)
    ingestPage(
        accountId,
        key,
        listType,
        memos,
        parentName,
        nextPageToken = null,
        refresh = true,
        incremental = !replace,
        expectedGeneration = generation,
    )
}

suspend fun MemoCacheRepository.seedMemo(
    accountId: String,
    memo: Memo,
    listType: CacheListType = CacheListType.USER,
    parentName: String? = null,
) = seedMemos(accountId, listType, listOf(memo), replace = false, parentName = parentName)

suspend fun MemoCacheRepository.cachedMemos(
    accountId: String,
    listType: CacheListType,
    parentName: String? = null,
    limit: Int? = null,
): List<Memo> =
    observeCachedMemos(accountId, listOf(listType))
        .first()
        .filter { parentName == null || it.parent == parentName }
        .take(limit ?: Int.MAX_VALUE)

suspend fun MemoDao.seedCachedMemos(
    accountId: String,
    listType: String,
    memos: List<CachedMemo>,
    replace: Boolean,
) = MemoCacheRepository(this).seedMemos(
    accountId,
    CacheListType.valueOf(listType),
    memos.map { requireNotNull(it.toMemo()) },
    replace,
    memos.firstOrNull()?.parentName,
)
