package org.example.memosm.data.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.paging.filter
import androidx.paging.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.MemoSearchFilter
import org.example.memosm.api.buildMemoSearchFilter
import org.example.memosm.api.resolveMemoOrderBy
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.CachedMemo
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.User
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.AccountContext
import java.io.IOException

/** A request captures its account, parent, filter and ordering for its entire lifetime. */
data class MemoQuery(
    val listType: CacheListType,
    val parentName: String? = null,
    val search: MemoSearchFilter? = null,
    val explore: Boolean = false,
    val hashtag: String? = null,
    val serverFilter: String? = null,
    val order: MemoOrderBy = MemoOrderBy.NEWEST,
) {
    val key: String get() =
        if (search == null && hashtag == null && serverFilter.isNullOrBlank()) {
            listType.name + parentName?.let { ":$it" }.orEmpty()
        } else {
            listType.name + ":" + GsonProvider.gson.toJson(copy(search = search?.copy(tags = search.tags.toSortedSet())))
        }
}

fun matchesLocalQuery(
    memo: Memo,
    query: MemoQuery,
    user: User?,
): Boolean {
    if (!query.serverFilter.isNullOrBlank()) return false // Arbitrary server CEL cannot be evaluated locally.
    if (query.explore && memo.visibility !in setOf(Visibility.PUBLIC, Visibility.PROTECTED)) return false
    if (query.listType == CacheListType.SEARCH && memo.state == MemoState.ARCHIVED) return false
    if (query.listType == CacheListType.SEARCH && !query.explore && memo.creator != user?.name) return false
    val criteria = query.search
    if (query.hashtag != null && query.hashtag.removePrefix("#") !in memo.tags.orEmpty()) return false
    if (criteria == null) return true
    if (!memo.content.contains(criteria.query, ignoreCase = true)) return false
    if (!memo.tags.orEmpty().containsAll(criteria.tags)) return false
    val time = memo.createTime?.toEpochMilliseconds() ?: 0L
    if (criteria.startDateMillis?.let { time < it } == true) return false
    if (criteria.endDateMillis?.let { time >= it + 86_400_000L } == true) return false
    return true
}

