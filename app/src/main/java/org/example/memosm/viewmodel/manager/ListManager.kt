package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
     * Called on successful fetch with the merged list of items (the network
     * page plus any cached items it didn't mention). The cache always merges:
     * a page-1 refresh must not wipe the full-history cache built by the
     * pre-downloader, so implementations upsert the given items rather than
     * replacing the whole cache.
     */
    val onFetchSuccess: suspend (List<T>) -> Unit = {},

    /**
     * Called on fetch failure to retrieve cached data.
     * Implementation should return cached items or empty list. [limit]
     * bounds the result (null = all rows): the prefill passes a small cap so
     * a large pre-downloaded history is not fully deserialized just to show
     * the first screen, while offline loading requests everything.
     */
    val getCachedData: suspend (limit: Int?) -> List<T> = { emptyList() }
)

abstract class BaseListManager<T>(
    protected val scope: CoroutineScope,
    private val initialState: PaginatedListState<T> = PaginatedListState(),
    private val cacheCallbacks: CacheCallbacks<T>? = null,
    private val nameProvider: ((T) -> String?)? = null,
    private val protectedNamesProvider: (() -> Set<String>)? = null,
    private val prefillLimit: Int = 30,
    /**
     * Order applied after a network page is merged with cached items. Server
     * pages arrive in server order (e.g. pinned-first) while cached rows are
     * ordered by their cached display order; without a merge-time sort the two
     * segments would keep their own orders and the list would be inconsistent
     * (e.g. the newest memo hidden below a pinned one). Null keeps the
     * manager's natural order (searches, comments, ...).
     */
    private val sortComparator: Comparator<T>? = null
) : ListManager<T> {

    protected val _listState = MutableStateFlow(initialState)
    override val listState: StateFlow<PaginatedListState<T>> = _listState.asStateFlow()

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

        // Local-first: on refresh, keep the current items visible (no blank
        // screen) while the network round-trip runs; the fresh page replaces
        // or merges into the list in loadInternal. A hard reset() here would
        // flash an empty list and drop the scroll position on every refresh.
        // isLoading is set synchronously (before the coroutine below launches)
        // so two back-to-back fetch() calls cannot both pass the guard above.
        if (refresh && !softRefresh) {
            _listState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            _listState.update { it.copy(isLoading = true) }
        }

        loadInternal(pageToken = null)
    }

    override fun loadMore() {
        android.util.Log.d(
            TAG,
            "loadMore: isLoading=${_listState.value.isLoading}, nextToken=${_listState.value.nextPageToken}"
        )
        if (_listState.value.isLoading || _listState.value.nextPageToken.isNullOrBlank()) return
        // Set isLoading synchronously so a second loadMore() before the
        // launched coroutine runs cannot double-fetch the same page.
        _listState.update { it.copy(isLoading = true) }
        loadInternal(pageToken = _listState.value.nextPageToken)
    }

    override fun reset() {
        android.util.Log.d(TAG, "reset")
        _listState.update { initialState }
    }

    /**
     * Load items directly from the local cache without hitting the network.
     * Used when the device is known to be offline (avoids long timeouts).
     * Marks the list as [PaginatedListState.showingCached] so a later network
     * refresh merges instead of shrinking the cached history.
     */
    open fun loadFromCache() {
        if (cacheCallbacks == null) return
        scope.launch {
            try {
                val cachedItems = cacheCallbacks.getCachedData(null)
                if (cachedItems.isNotEmpty()) {
                    _listState.update { it.copy(
                        items = sortIfNeeded(cachedItems),
                        isLoading = false,
                        nextPageToken = null,
                        isOffline = true,
                        showingCached = true
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "loadFromCache error", e)
            }
        }
    }

    /** Apply the manager's sort order (when configured) to a list of items. */
    private fun sortIfNeeded(items: List<T>): List<T> =
        if (sortComparator != null) items.sortedWith(sortComparator) else items

    // Helper to allow external updates (e.g. CRUD operations updating the list locally)
    fun updateState(transform: (PaginatedListState<T>) -> PaginatedListState<T>) {
        _listState.update { transform(it) }
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

                // Local-first: on the first page with nothing on screen, show
                // the cached data immediately (without the offline badge) while
                // the network fetch runs in the background. The server page is
                // then merged below, so the list neither blanks out on slow
                // networks nor shrinks from the full cached history.
                // The prefill is capped at [prefillLimit]: the cache can hold the
                // entire pre-downloaded history (thousands of rows), and merging
                // all of it into the visible list would render a huge unsorted
                // list and trigger attachment preloads for everything.
                val np = nameProvider
                var cachedPrefill: List<T> = emptyList()
                val canPrefill = pageToken == null &&
                    np != null && cacheCallbacks != null &&
                    _listState.value.items.isEmpty()
                if (canPrefill) {
                    cachedPrefill = try {
                        // Limit pushed into the cache query: only the first
                        // [prefillLimit] rows are read and deserialized, so a
                        // pre-downloaded history of thousands of memos does
                        // not delay first paint.
                        cacheCallbacks!!.getCachedData(prefillLimit)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "cache prefill failed", e)
                        emptyList()
                    }
                    if (cachedPrefill.isNotEmpty()) {
                        _listState.update { it.copy(
                            items = sortIfNeeded(cachedPrefill),
                            isLoading = true,
                            isOffline = false,
                            errorMessage = null,
                            showingCached = true
                        ) }
                    }
                }

                _listState.update { it.copy(isLoading = true, isOffline = false) }

                val (newItems, rawNextToken) = fetchFromApi(pageToken)
                val nextToken = if (rawNextToken.isNullOrBlank()) null else rawNextToken

                val processedItems = newItems.map { processItem(it) }

                android.util.Log.d(
                    TAG,
                    "loadInternal: fetched ${newItems.size} items, rawToken='$rawNextToken' -> nextToken=$nextToken"
                )

                val updatedItems = if (pageToken == null) {
                    // Merge: keep cached items the first server page doesn't
                    // mention so the full-history cache stays visible while the
                    // user pages through the rest online. The merge pool is the
                    // prefill, or the currently shown items when they were
                    // served from cache (e.g. after an offline session).
                    val mergePool = when {
                        cachedPrefill.isNotEmpty() -> cachedPrefill
                        np != null && _listState.value.showingCached -> _listState.value.items
                        else -> emptyList()
                    }
                    if (mergePool.isNotEmpty() && np != null) {
                        val networkNames =
                            processedItems.mapNotNull(np!!).toHashSet()
                        val merged = processedItems + mergePool.filter {
                            np!!(it) !in networkNames
                        }
                        // The merge concatenates the server page (server order)
                        // with cache rows (cached display order); re-sort so
                        // the combined list follows one consistent order
                        // (pinned first, then newest).
                        sortIfNeeded(merged)
                    } else {
                        processedItems
                    }
                } else if (np == null) {
                    // No name provider: items cannot be matched by name, so
                    // append the new page plainly without the merge logic.
                    _listState.value.items + processedItems
                } else {
                    // Refresh cached placeholders with the fresher network
                    // versions (keeping their position), then append genuinely
                    // new items. Discarding duplicates here would leave stale
                    // cached content on screen for memos the server updated.
                    val existing = _listState.value.items
                    val existingNames = existing.mapNotNull(np).toHashSet()
                    val fresh = processedItems.filter {
                        np(it) !in existingNames
                    }
                    val replaced = existing.map { item ->
                        val n = np(item)
                        if (n != null) {
                            processedItems.firstOrNull { np(it) == n } ?: item
                        } else {
                            item
                        }
                    }
                    replaced + fresh
                }

                // Keep showingCached until every page has been confirmed by the
                // network: a mid-pagination pull-to-refresh must merge (not
                // shrink), while a fully-confirmed list refreshes plainly.
                // (cachedPrefill is only non-empty on the first page, so this
                // covers both the first-page and pagination cases.)
                val listStillHasCacheItems =
                    cachedPrefill.isNotEmpty() || _listState.value.showingCached
                val resolvedShowingCached = listStillHasCacheItems && nextToken != null

                // Atomic update (CAS): merge any items that were added locally
                // (e.g. an optimistic create) while the network call was in
                // flight, so the final write cannot clobber them. Items with a
                // pending offline UPDATE keep their local version too, so a
                // refresh racing the op push cannot regress them to the stale
                // server content.
                _listState.update { current ->
                    val finalItems = if (np != null && pageToken == null) {
                        val updatedNames = updatedItems.mapNotNull(np!!).toHashSet()
                        val localOnly = current.items.filter { np!!(it) !in updatedNames }
                        val protectedNames = protectedNamesProvider?.invoke().orEmpty()
                        val merged = if (protectedNames.isEmpty()) {
                            updatedItems
                        } else {
                            updatedItems.map { item ->
                                val n = np!!(item)
                                if (n != null && n in protectedNames) {
                                    current.items.firstOrNull { np!!(it) == n } ?: item
                                } else {
                                    item
                                }
                            }
                        }
                        val mergedNames = merged.mapNotNull(np!!).toHashSet()
                        // Local additions (e.g. an optimistic create made while
                        // the fetch was in flight) are appended last; re-sort
                        // so they land in their natural position (newest on top).
                        sortIfNeeded(merged + localOnly.filter { np!!(it) !in mergedNames })
                    } else {
                        updatedItems
                    }
                    current.copy(
                        items = finalItems,
                        nextPageToken = nextToken,
                        isLoading = false,
                        isOffline = false,
                        errorMessage = null,  // Clear any previous error on success
                        showingCached = resolvedShowingCached
                    )
                }

                // Cache the data on successful fetch. The callback always
                // merges into the cache (see CacheCallbacks.onFetchSuccess).
                if (cacheCallbacks != null) {
                    try {
                        cacheCallbacks.onFetchSuccess(updatedItems)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error caching data", e)
                    }
                }

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "loadInternal error", e)

                // Keep technical exception details in logs; UI receives a generic, localizable message.
                val errorMessage = null

                // On failure, try to load from cache (only for initial fetch)
                if (pageToken == null && cacheCallbacks != null) {
                    try {
                        val cachedItems = cacheCallbacks.getCachedData(null)
                        if (cachedItems.isNotEmpty()) {
                            android.util.Log.d(
                                TAG, "Loaded ${cachedItems.size} items from cache"
                            )
                            _listState.update { it.copy(
                                items = sortIfNeeded(cachedItems),
                                isLoading = false,
                                nextPageToken = null, // All cached pages are already served
                                isOffline = true,  // Mark as offline/cached data
                                errorMessage = errorMessage
                            ) }
                            return@launch
                        }
                    } catch (cacheError: CancellationException) {
                        throw cacheError
                    } catch (cacheError: Exception) {
                        android.util.Log.e(
                            TAG, "Error loading from cache", cacheError
                        )
                    }
                }

                _listState.update {
                    it.copy(isLoading = false, nextPageToken = null, errorMessage = errorMessage)
                }
            }
        }
    }
}
