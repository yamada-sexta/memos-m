package org.example.memosm.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApi
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.toDraft
import org.example.memosm.viewmodel.delegates.AppSettingsDelegate
import org.example.memosm.viewmodel.delegates.AppSettingsDelegateImpl
import org.example.memosm.viewmodel.delegates.DraftDelegate
import org.example.memosm.viewmodel.delegates.DraftDelegateImpl
import org.example.memosm.viewmodel.delegates.MemoActionDelegate
import org.example.memosm.viewmodel.delegates.MemoActionDelegateImpl
import org.example.memosm.viewmodel.delegates.MemoListUpdater
import org.example.memosm.viewmodel.delegates.ShortcutDelegate
import org.example.memosm.viewmodel.delegates.ShortcutDelegateImpl
import org.example.memosm.viewmodel.delegates.UserDelegate
import org.example.memosm.viewmodel.delegates.UserDelegateImpl
import org.example.memosm.viewmodel.delegates.WebhookDelegate
import org.example.memosm.viewmodel.delegates.WebhookDelegateImpl
import org.example.memosm.viewmodel.manager.ArchivedMemoListManager
import org.example.memosm.viewmodel.manager.AttachmentManager
import org.example.memosm.viewmodel.manager.CacheCallbacks
import org.example.memosm.viewmodel.manager.CommentListManager
import org.example.memosm.viewmodel.manager.ExploreMemoListManager
import org.example.memosm.viewmodel.manager.SearchMemoListManager
import org.example.memosm.viewmodel.manager.UserMemoListManager
import org.example.memosm.model.UserNotification