@OptIn(ExperimentalPagingApi::class)
suspend fun memoPages(
    account: AccountContext,
    repository: MemoCacheRepository,
    query: MemoQuery,
    user: User?,
    pageSize: Int,
    canFetch: () -> Boolean,
    isCurrent: () -> Boolean,
): Flow<PagingData<Memo>> {
    val storageGeneration = BackupCoordinator.generation

    fun current() = isCurrent() && BackupCoordinator.generation == storageGeneration
    val localSource =
        if (!query.serverFilter.isNullOrBlank() || query.search == null && query.hashtag == null) {
            null
        } else if (query.listType == CacheListType.SEARCH) {
            if (query.explore) CacheListType.EXPLORE else CacheListType.USER
        } else {
            query.listType
        }
    return Pager(
        config = PagingConfig(pageSize = pageSize.coerceAtLeast(10), enablePlaceholders = false),
        remoteMediator =
            object : RemoteMediator<Int, CachedMemo>() {
                private val tokens = mutableSetOf<String>()

                override suspend fun initialize() =
                    if (canFetch()) {
                        InitializeAction.LAUNCH_INITIAL_REFRESH
                    } else {
                        InitializeAction.SKIP_INITIAL_REFRESH
                    }

                override suspend fun load(
                    loadType: LoadType,
                    state: PagingState<Int, CachedMemo>,
                ): MediatorResult {
                    if (loadType == LoadType.PREPEND) return MediatorResult.Success(true)
                    if (!current()) throw CancellationException("Account changed")
                    return try {
                        val previous = repository.getQuery(account.account.id, query.key)
                        val token = if (loadType == LoadType.REFRESH) null else previous?.nextPageToken
                        if (loadType == LoadType.APPEND && previous?.complete == true && token == null) {
                            return MediatorResult.Success(true)
                        }
                        if (!canFetch()) return MediatorResult.Error(IOException("Server is unavailable"))
                        if (loadType == LoadType.REFRESH) tokens.clear()
                        val generation =
                            if (loadType == LoadType.REFRESH) {
                                BackupCoordinator.withStorageLock {
                                    if (!current()) throw CancellationException("Account storage changed")
                                    repository.beginRefresh(account.account.id, query.key, query.listType, query.parentName)
                                }
                            } else {
                                previous?.generation
                            }
                        val api = account.api
                        val response =
                            if (query.listType == CacheListType.COMMENT) {
                                val parent = query.parentName ?: return MediatorResult.Success(true)
                                val remoteParent =
                                    repository.resolveServerName(account.account.id, parent)
                                        ?: throw IOException("Parent memo is awaiting sync")
                                val page =
                                    api.listMemoComments(
                                        remoteParent,
                                        state.config.pageSize,
                                        token,
                                        api.resolveMemoOrderBy(MemoOrderBy.OLDEST),
                                    )
                                (page.memos ?: throw IOException("Server omitted the memo collection")) to page.nextPageToken
                            } else {
                                val creator =
                                    if (query.listType == CacheListType.USER || query.listType == CacheListType.ARCHIVED ||
                                        query.listType == CacheListType.SEARCH && !query.explore
                                    ) {
                                        api.buildMemoCreatorFilter(user) ?: throw IOException("User identity is unavailable")
                                    } else {
                                        null
                                    }
                                val filter =
                                    listOfNotNull(
                                        creator,
                                        if (query.listType == CacheListType.EXPLORE ||
                                            query.explore
                                        ) {
                                            "visibility in ['PUBLIC', 'PROTECTED']"
                                        } else {
                                            null
                                        },
                                        query.hashtag?.let { "tag in [${GsonProvider.gson.toJson(it.removePrefix("#"))}]" },
                                        query.serverFilter,
                                        query.search?.let(api::buildMemoSearchFilter),
                                    ).filter { it.isNotBlank() }
                                        .joinToString(" && ")
                                        .ifBlank { null }
                                val page =
                                    api.listMemos(
                                        state.config.pageSize,
                                        token,
                                        state = if (query.listType == CacheListType.ARCHIVED) "ARCHIVED" else null,
                                        orderBy =
                                            api.resolveMemoOrderBy(
                                                if (query.listType ==
                                                    CacheListType.USER
                                                ) {
                                                    MemoOrderBy.PINNED_DESC
                                                } else {
                                                    query.order
                                                },
                                            ),
                                        filter = filter,
                                    )
                                (page.memos ?: throw IOException("Server omitted the memo collection")) to page.nextPageToken
                            }
                        if (!current()) throw CancellationException("Account changed")
                        val next = response.second?.takeIf { it.isNotBlank() }
                        token?.let(tokens::add)
                        check(next == null || tokens.add(next)) { "Server repeated a pagination token" }
                        BackupCoordinator.withStorageLock {
                            if (!current()) throw CancellationException("Account storage changed")
                            repository.ingestPage(
                                account.account.id,
                                query.key,
                                query.listType,
                                response.first,
                                query.parentName,
                                nextPageToken = next,
                                refresh = loadType == LoadType.REFRESH,
                                expectedGeneration = generation,
                                expectedPageToken = token,
                            )
                        }
                        MediatorResult.Success(next == null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        MediatorResult.Error(error)
                    }
                }
            },
        pagingSourceFactory = {
            repository.pagingSource(
                account.account.id,
                query.key,
                when (query.listType) {
                    CacheListType.USER -> MemoOrderBy.PINNED_DESC
                    CacheListType.COMMENT -> MemoOrderBy.OLDEST
                    else -> query.order
                },
                sourceListType = localSource,
            )
        },
    ).flow.map { page ->
        val memos = page.map { requireNotNull(it.toMemo()) { "Invalid cached memo" } }
        if (localSource == null) memos else memos.filter { matchesLocalQuery(it, query, user) }
    }
}
