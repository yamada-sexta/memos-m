package org.example.memosm.viewmodel.manager

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.resolveMemoOrderBy
import org.example.memosm.model.Memo
import org.example.memosm.model.User
import retrofit2.HttpException

private const val TAG = "MemoListManager"

/** How long the first-page fetch waits for the user identity on cold start. */
private const val USER_READY_WAIT_MS = 5000L
private const val USER_READY_POLL_MS = 200L

/**
 * User-list order (mirrors the server's `pinned desc, display_time desc`):
 * pinned memos first, then newest. Applied after a network page is merged
 * with cached rows so the combined list follows one consistent order.
 */
val USER_MEMO_COMPARATOR: Comparator<Memo> =
    compareByDescending<Memo> { it.pinned == true }
        .thenByDescending { it.displayTime?.toEpochMilliseconds() ?: 0L }

/** Newest-first order for lists without pinning (explore/archived). */
private val MEMO_TIME_COMPARATOR: Comparator<Memo> =
    compareByDescending { it.displayTime?.toEpochMilliseconds() ?: 0L }

class UserMemoListManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val filterProvider: () -> String?,
    private val pageSizeProvider: () -> Int,
    cacheCallbacks: CacheCallbacks<Memo>? = null,
    protectedNamesProvider: (() -> Set<String>)? = null
) : BaseListManager<Memo>(
    scope,
    cacheCallbacks = cacheCallbacks,
    nameProvider = { it.name },
    protectedNamesProvider = protectedNamesProvider,
    sortComparator = USER_MEMO_COMPARATOR
) {

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Memo>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        val filter = waitForCreatorFilter(pageToken)
        if (filter == null && pageToken == null) {
            // User identity never became available (e.g. cold start offline
            // with no stored snapshot): keep the cached prefill on screen; the
            // fetch is retried once the user is fetched.
            Log.d(TAG, "UserMemoListManager fetch skipped (user identity not ready)")
            return Pair(emptyList(), null)
        }
        val pageSize = pageSizeProvider()
        Log.d(
            TAG,
            "UserMemoListManager fetch: filter=$filter, pageToken=$pageToken, pageSize=$pageSize"
        )

        try {
            val response = api.listMemos(
                pageSize = pageSize,
                pageToken = pageToken,
                filter = filter,
                orderBy = api.resolveMemoOrderBy(MemoOrderBy.PINNED_DESC)
            )
            Log.d(TAG, "UserMemoListManager success: count=${response.memos?.size ?: 0}")
            return Pair(response.memos ?: emptyList(), response.nextPageToken)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "UserMemoListManager failed: filter=$filter, error=$errorBody", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "UserMemoListManager failed: filter=$filter", e)
            throw e
        }
    }

    /**
     * The creator filter needs the user identity, which is fetched over the
     * network after account activation. On cold start it is briefly null; the
     * first-page fetch waits (bounded) for it instead of returning an empty
     * page or sending an unfiltered request (which would pull in other users'
     * public memos). Paging requests (pageToken != null) skip the wait: by the
     * time the user pages, the identity is always known.
     */
    private suspend fun waitForCreatorFilter(pageToken: String?): String? {
        if (pageToken != null) return filterProvider()
        var filter = filterProvider()
        var waitedMs = 0L
        while (filter == null && waitedMs < USER_READY_WAIT_MS) {
            delay(USER_READY_POLL_MS)
            filter = filterProvider()
            waitedMs += USER_READY_POLL_MS
        }
        return filter
    }
}

class ExploreMemoListManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val pageSizeProvider: () -> Int,
    cacheCallbacks: CacheCallbacks<Memo>? = null
) : BaseListManager<Memo>(
    scope,
    cacheCallbacks = cacheCallbacks,
    nameProvider = { it.name },
    sortComparator = MEMO_TIME_COMPARATOR
) {

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Memo>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        val filter = "visibility in ['PUBLIC', 'PROTECTED']"
        val pageSize = pageSizeProvider()
        Log.d(TAG, "ExploreMemoListManager fetch: filter=$filter, pageToken=$pageToken")

        try {
            val response = api.listMemos(
                pageSize = pageSize, pageToken = pageToken, filter = filter
            )
            Log.d(TAG, "ExploreMemoListManager success: count=${response.memos?.size ?: 0}")
            return Pair(response.memos ?: emptyList(), response.nextPageToken)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "ExploreMemoListManager failed: filter=$filter, error=$errorBody", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ExploreMemoListManager failed: filter=$filter", e)
            throw e
        }
    }
}

class ArchivedMemoListManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val currentUserProvider: () -> User?,
    private val pageSizeProvider: () -> Int,
    cacheCallbacks: CacheCallbacks<Memo>? = null
) : BaseListManager<Memo>(
    scope,
    cacheCallbacks = cacheCallbacks,
    nameProvider = { it.name },
    sortComparator = MEMO_TIME_COMPARATOR
) {

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Memo>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        val user = currentUserProvider() ?: return Pair(emptyList(), null)
        val filter = api.buildMemoCreatorFilter(user)
        if (filter.isNullOrBlank()) {
            Log.e(TAG, "ArchivedMemoListManager failed: user=${user.name}")
            return Pair(emptyList(), null)
        }

        val pageSize = pageSizeProvider()
        Log.d(TAG, "ArchivedMemoListManager fetch: filter=$filter, user=${user.name}")

        try {
            val response = api.listMemos(
                pageSize = pageSize, pageToken = pageToken, filter = filter, state = "ARCHIVED"
            )
            return Pair(response.memos ?: emptyList(), response.nextPageToken)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "ArchivedMemoListManager failed: filter=$filter, error=$errorBody", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "ArchivedMemoListManager failed: filter=$filter", e)
            throw e
        }
    }
}

/**
 * Structured local-search parameters (built alongside the server filter string
 * by the search UI) used to query the offline cache.
 */
data class LocalSearchFilter(
    val query: String = "",
    val tags: List<String> = emptyList(),
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    // True when searching from the Explore tab: the offline search must then
    // restrict itself to the EXPLORE cache rows (PUBLIC/PROTECTED only) so it
    // never surfaces the user's own private memos.
    val explore: Boolean = false
)

class SearchMemoListManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    private val pageSizeProvider: () -> Int,
    cacheCallbacks: CacheCallbacks<Memo>? = null,
    private val localSearchProvider: suspend (LocalSearchFilter) -> List<Memo> = { emptyList() }
) : BaseListManager<Memo>(
    scope,
    cacheCallbacks = cacheCallbacks,
    nameProvider = { it.name }
) {

    private var currentFilter: String? = null
    private var currentOrderBy: MemoOrderBy? = null
    private var currentLocalFilter: LocalSearchFilter = LocalSearchFilter()

    fun updateFilter(filter: String?, orderBy: MemoOrderBy?) {
        currentFilter = filter
        currentOrderBy = orderBy
    }

    fun updateLocalFilter(filter: LocalSearchFilter) {
        currentLocalFilter = filter
    }

    /**
     * Run the search against the local cache only (used when offline).
     */
    fun searchLocal() {
        scope.launch {
            try {
                val items = localSearchProvider(currentLocalFilter)
                _listState.update {
                    it.copy(
                        items = items,
                        isLoading = false,
                        nextPageToken = null,
                        isOffline = true,
                        errorMessage = null
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("SearchMemoListManager", "searchLocal failed", e)
                _listState.update {
                    it.copy(
                        isLoading = false, nextPageToken = null, isOffline = true
                    )
                }
            }
        }
    }

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Memo>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        if (currentFilter == null) return Pair(emptyList(), null)

        val pageSize = pageSizeProvider()
        Log.d(TAG, "SearchMemoListManager fetch: filter=$currentFilter")

        try {
            val response = api.listMemos(
                pageSize = pageSize,
                pageToken = pageToken,
                filter = currentFilter,
                orderBy = api.resolveMemoOrderBy(currentOrderBy)
            )
            return Pair(response.memos ?: emptyList(), response.nextPageToken)
        } catch (e: HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            Log.e(TAG, "SearchMemoListManager failed: filter=$currentFilter, error=$errorBody", e)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SearchMemoListManager failed: filter=$currentFilter", e)
            throw e
        }
    }
}
