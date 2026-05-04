package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.viewmodel.PaginatedListState

private const val TAG = "ListManager"

interface ListManager<T> {
    val listState: StateFlow<PaginatedListState<T>>

    // Refresh: true to reload from page 1, false to fetch if empty
    // SoftRefresh: when true, keeps existing items visible during refresh (no reset)
    fun fetch(refresh: Boolean = false, softRefresh: Boolean = false)

    // Load next page if available
    fun loadMore()

    // Reset the list completely (e.g. on logout)
    fun reset()
}

/**
 * Cache callbacks for offline support.
 */
data class CacheCallbacks<T>(
    /**
     * Called on successful fetch with the full list of items.
     * Implementation should save these to local cache.
     */
    val onFetchSuccess: suspend (List<T>) -> Unit = {},

    /**
     * Called on fetch failure to retrieve cached data.
     * Implementation should return cached items or empty list.
     */
    val getCachedData: suspend () -> List<T> = { emptyList() }
)

abstract class BaseListManager<T>(
    private val scope: CoroutineScope,
    private val initialState: PaginatedListState<T> = PaginatedListState(),
    private val cacheCallbacks: CacheCallbacks<T>? = null
) : ListManager<T> {

    protected val _listState = MutableStateFlow(initialState)
    override val listState: StateFlow<PaginatedListState<T>> = _listState.asStateFlow()
    private var lastRequestedPageToken: String? = null

    // Abstract methods to be implemented by specific managers
    // Returns a Pair of (Items, NextPageToken)
    protected abstract suspend fun fetchFromApi(pageToken: String?): Pair<List<T>, String?>

    // Optional: Process item before adding to state (e.g. resolve relative URLs)
    protected open suspend fun processItem(item: T): T = item

    override fun fetch(refresh: Boolean, softRefresh: Boolean) {
        android.util.Log.d(
            TAG,
            "fetch: refresh=$refresh, softRefresh=$softRefresh, currentItems=${_listState.value.items.size}"
        )
        if (refresh && !softRefresh) {
            reset()
        }

        // If already loading, skip
        if (_listState.value.isLoading) {
            android.util.Log.d(TAG, "fetch: already loading, skipping")
            return
        }

        // If not refreshing and we already have items, we don't need to fetch page 1 again.
        // The user should use loadMore() for the next page.
        // This prevents resetting the list to page 1 when navigating back to a screen that has data.
        if (!refresh && _listState.value.items.isNotEmpty()) {
            android.util.Log.d(
                TAG, "fetch: items exist and not refreshing, skipping"
            )
            return
        }

        loadInternal(pageToken = null)
    }

    override fun loadMore() {
        val nextToken = _listState.value.nextPageToken?.takeIf { it.isNotBlank() } ?: return
        android.util.Log.d(
            TAG,
            "loadMore: isLoading=${_listState.value.isLoading}, nextToken=$nextToken, lastRequested=$lastRequestedPageToken"
        )
        if (_listState.value.isLoading || nextToken == lastRequestedPageToken) return
        lastRequestedPageToken = nextToken
        loadInternal(pageToken = nextToken)
    }

    override fun reset() {
        android.util.Log.d(TAG, "reset")
        lastRequestedPageToken = null
        _listState.value = initialState
    }

    // Helper to allow external updates (e.g. CRUD operations updating the list locally)
    fun updateState(transform: (PaginatedListState<T>) -> PaginatedListState<T>) {
        _listState.value = transform(_listState.value)
    }

    // Insert or update an item. 
    // isSameItem checks identity (e.g. ID match).
    // comparator (optional) sorts the list after insertion.
    fun upsert(
        item: T, isSameItem: (T) -> Boolean, comparator: Comparator<T>? = null
    ) {
        updateState { state ->
            val existingIndex = state.items.indexOfFirst(isSameItem)
            val newItems = if (existingIndex != -1) {
                // Replace existing
                state.items.toMutableList().apply { set(existingIndex, item) }
            } else {
                // Add new
                (state.items + item)
            }

            val sortedItems = if (comparator != null) {
                newItems.sortedWith(comparator)
            } else {
                newItems
            }
            state.copy(items = sortedItems)
        }
    }

    // Replace an item only if it exists.
    fun replace(item: T, isSameItem: (T) -> Boolean) {
        updateState { state ->
            val newItems = state.items.map { if (isSameItem(it)) item else it }
            state.copy(items = newItems)
        }
    }

    // Remove items matching the predicate.
    fun remove(predicate: (T) -> Boolean) {
        updateState { state ->
            state.copy(items = state.items.filterNot(predicate))
        }
    }

    private fun loadInternal(pageToken: String?) {
        scope.launch {
            try {
                android.util.Log.d(TAG, "loadInternal: pageToken=$pageToken")
                _listState.value = _listState.value.copy(isLoading = true, isOffline = false)

                val (newItems, rawNextToken) = fetchFromApi(pageToken)
                val nextToken = if (rawNextToken.isNullOrBlank()) null else rawNextToken

                val processedItems = newItems.map { processItem(it) }

                android.util.Log.d(
                    TAG,
                    "loadInternal: fetched ${newItems.size} items, rawToken='$rawNextToken' -> nextToken=$nextToken"
                )

                val updatedItems =
                    if (pageToken == null) processedItems else _listState.value.items + processedItems

                if (pageToken == null) {
                    lastRequestedPageToken = null
                }

                _listState.value = _listState.value.copy(
                    items = updatedItems,
                    nextPageToken = nextToken,
                    isLoading = false,
                    isOffline = false,
                    errorMessage = null  // Clear any previous error on success
                )

                // Cache the data on successful fetch (only for initial page to avoid partial caches)
                if (pageToken == null && cacheCallbacks != null) {
                    try {
                        cacheCallbacks.onFetchSuccess(updatedItems)
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error caching data", e)
                    }
                }

            } catch (e: Exception) {
                if (!pageToken.isNullOrBlank()) {
                    lastRequestedPageToken = null
                }
                e.printStackTrace()
                android.util.Log.e(TAG, "loadInternal error", e)

                // Extract a user-friendly error message
                val errorMessage = e.message ?: e.toString()

                // On failure, try to load from cache (only for initial fetch)
                if (pageToken == null && cacheCallbacks != null) {
                    try {
                        val cachedItems = cacheCallbacks.getCachedData()
                        if (cachedItems.isNotEmpty()) {
                            android.util.Log.d(
                                TAG, "Loaded ${cachedItems.size} items from cache"
                            )
                            _listState.value = _listState.value.copy(
                                items = cachedItems,
                                isLoading = false,
                                isOffline = true,  // Mark as offline/cached data
                                errorMessage = errorMessage
                            )
                            return@launch
                        }
                    } catch (cacheError: Exception) {
                        android.util.Log.e(
                            TAG, "Error loading from cache", cacheError
                        )
                    }
                }

                _listState.value =
                    _listState.value.copy(isLoading = false, errorMessage = errorMessage)
            }
        }
    }
}