class MemosViewModel(
    private val dataStoreManager: DataStoreManager,
    private val memoCacheRepository: MemoCacheRepository,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemosUiState())
    val uiState: StateFlow<MemosUiState> = _uiState.asStateFlow()

    private var api: MemosApi? = null
    private var currentHttpClient: OkHttpClient? = null
    private var currentBaseUrl: String? = null

    // Managers
    private val userMemoManager: UserMemoListManager = UserMemoListManager(
        scope = viewModelScope,
        apiProvider = { api },
        filterProvider = {
            val user = _uiState.value.session.currUser
            val userId = user?.name?.substringAfterLast("/") ?: ""

            // Use creator_id and row_status
            val base = if (userId.isNotEmpty()) {
                "creator_id == $userId"
            } else {
                ""
            }

            val shortcut = _uiState.value.userMemoList.selectedShortcut
            val hashtag = _uiState.value.userMemoList.selectedHashtag

            if (shortcut != null && !shortcut.filter.isNullOrBlank()) {
                "$base && ${shortcut.filter}"
            } else if (hashtag != null) {
                val tagName = hashtag.removePrefix("#")
                "$base && tag in [\"$tagName\"]"
            } else {
                base
            }
        },
        pageSizeProvider = { _uiState.value.appSettings.pageSize },
        cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
            val accountId =
                _uiState.value.accounts.find { it.isActive }?.id ?: return@CacheCallbacks
            memoCacheRepository.cacheMemos(
                accountId, CacheListType.USER, memos
            )
        }, getCachedData = {
            val accountId = _uiState.value.accounts.find { it.isActive }?.id
                ?: return@CacheCallbacks emptyList()
            memoCacheRepository.getCachedMemos(
                accountId, CacheListType.USER
            )
        })
    )

    private val exploreMemoManager: ExploreMemoListManager =
        ExploreMemoListManager(
            scope = viewModelScope,
            apiProvider = { api },
            pageSizeProvider = { _uiState.value.appSettings.pageSize },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
                val accountId =
                    _uiState.value.accounts.find { it.isActive }?.id ?: return@CacheCallbacks
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.EXPLORE, memos
                )
            }, getCachedData = {
                val accountId = _uiState.value.accounts.find { it.isActive }?.id
                    ?: return@CacheCallbacks emptyList()
                memoCacheRepository.getCachedMemos(
                    accountId, CacheListType.EXPLORE
                )
            })
        )

    private val archivedMemoManager: ArchivedMemoListManager =
        ArchivedMemoListManager(
            scope = viewModelScope,
            apiProvider = { api },
            currentUserProvider = { _uiState.value.session.currUser },
            pageSizeProvider = { _uiState.value.appSettings.pageSize },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
                val accountId =
                    _uiState.value.accounts.find { it.isActive }?.id ?: return@CacheCallbacks
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.ARCHIVED, memos
                )
            }, getCachedData = {
                val accountId = _uiState.value.accounts.find { it.isActive }?.id
                    ?: return@CacheCallbacks emptyList()
                memoCacheRepository.getCachedMemos(
                    accountId, CacheListType.ARCHIVED
                )
            })
        )

    private val searchMemoManager: SearchMemoListManager = SearchMemoListManager(
        viewModelScope,
        { api },
        pageSizeProvider = { _uiState.value.appSettings.pageSize })

    private val commentManager: CommentListManager = CommentListManager(viewModelScope, { api })

    private val attachmentManager: AttachmentManager =
        AttachmentManager(
            scope = viewModelScope, apiProvider = { api }, streamingApiProvider = {
                currentHttpClient?.let {
                    StreamingAttachmentApi(it, currentBaseUrl ?: "")
                }
            }, cacheCallbacks = CacheCallbacks(onFetchSuccess = { attachments ->
                val accountId =
                    _uiState.value.accounts.find { it.isActive }?.id ?: return@CacheCallbacks
                memoCacheRepository.cacheAttachments(accountId, attachments)
            }, getCachedData = {
                val accountId = _uiState.value.accounts.find { it.isActive }?.id
                    ?: return@CacheCallbacks emptyList()
                memoCacheRepository.getCachedAttachments(accountId)
            }), initialCellWidth = _uiState.value.attachmentList.cellWidth
        )

    private var collectionJob: Job? = null
    private var cacheCollectionJob: Job? = null

    private val _attachmentAspectRatios =
        MutableStateFlow<Map<Float, Map<String, Float>>>(emptyMap())

    // Delegates
    val userDelegate: UserDelegate = UserDelegateImpl(
        viewModelScope, _uiState, { api }, dataStoreManager
    ) { account ->
        switchAccountInternal(account)
    }

    val shortcutDelegate: ShortcutDelegate = ShortcutDelegateImpl(
        viewModelScope,
        _uiState,
        { api },
        { userMemoManager.fetch(refresh = true) })

    val webhookDelegate: WebhookDelegate = WebhookDelegateImpl(
        viewModelScope, _uiState
    ) { api }


    val appSettingsDelegate: AppSettingsDelegate = AppSettingsDelegateImpl(
        viewModelScope, _uiState, dataStoreManager
    ) {
        userMemoManager.fetch(refresh = true)
        exploreMemoManager.fetch(refresh = true)
    }

    val draftDelegate: DraftDelegate = DraftDelegateImpl(
        viewModelScope,
        _uiState,
        memoCacheRepository,
        { api }
    ) {
        userMemoManager.fetch(refresh = true)
        archivedMemoManager.fetch(refresh = true)
        exploreMemoManager.fetch(refresh = true)
    }

    private val memoListUpdater = object : MemoListUpdater {
        override fun updateMemoInLists(memo: Memo) {
            updateMemoInState(memo)
        }

        override fun removeMemoFromLists(memoName: String) {
            val isSame = { m: Memo -> m.name == memoName }
            userMemoManager.remove(isSame)
            exploreMemoManager.remove(isSame)
            archivedMemoManager.remove(isSame)
            searchMemoManager.remove(isSame)
            commentManager.remove(isSame)
        }

        override fun refreshUserMemos() {
            userMemoManager.fetch(refresh = true)
        }

        override fun handleMemoStateChange(memo: Memo, updated: Memo) {
            val oldState = memo.state ?: "NORMAL"
            val newState = updated.state ?: "NORMAL"
            val comparator = compareByDescending<Memo> { it.displayTime }

            if (oldState != newState) {
                if (newState == "ARCHIVED") {
                    // Move from User/Explore -> Archived
                    val isSame = { m: Memo -> m.name == memo.name }
                    userMemoManager.remove(isSame)
                    exploreMemoManager.remove(isSame)

                    val isSameUpdated = { m: Memo -> m.name == updated.name }
                    archivedMemoManager.upsert(updated, isSameUpdated, comparator)
                } else if (newState == "NORMAL") {
                    // Move from Archived -> User (and maybe Explore if public, but keep simple for now)
                    val isSame = { m: Memo -> m.name == memo.name }
                    archivedMemoManager.remove(isSame)

                    val isSameUpdated = { m: Memo -> m.name == updated.name }
                    userMemoManager.upsert(updated, isSameUpdated, comparator)
                }
            }
        }
    }

    val memoActionDelegate: MemoActionDelegate = MemoActionDelegateImpl(
        viewModelScope,
        _uiState,
        { api },
        memoListUpdater,
        draftDelegate,
        { attachmentManager },
        { commentManager })

    init {
        userDelegate.updateCurrentAccountInList()
        appSettingsDelegate.loadPageSize()
        appSettingsDelegate.loadHeaderScale()

        startStateCollection()
    }

    private suspend fun createApi(
        baseUrl: String, token: String
    ): MemosApi {
        val authInterceptor = AuthInterceptor(token)

        currentHttpClient = okHttpClient.newBuilder().addInterceptor(authInterceptor).build()
        currentBaseUrl = baseUrl

        return MemosApiFactory.create(baseUrl, currentHttpClient!!)
    }

    // Keep this one as it's used by the delegate directly above
    private fun switchAccountInternal(account: Account) {
        // Re-create Api and Managers
        viewModelScope.launch {
            api = createApi(account.hostUrl, account.accessToken)

            observeLocalCache(account.id)
            fetchCurrentUser()
            exploreMemoManager.fetch()
            if (account.user != null) {
                userMemoManager.fetch()
            }
            draftDelegate.loadDraftsForAccount(account.id)
        }
    }

    private fun startStateCollection() {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            combine(
                combine(
                    userMemoManager.listState,
                    exploreMemoManager.listState,
                    archivedMemoManager.listState
                ) { u, e, a -> Triple(u, e, a) },
                combine(
                    searchMemoManager.listState,
                    commentManager.listState,
                    attachmentManager.listState
                ) { s, c, at -> Triple(s, c, at) },
                attachmentManager.cellWidth,
                _attachmentAspectRatios
            ) { (userMemos, exploreMemos, archivedMemos), (searchMemos, comments, attachments), cellWidth, aspectRatios ->
                Log.d(
                    "MemosDebug",
                    "ViewModel: StateCollection. aspectRatiosCount=${aspectRatios.values.sumOf { it.size }}"
                )
                val mergedUserMemos = mergeUnsyncedMemos(userMemos.items, archived = false)
                val mergedArchivedMemos = mergeUnsyncedMemos(archivedMemos.items, archived = true)
                _uiState.value.copy(
                    userMemoList = _uiState.value.userMemoList.copy(
                        list = userMemos.copy(items = mergedUserMemos)
                    ),
                    exploreMemoList = _uiState.value.exploreMemoList.copy(list = exploreMemos),
                    archivedMemoList = _uiState.value.archivedMemoList.copy(
                        list = archivedMemos.copy(items = mergedArchivedMemos)
                    ),
                    searchMemoList = _uiState.value.searchMemoList.copy(list = searchMemos),
                    detailPane = _uiState.value.detailPane.copy(comments = comments),
                    attachmentList = AttachmentListState(
                        list = attachments,
                        cellWidth = cellWidth,
                        aspectRatios = aspectRatios
                    )
                )
            }.collect { newState ->
                _uiState.value = newState

                // Fetch missing users for all visible lists
                val allCreators =
                    (newState.userMemoList.list.items + newState.exploreMemoList.list.items + newState.searchMemoList.list.items + newState.archivedMemoList.list.items).mapNotNull { it.creator }
                        .distinct()
                userDelegate.fetchUsers(allCreators)
            }
        }
    }

    // --- User & Session (Delegated) ---

    // Exposed for delegation only
    private fun fetchCurrentUser() {
        userDelegate.fetchCurrentUser { user ->
            // User fetched, now fetch related data that requires user name
            val name = user.name ?: return@fetchCurrentUser
            viewModelScope.launch { shortcutDelegate.fetchShortcuts(name) }
            viewModelScope.launch { webhookDelegate.fetchWebhooks(name) }

            // Refresh user memos now that we have the numeric userId
            userMemoManager.fetch(refresh = true)
        }
    }

    fun fetchUserMemos(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.USerMemos)
        userMemoManager.fetch(refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreUserMemos() = userMemoManager.loadMore()

    fun fetchExploreMemos(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.ExploreMemos)
        exploreMemoManager.fetch(refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreExploreMemos() = exploreMemoManager.loadMore()

    fun fetchArchivedMemos(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.ArchivedMemos)
        archivedMemoManager.fetch(refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreArchivedMemos() = archivedMemoManager.loadMore()

    fun fetchSearchMemos(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.SearchMemos)
        searchMemoManager.fetch(refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreSearchMemos() = searchMemoManager.loadMore()

    fun searchMemos(isExplore: Boolean, filter: String?, orderBy: String? = null) {
        searchMemoManager.updateFilter(filter, orderBy)
        fetchSearchMemos(refresh = true)
    }

    private fun updateRefreshTrigger(source: RefreshSource = RefreshSource.Manual) {
        _uiState.update {
            it.copy(
                isRefreshing = true,
                refreshTrigger = System.currentTimeMillis(),
                refreshSource = source
            )
        }
    }

    private fun clearRefreshingState() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    fun fetchAttachments(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.Attachments)
        attachmentManager.fetch(refresh = refresh, softRefresh = refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreAttachments() {
        attachmentManager.loadMore()
    }

    fun updateAttachmentCellWidth(width: Float) {
        attachmentManager.updateCellWidth(width)
    }

    suspend fun listCurrentUserNotifications(maxItems: Int = 100): List<UserNotification> {
        val currentApi = api ?: throw IllegalStateException("Unable to access notifications.")
        val userName = _uiState.value.session.currUser?.name
            ?: throw IllegalStateException("User information not available.")

        val notifications = mutableListOf<UserNotification>()
        var nextPageToken: String? = null

        while (true) {
            val remaining = (maxItems - notifications.size).coerceAtMost(50)
            if (remaining <= 0) break

            val response = currentApi.listUserNotifications(
                user = userName,
                pageSize = remaining,
                pageToken = nextPageToken
            )
            val pageNotifications = response.notifications.orEmpty()
            notifications += pageNotifications
            nextPageToken = response.nextPageToken?.takeIf { it.isNotBlank() }

            if (pageNotifications.isEmpty() || nextPageToken == null || notifications.size >= maxItems) {
                break
            }
        }

        return notifications
    }

    private fun updateMemoInState(updatedMemo: Memo) {
        val isSame = { m: Memo -> m.name == updatedMemo.name }
        userMemoManager.replace(updatedMemo, isSame)
        exploreMemoManager.replace(updatedMemo, isSame)
        archivedMemoManager.replace(updatedMemo, isSame)
        searchMemoManager.replace(updatedMemo, isSame)
        commentManager.replace(updatedMemo, isSame)

        if (_uiState.value.detailPane.selectedMemo?.name == updatedMemo.name) {
            _uiState.update {
                it.copy(detailPane = it.detailPane.copy(selectedMemo = updatedMemo))
            }
        }
    }

    fun updateAttachmentAspectRatio(scale: Float, key: String, ratio: Float) {
        val currentMap = _attachmentAspectRatios.value.toMutableMap()
        val scaleMap = currentMap[scale]?.toMutableMap() ?: mutableMapOf()
        scaleMap[key] = ratio
        currentMap[scale] = scaleMap
        _attachmentAspectRatios.value = currentMap
    }

    private fun observeLocalCache(accountId: String) {
        cacheCollectionJob?.cancel()
        cacheCollectionJob = viewModelScope.launch {
            memoCacheRepository.observeUnsyncedMemos(accountId).collect { unsynced ->
                _uiState.update {
                    it.copy(
                        draft = it.draft.copy(
                            drafts = unsynced.map { memo ->
                                val draft = memo.toDraft()
                                draft.copy(
                                    id = memo.localId ?: draft.id,
                                    syncState = memo.effectiveSyncState
                                )
                            },
                            isDraftLoaded = true
                        )
                    )
                }
            }
        }
    }

    private fun mergeUnsyncedMemos(remoteMemos: List<Memo>, archived: Boolean): List<Memo> {
        val drafts = _uiState.value.draft.drafts.filter { draft ->
            if (archived) {
                draft.state == MemoState.ARCHIVED
            } else {
                draft.state != MemoState.ARCHIVED
            }
        }
        if (drafts.isEmpty()) return remoteMemos

        val remoteNames = remoteMemos.mapNotNull { it.name }.toSet()
        val replacements = drafts
            .filter { !it.remoteName.isNullOrBlank() }
            .associateBy { it.remoteName }

        val localOnly = drafts
            .filter { it.remoteName.isNullOrBlank() }
            .map { it.toMemo() }

        val replacedOrRemote = remoteMemos.map { memo ->
            replacements[memo.name]?.toMemo() ?: memo
        }

        val offlineEditsMissingFromCache = drafts
            .filter { !it.remoteName.isNullOrBlank() && it.remoteName !in remoteNames }
            .map { it.toMemo() }

        return localOnly + offlineEditsMissingFromCache + replacedOrRemote
    }
}
