package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CoroutineScope
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Memo

private const val COMMENT_PAGE_SIZE = 100 // Comments usually loaded all or in large batches

class CommentListManager(
    scope: CoroutineScope,
    private val apiProvider: () -> MemosApi?,
    cacheCallbacks: CacheCallbacks<Memo>? = null,
    private val isOnlineProvider: () -> Boolean = { true }
) : BaseListManager<Memo>(
    scope,
    cacheCallbacks = cacheCallbacks,
    nameProvider = { it.name }
) {

    private var _currentMemoName: String? = null

    val currentMemoName: String?
        get() = _currentMemoName

    fun setMemo(memoName: String) {
        if (_currentMemoName != memoName) {
            _currentMemoName = memoName
            reset()
            // Offline: skip the network round-trip (and its timeout) and serve
            // the local comment cache directly, mirroring the memo lists.
            if (isOnlineProvider()) fetch() else loadFromCache()
        }
    }

    /**
     * Load comments from the local cache for the currently selected memo.
     */
    override fun loadFromCache() {
        if (currentMemoName == null) return
        super.loadFromCache()
    }

    override suspend fun fetchFromApi(pageToken: String?): Pair<List<Memo>, String?> {
        val api = apiProvider() ?: return Pair(emptyList(), null)
        val parent = currentMemoName ?: return Pair(emptyList(), null)

        val response = api.listMemoComments(
            memo = parent,
            pageSize = COMMENT_PAGE_SIZE,
            pageToken = pageToken
        )

        // Sort comments by display_time asc (oldest first) usually
        // API might default to desc. We can sort client side or use sort param if available.
        // For now, returning as is.
        val sorted = response.memos?.sortedBy { it.createTime } ?: emptyList()

        return Pair(sorted, response.nextPageToken)
    }
}
