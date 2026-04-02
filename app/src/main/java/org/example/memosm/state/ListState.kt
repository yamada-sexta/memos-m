package org.example.memosm.state

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Memo
import org.example.memosm.model.User
import org.example.memosm.viewmodel.PaginatedListState

/**
 * Functional approach to fetch memo lists.
 */
suspend fun fetchMemoList(
    api: MemosApi?,
    pageSize: Int,
    pageToken: String?,
    filter: String
): Pair<List<Memo>, String?> {
    if (api == null) return emptyList<Memo>() to null

    val response = api.listMemos(
        pageSize = pageSize,
        pageToken = pageToken,
        filter = filter.ifBlank { null }
    )
    return (response.memos ?: emptyList()) to response.nextPageToken
}

/**
 * Common State Holder for Lists
 */
class ListStateHolder<T>(
    private val scope: CoroutineScope,
    private val fetcher: suspend (String?) -> Pair<List<T>, String?>,
    private val cacheSaver: suspend (List<T>) -> Unit,
    private val cacheLoader: suspend () -> List<T>
) {
    private val _state = MutableStateFlow(PaginatedListState<T>())
    val state: StateFlow<PaginatedListState<T>> = _state.asStateFlow()

    fun fetch(refresh: Boolean = false, softRefresh: Boolean = false) {
        if (refresh && !softRefresh) {
            _state.value = PaginatedListState()
        }

        if (_state.value.isLoading) return
        if (!refresh && _state.value.items.isNotEmpty()) return

        loadInternal(null)
    }

    fun loadMore() {
        if (_state.value.isLoading || _state.value.nextPageToken.isNullOrBlank()) return
        loadInternal(_state.value.nextPageToken)
    }

    private fun loadInternal(pageToken: String?) {
        scope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, isOffline = false)
                val (newItems, nextToken) = fetcher(pageToken)
                val updatedItems = if (pageToken == null) newItems else _state.value.items + newItems

                _state.value = _state.value.copy(
                    items = updatedItems,
                    nextPageToken = nextToken,
                    isLoading = false,
                    errorMessage = null
                )

                if (pageToken == null) cacheSaver(updatedItems)
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.toString()
                if (pageToken == null) {
                    try {
                        val cachedItems = cacheLoader()
                        if (cachedItems.isNotEmpty()) {
                            _state.value = _state.value.copy(
                                items = cachedItems,
                                isLoading = false,
                                isOffline = true,
                                errorMessage = errorMessage
                            )
                            return@launch
                        }
                    } catch (cacheError: Exception) {
                        Log.e("ListStateHolder", "Error loading cache", cacheError)
                    }
                }
                _state.value = _state.value.copy(isLoading = false, errorMessage = errorMessage)
            }
        }
    }

    fun updateItem(item: T, isSame: (T) -> Boolean) {
        _state.value = _state.value.copy(
            items = _state.value.items.map { if (isSame(it)) item else it }
        )
    }

    fun removeItem(isSame: (T) -> Boolean) {
        _state.value = _state.value.copy(
            items = _state.value.items.filterNot(isSame)
        )
    }

    fun insertItem(item: T) {
        _state.value = _state.value.copy(
             items = listOf(item) + _state.value.items
        )
    }
}
