package org.example.memosm.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Memo
import org.example.memosm.model.User
import org.example.memosm.viewmodel.MemoListState
import org.example.memosm.viewmodel.PaginatedListState

data class MemosListControls(
    val state: MemoListState,
    val fetch: (Boolean) -> Unit,
    val loadMore: () -> Unit,
    val updateItem: (Memo) -> Unit,
    val removeItem: (String) -> Unit,
    val insertItem: (Memo) -> Unit
)

@Composable
fun rememberUserMemosState(
    api: MemosApi?,
    repository: MemoCacheRepository,
    currentUser: User?,
    accountId: String?,
    pageSize: Int
): MemosListControls {
    val scope = rememberCoroutineScope()
    val holder = remember(api, accountId, currentUser) {
        ListStateHolder<Memo>(
            scope = scope,
            fetcher = { token ->
                val userId = currentUser?.name?.substringAfterLast("/") ?: ""
                val filter = if (userId.isNotEmpty()) "creator_id == $userId" else ""
                fetchMemoList(api, pageSize, token, filter)
            },
            cacheSaver = { items ->
                accountId?.let { repository.cacheMemos(it, CacheListType.USER, items) }
            },
            cacheLoader = {
                accountId?.let { repository.getCachedMemos(it, CacheListType.USER) } ?: emptyList()
            }
        )
    }

    LaunchedEffect(holder) { holder.fetch() }

    val paginatedState by holder.state.collectAsState()

    return MemosListControls(
        state = MemoListState(list = paginatedState),
        fetch = { refresh -> holder.fetch(refresh = refresh) },
        loadMore = { holder.loadMore() },
        updateItem = { memo -> holder.updateItem(memo) { it.name == memo.name } },
        removeItem = { name -> holder.removeItem { it.name == name } },
        insertItem = { memo -> holder.insertItem(memo) }
    )
}

@Composable
fun rememberExploreMemosState(
    api: MemosApi?,
    repository: MemoCacheRepository,
    accountId: String?,
    pageSize: Int
): MemosListControls {
    val scope = rememberCoroutineScope()
    val holder = remember(api, accountId) {
        ListStateHolder<Memo>(
            scope = scope,
            fetcher = { token -> fetchMemoList(api, pageSize, token, "visibilities == ['PUBLIC']") },
            cacheSaver = { items ->
                accountId?.let { repository.cacheMemos(it, CacheListType.EXPLORE, items) }
            },
            cacheLoader = {
                accountId?.let { repository.getCachedMemos(it, CacheListType.EXPLORE) } ?: emptyList()
            }
        )
    }

    LaunchedEffect(holder) { holder.fetch() }

    val paginatedState by holder.state.collectAsState()

    return MemosListControls(
        state = MemoListState(list = paginatedState),
        fetch = { refresh -> holder.fetch(refresh = refresh) },
        loadMore = { holder.loadMore() },
        updateItem = { memo -> holder.updateItem(memo) { it.name == memo.name } },
        removeItem = { name -> holder.removeItem { it.name == name } },
        insertItem = { memo -> holder.insertItem(memo) }
    )
}

@Composable
fun rememberArchivedMemosState(
    api: MemosApi?,
    repository: MemoCacheRepository,
    currentUser: User?,
    accountId: String?,
    pageSize: Int
): MemosListControls {
    val scope = rememberCoroutineScope()
    val holder = remember(api, accountId, currentUser) {
        ListStateHolder<Memo>(
            scope = scope,
            fetcher = { token ->
                val userId = currentUser?.name?.substringAfterLast("/") ?: ""
                val filter = if (userId.isNotEmpty()) "creator_id == $userId && row_status == 'ARCHIVED'" else "row_status == 'ARCHIVED'"
                fetchMemoList(api, pageSize, token, filter)
            },
            cacheSaver = { items ->
                accountId?.let { repository.cacheMemos(it, CacheListType.ARCHIVED, items) }
            },
            cacheLoader = {
                accountId?.let { repository.getCachedMemos(it, CacheListType.ARCHIVED) } ?: emptyList()
            }
        )
    }

    LaunchedEffect(holder) { holder.fetch() }

    val paginatedState by holder.state.collectAsState()

    return MemosListControls(
        state = MemoListState(list = paginatedState),
        fetch = { refresh -> holder.fetch(refresh = refresh) },
        loadMore = { holder.loadMore() },
        updateItem = { memo -> holder.updateItem(memo) { it.name == memo.name } },
        removeItem = { name -> holder.removeItem { it.name == name } },
        insertItem = { memo -> holder.insertItem(memo) }
    )
}
