package org.example.memosm.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.example.memosm.api.AuthInterceptor
import org.example.memosm.api.MemosApi
import org.example.memosm.api.MemosApiFactory
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.StreamingAttachmentApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.data.network.ConnectivityObserver
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.data.offline.NotificationCacheStore
import org.example.memosm.data.offline.NotificationsSnapshotData
import org.example.memosm.data.offline.SessionCacheStore
import org.example.memosm.data.sync.ConflictResolution
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.PreDownloadManager
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.data.sync.SyncManager
import org.example.memosm.data.sync.SyncRepository
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.model.Account
import org.example.memosm.model.Memo
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
import org.example.memosm.viewmodel.manager.LocalSearchFilter
import org.example.memosm.viewmodel.manager.SearchMemoListManager
import org.example.memosm.viewmodel.manager.ServerReachabilityMonitor
import org.example.memosm.viewmodel.manager.UserMemoListManager
import org.example.memosm.viewmodel.manager.USER_MEMO_COMPARATOR
import org.example.memosm.model.UserNotification

class MemosViewModel(
    private val dataStoreManager: DataStoreManager,
    private val draftManager: DraftManager,
    private val memoCacheRepository: MemoCacheRepository,
    private val okHttpClient: OkHttpClient,
    private val connectivityObserver: ConnectivityObserver,
    private val attachmentCacheManager: AttachmentCacheManager,
    private val syncRepository: SyncRepository,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val syncAuditLogger: SyncAuditLogger,
    private val attachmentCacheStore: AttachmentCacheStore,
    private val sessionCacheStore: SessionCacheStore,
    private val notificationCacheStore: NotificationCacheStore,
    private val attachmentUploadQueue: AttachmentUploadQueue
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemosUiState())
    val uiState: StateFlow<MemosUiState> = _uiState.asStateFlow()

    private var api: MemosApi? = null
    private var currentHttpClient: OkHttpClient? = null
    private var currentBaseUrl: String? = null

    private fun activeAccountId(): String? = _uiState.value.accounts.find { it.isActive }?.id

    // Managers
    private val userMemoManager: UserMemoListManager = UserMemoListManager(
        scope = viewModelScope,
        apiProvider = { api },
        filterProvider = {
            val user = _uiState.value.session.currUser
            val base = api?.buildMemoCreatorFilter(user)

            val shortcut = _uiState.value.userMemoList.selectedShortcut
            val hashtag = _uiState.value.userMemoList.selectedHashtag
            val extraFilter = when {
                shortcut != null && !shortcut.filter.isNullOrBlank() -> shortcut.filter
                hashtag != null -> {
                    val tagName = hashtag.removePrefix("#")
                    "tag in [\"$tagName\"]"
                }

                else -> null
            }

            listOfNotNull(base?.takeIf { it.isNotBlank() }, extraFilter?.takeIf { it.isNotBlank() })
                .joinToString(" && ")
                .ifBlank { null }
        },
        pageSizeProvider = { _uiState.value.appSettings.pageSize },
        cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
            val accountId = activeAccountId() ?: return@CacheCallbacks
            // Merge (never replace) so a page-1 refresh cannot wipe the
            // full-history text cache built by the pre-downloader.
            memoCacheRepository.cacheMemos(accountId, CacheListType.USER, memos, replace = false)
            refreshTextCacheCount()
            // Pre-download attachments of what is now visible, for offline viewing.
            preDownloadManager.preloadVisibleAttachments(memos)
        }, getCachedData = { limit ->
            val accountId = activeAccountId()
                ?: return@CacheCallbacks emptyList()
            memoCacheRepository.getCachedMemos(
                accountId, CacheListType.USER, limit = limit
            )
        }),
        protectedNamesProvider = {
            // Memos with a queued offline UPDATE keep their local (newer)
            // content until the op is pushed, so a refresh cannot regress them
            // to stale server content.
            _uiState.value.pendingOps
                .filter { it.type == PendingOpType.UPDATE.name }
                .mapNotNull { it.memoName }
                .toSet()
        }
    )

    private val exploreMemoManager: ExploreMemoListManager =
        ExploreMemoListManager(
            scope = viewModelScope,
            apiProvider = { api },
            pageSizeProvider = { _uiState.value.appSettings.pageSize },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
                val accountId =
                    activeAccountId() ?: return@CacheCallbacks
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.EXPLORE, memos, replace = false
                )
                refreshTextCacheCount()
                preDownloadManager.preloadVisibleAttachments(memos)
            }, getCachedData = { limit ->
                val accountId = activeAccountId()
                    ?: return@CacheCallbacks emptyList()
                memoCacheRepository.getCachedMemos(
                    accountId, CacheListType.EXPLORE, limit = limit
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
                    activeAccountId() ?: return@CacheCallbacks
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.ARCHIVED, memos, replace = false
                )
                refreshTextCacheCount()
                preDownloadManager.preloadVisibleAttachments(memos)
            }, getCachedData = { limit ->
                val accountId = activeAccountId()
                    ?: return@CacheCallbacks emptyList()
                memoCacheRepository.getCachedMemos(
                    accountId, CacheListType.ARCHIVED, limit = limit
                )
            })
        )

    private val searchMemoManager: SearchMemoListManager = SearchMemoListManager(
        viewModelScope,
        { api },
        pageSizeProvider = { _uiState.value.appSettings.pageSize },
        // Search results are not persisted to the cache: offline search already
        // runs against the USER/ARCHIVED/EXPLORE cached rows via
        // searchCachedMemos, so writing a dedicated SEARCH list would only
        // duplicate data and bloat the database without any reader.
        cacheCallbacks = CacheCallbacks(onFetchSuccess = {}, getCachedData = { emptyList() }),
        localSearchProvider = { filter ->
            val accountId = activeAccountId()
            if (accountId == null) emptyList()
            else memoCacheRepository.searchCachedMemos(
                accountId = accountId,
                query = filter.query,
                tags = filter.tags,
                startMillis = filter.startMillis,
                endMillis = filter.endMillis,
                // The Explore tab search must not surface the user's own
                // (private) memos: restrict to the EXPLORE cache rows, which
                // were pre-downloaded with the PUBLIC/PROTECTED visibility
                // filter.
                explore = filter.explore
            )
        })

    private val commentManager: CommentListManager = CommentListManager(
        viewModelScope,
        { api },
        cacheCallbacks = CacheCallbacks(onFetchSuccess = { comments ->
            val accountId = activeAccountId() ?: return@CacheCallbacks
            val parent = commentManager.currentMemoName
            if (parent != null) {
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.COMMENT, comments, replace = false, parentName = parent
                )
            }
        }, getCachedData = { _ ->
            val accountId = activeAccountId() ?: return@CacheCallbacks emptyList()
            val parent = commentManager.currentMemoName
            if (parent == null) emptyList()
            else memoCacheRepository.getCachedMemos(
                accountId, CacheListType.COMMENT, parentName = parent
            )
        }),
        isOnlineProvider = { _uiState.value.isOnline }
    )

    private val attachmentManager: AttachmentManager =
        AttachmentManager(
            scope = viewModelScope, apiProvider = { api }, streamingApiProvider = {
            currentHttpClient?.let {
                StreamingAttachmentApi(it, currentBaseUrl ?: "")
            }
        }, initialCellWidth = _uiState.value.attachmentList.cellWidth,
            uploadQueueProvider = { attachmentUploadQueue },
            accountIdProvider = { activeAccountId() },
            draftReferenceChecker = { clientId ->
                val accountId = activeAccountId() ?: return@AttachmentManager false
                draftManager.draftsContain(accountId, clientId)
            },
            outboxReferenceChecker = { clientId ->
                val accountId = activeAccountId() ?: return@AttachmentManager false
                syncRepository.getOps(accountId).any { it.payloadJson?.contains(clientId) == true }
            },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { attachments ->
                val accountId = activeAccountId() ?: return@CacheCallbacks
                attachmentCacheStore.cacheMeta(accountId, attachments)
            }, getCachedData = { limit ->
                val accountId = activeAccountId()
                    ?: return@CacheCallbacks emptyList()
                attachmentCacheStore.getCachedMeta(accountId, limit)
            })
        )

    private var collectionJob: Job? = null

    // Server-reachability state machine (one-shot + periodic probes); bridged
    // into _uiState in startOfflineStateCollection().
    private val reachabilityMonitor = ServerReachabilityMonitor(
        scope = viewModelScope,
        apiProvider = { api },
        accountIdProvider = { activeAccountId() }
    )

    private val _attachmentAspectRatios =
        MutableStateFlow<Map<Float, Map<String, Float>>>(emptyMap())

    // Sync engine: replays queued offline writes when connectivity returns.
    private val syncManager = SyncManager(
        scope = viewModelScope,
        repository = syncRepository,
        memoCacheRepository = memoCacheRepository,
        dataStoreManager = dataStoreManager,
        workScheduler = syncWorkScheduler,
        auditLogger = syncAuditLogger,
        apiProvider = { api },
        accountIdProvider = { activeAccountId() },
        currentUserProvider = { _uiState.value.session.currUser },
        // Use the UI state's isOnline (which reflects server reachability)
        // rather than the raw connectivity observer: the observer requires
        // NET_CAPABILITY_VALIDATED, which is absent on emulators using adb
        // reverse and on captive portals the OS hasn't validated yet. The
        // reachability probe is the authoritative online signal.
        isOnlineProvider = { _uiState.value.isOnline },
        attachmentUploadQueueProvider = { attachmentUploadQueue },
        onMemoSynced = { memo, tempName ->
            if (tempName != null) {
                // A queued create just landed: swap the temporary local memo for the real one.
                memoListUpdater.removeMemoFromLists(tempName)
                activeAccountId()?.let { memoCacheRepository.removeCachedMemo(it, tempName) }
                memoListUpdater.insertMemoIntoUserList(memo)
            } else {
                memoListUpdater.updateMemoInLists(memo)
            }
        },
        onMemoDeleted = { name -> memoListUpdater.removeMemoFromLists(name) },
        onCommentsRefresh = { commentManager.fetch(refresh = true) },
        onConflict = { item ->
            // Keep the first unresolved conflict on screen: the sync loop may
            // detect several conflicts back-to-back, and overwriting the dialog
            // would drop the user's pending decision. Remaining conflicted ops
            // stay queued and re-surface on the next sync, one at a time.
            _uiState.update { state ->
                if (state.conflict != null) state else state.copy(conflict = item)
            }
        }
    )

    // Pre-downloads full text history + attachments for offline use.
    private val preDownloadManager = PreDownloadManager(
        scope = viewModelScope,
        memoCacheRepository = memoCacheRepository,
        dataStoreManager = dataStoreManager,
        attachmentCacheManager = attachmentCacheManager,
        attachmentCacheStore = attachmentCacheStore,
        apiProvider = { api },
        accountIdProvider = { activeAccountId() },
        userProvider = { _uiState.value.session.currUser },
        hostUrlProvider = { _uiState.value.session.hostUrl },
        tokenProvider = { _uiState.value.session.token },
        isOnlineProvider = { _uiState.value.isOnline },
        isWifiProvider = { connectivityObserver.isWifi.value }
    )

    // Delegates
    val userDelegate: UserDelegate = UserDelegateImpl(
        viewModelScope, _uiState, { api }, dataStoreManager, sessionCacheStore,
        onAccountSwitched = { account ->
            switchAccountInternal(account)
        },
        onAccountRemoved = { account ->
            viewModelScope.launch {
                memoCacheRepository.clearCache(account.id)
                syncManager.clearForAccount(account.id)
                // Stop both the one-time and the periodic outbox replay, and
                // drop the account's durable attachment uploads (staged files
                // and rows) so nothing lingers for a deleted account.
                syncWorkScheduler.cancel(account.id)
                attachmentUploadQueue.clearForAccount(account.id)
                attachmentCacheManager.clearAccount(account.id)
                attachmentCacheStore.clearMeta(account.id)
                notificationCacheStore.clear(account.id)
                // Drop per-account leftovers: prefs keys and the in-memory
                // hostUrl mapping (Coil's global image cache is bounded by its
                // own cap and cannot be cleared per-account, so it is left as-is).
                sessionCacheStore.clear(account.id)
                dataStoreManager.removeLastSyncTime(account.id)
                attachmentCacheManager.forgetHost(account.hostUrl)
            }
        }
    )

    val shortcutDelegate: ShortcutDelegate = ShortcutDelegateImpl(
        viewModelScope,
        _uiState,
        { api },
        sessionCacheStore,
        {
            // Filter changes: the cached prefill/merge is unfiltered, so clear
            // the cached-merge flag before refetching - the server-filtered
            // page must replace the list plainly instead of being polluted by
            // cached items from other filters.
            userMemoManager.updateState { it.copy(showingCached = false) }
            userMemoManager.fetch(refresh = true)
        })

    val webhookDelegate: WebhookDelegate = WebhookDelegateImpl(
        viewModelScope, _uiState
    ) { api }


    val appSettingsDelegate: AppSettingsDelegate = AppSettingsDelegateImpl(
        viewModelScope, _uiState, dataStoreManager,
        onPageSizeChanged = {
            userMemoManager.fetch(refresh = true)
            exploreMemoManager.fetch(refresh = true)
        },
        onOfflineSettingsChanged = {
            preDownloadManager.maybeAutoDownload()
        }
    )

    val draftDelegate: DraftDelegate = DraftDelegateImpl(
        viewModelScope, _uiState, draftManager, { memoActionDelegate }) { userMemoManager.fetch(refresh = true) }

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
                    userMemoManager.upsert(updated, isSameUpdated, USER_MEMO_COMPARATOR)
                }
            }
        }

        override fun insertMemoIntoUserList(memo: Memo) {
            userMemoManager.upsert(memo, { it.name == memo.name }, USER_MEMO_COMPARATOR)
            if (_uiState.value.detailPane.selectedMemo?.name == memo.name) {
                _uiState.update {
                    it.copy(detailPane = it.detailPane.copy(selectedMemo = memo))
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
        { commentManager },
        syncManager,
        memoCacheRepository,
        { activeAccountId() },
        { _uiState.value.session.currUser },
        { _uiState.value.isOnline })

    init {
        userDelegate.updateCurrentAccountInList()
        appSettingsDelegate.loadPageSize()
        appSettingsDelegate.loadHeaderScale()
        appSettingsDelegate.loadOfflineSettings()

        startStateCollection()
        startOfflineStateCollection()
        syncManager.startObserving(dataStoreManager.account.map { it?.id })
    }

    private suspend fun createApi(
        baseUrl: String, token: String
    ): MemosApi {
        val authInterceptor = AuthInterceptor(token)

        currentHttpClient = okHttpClient.newBuilder().addInterceptor(authInterceptor).build()
        currentBaseUrl = baseUrl

        return MemosApiFactory.create(baseUrl, currentHttpClient!!)
    }

    private fun runRecoverySequence() {
        syncManager.syncNow()
        preDownloadManager.maybeAutoDownload()
        listOf(userMemoManager, exploreMemoManager, archivedMemoManager).forEach { manager ->
            manager.updateState { it.copy(isOffline = false, errorMessage = null) }
            if (manager.listState.value.showingCached) manager.fetch(refresh = true)
        }
    }

    // Keep this one as it's used by the delegate directly above
    private fun switchAccountInternal(account: Account) {
        // Re-create Api and Managers
        viewModelScope.launch {
            api = createApi(account.hostUrl, account.accessToken)

            // Reset all lists: cached items and merge flags from the previous
            // account must not leak into the new account's list.
            userMemoManager.reset()
            exploreMemoManager.reset()
            archivedMemoManager.reset()
            searchMemoManager.reset()
            commentManager.reset()

            fetchCurrentUser()
            // Always probe server reachability on account switch: the system
            // connectivity observer may report offline (e.g. emulator with adb
            // reverse, or an unvalidated captive portal) while the server is
            // actually reachable. If the probe succeeds, the recovery sequence
            // fetches lists and replays the outbox; if it fails, the offline
            // cache is served instead.
            if (_uiState.value.isOnline) {
                reachabilityMonitor.checkNow {
                    exploreMemoManager.fetch()
                    userMemoManager.fetch()
                    runRecoverySequence()
                }
            } else {
                // Optimistically serve from cache first, then probe: if the
                // server turns out to be reachable, the recovery sequence
                // will refresh the lists on top of the cached data.
                exploreMemoManager.loadFromCache()
                userMemoManager.loadFromCache()
                reachabilityMonitor.checkNow {
                    exploreMemoManager.fetch()
                    userMemoManager.fetch()
                    runRecoverySequence()
                }
            }
            draftDelegate.loadDraftsForAccount(account.id)

            // Entering the app: sync queued writes and pre-download.
            syncManager.syncNow()
            preDownloadManager.maybeAutoDownload()
            attachmentCacheManager.refreshUsage(account.id)
            refreshTextCacheCount()
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
                // Use update{} (CAS) instead of `value = copy(...)`: several
                // other collectors (pendingOps, isSyncing, preDownloadState,
                // usage, lastSyncTime, syncError) mutate the same StateFlow
                // concurrently, and a plain read-modify-write would silently
                // drop their updates.
                _uiState.update { current ->
                    current.copy(
                        userMemoList = current.userMemoList.copy(list = userMemos),
                        exploreMemoList = current.exploreMemoList.copy(list = exploreMemos),
                        archivedMemoList = current.archivedMemoList.copy(list = archivedMemos),
                        searchMemoList = current.searchMemoList.copy(list = searchMemos),
                        detailPane = current.detailPane.copy(comments = comments),
                        attachmentList = AttachmentListState(
                            list = attachments, cellWidth = cellWidth, aspectRatios = aspectRatios
                        )
                    )
                }

                // Fetch missing users for all visible lists (deduped in the delegate)
                val allCreators =
                    (userMemos.items + exploreMemos.items + searchMemos.items + archivedMemos.items + comments.items).mapNotNull { it.creator }
                        .distinct()
                if (allCreators.isNotEmpty()) {
                    userDelegate.fetchUsers(allCreators)
                }
            }.collect { }
        }
    }

    /**
     * Collects offline/sync state: connectivity, pending ops, sync progress,
     * pre-download progress, last sync time and attachment cache usage.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun startOfflineStateCollection() {
        // Periodic reachability probe: catches the case where the OS keeps
        // reporting validated connectivity while the server process dies (or
        // comes back) without any connectivity change.
        reachabilityMonitor.start(::runRecoverySequence)
        // Bridge the monitor's reachability state into the UI state.
        viewModelScope.launch {
            reachabilityMonitor.state.collect { reachability ->
                _uiState.update {
                    it.copy(
                        isOnline = reachability.isOnline,
                        connectionState = reachability.connectionState,
                        syncError = reachability.error
                    )
                }
            }
        }
        viewModelScope.launch {
            connectivityObserver.isOnline.collect { online ->
                if (!online) {
                    reachabilityMonitor.cancelProbe()
                    _uiState.update {
                        it.copy(isOnline = false, connectionState = ConnectionState.CHECKING)
                    }
                    // Stop active network work promptly; durable Room queue and
                    // optimistic cache remain intact for the next recovery pass.
                    syncManager.cancelSync()
                    preDownloadManager.cancel()
                    // Even when the system reports no validated Internet (common on
                    // emulators using adb reverse, or captive portals the OS hasn't
                    // validated yet), the server may still be reachable. Probe it
                    // rather than serving stale cache indefinitely.
                    reachabilityMonitor.checkNow(::runRecoverySequence)
                } else {
                    reachabilityMonitor.checkNow(::runRecoverySequence)
                }
            }
        }
        viewModelScope.launch {
            // StateFlow collectors always see the latest value (StateFlow is
            // conflated by design); the queue may change per operation during
            // sync bursts, but only the newest snapshot reaches the UI state.
            syncManager.pendingOps.collect { ops ->
                _uiState.update { it.copy(pendingOps = ops, pendingOpsCount = ops.size) }
            }
        }
        viewModelScope.launch {
            syncManager.isSyncing.collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }
        viewModelScope.launch {
            // Running progress is sampled at the source (PreDownloadManager),
            // so a pre-download burst cannot storm the UI state with
            // intermediate progress updates.
            preDownloadManager.state.collect { state ->
                _uiState.update { it.copy(preDownloadState = state) }
                // A finished pre-download changes the cached text count; refresh
                // it so the status bar / panel show up-to-date numbers.
                if (state is PreDownloadState.Done) refreshTextCacheCount()
            }
        }
        viewModelScope.launch {
            attachmentCacheManager.usage.collect { usage ->
                _uiState.update { it.copy(attachmentCacheUsage = usage) }
            }
        }
        viewModelScope.launch {
            // Per-account key: switching accounts must show that account's own
            // last-sync time, not the previous one's.
            dataStoreManager.account.flatMapLatest { account ->
                dataStoreManager.lastSyncTime(account?.id)
            }.conflate().collect { timestamp ->
                _uiState.update { it.copy(lastSyncTime = timestamp) }
            }
        }
        // Surface the most recent connection/sync error so the UI can show a
        // concrete failure reason (e.g. "timeout", "server unreachable") instead
        // of a generic offline notice.
        viewModelScope.launch {
            combine(
                userMemoManager.listState.map { it.errorMessage },
                exploreMemoManager.listState.map { it.errorMessage },
                archivedMemoManager.listState.map { it.errorMessage },
                syncManager.pendingOps
            ) { userErr, exploreErr, archivedErr, ops ->
                listOfNotNull(userErr, exploreErr, archivedErr).firstOrNull()
                    ?: ops.asReversed().firstNotNullOfOrNull { it.lastError }
            }.distinctUntilChanged().conflate().collect { err ->
                _uiState.update { it.copy(syncError = err) }
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

            // Full-text pre-download needs the user identity to build the
            // creator filter - at account-switch time it was still null, so
            // re-trigger now that we know who the user is.
            preDownloadManager.maybeAutoDownload()

            // Refresh user memos now that we have the numeric userId
            if (_uiState.value.isOnline) {
                userMemoManager.fetch(refresh = true)
            } else {
                userMemoManager.loadFromCache()
            }
        }
    }

    fun fetchUserMemos(refresh: Boolean = false) {
        if (!_uiState.value.isOnline) {
            // Offline: serve cached data immediately instead of timing out.
            if (refresh) updateRefreshTrigger(RefreshSource.USerMemos)
            userMemoManager.loadFromCache()
            if (refresh) clearRefreshingState()
            return
        }
        if (refresh) updateRefreshTrigger(RefreshSource.USerMemos)
        userMemoManager.fetch(refresh)
        if (refresh) {
            clearRefreshingState()
            // Pull-to-refresh is an explicit user action: bypass the backoff.
            syncManager.syncNow(force = true)
        }
    }

    fun loadMoreUserMemos() = userMemoManager.loadMore()

    fun fetchExploreMemos(refresh: Boolean = false) {
        if (!_uiState.value.isOnline) {
            if (refresh) updateRefreshTrigger(RefreshSource.ExploreMemos)
            exploreMemoManager.loadFromCache()
            if (refresh) clearRefreshingState()
            return
        }
        if (refresh) updateRefreshTrigger(RefreshSource.ExploreMemos)
        exploreMemoManager.fetch(refresh)
        if (refresh) {
            clearRefreshingState()
            // Pull-to-refresh is an explicit user action: bypass the backoff.
            syncManager.syncNow(force = true)
        }
    }

    fun loadMoreExploreMemos() = exploreMemoManager.loadMore()

    fun fetchArchivedMemos(refresh: Boolean = false) {
        if (!_uiState.value.isOnline) {
            if (refresh) updateRefreshTrigger(RefreshSource.ArchivedMemos)
            archivedMemoManager.loadFromCache()
            if (refresh) clearRefreshingState()
            return
        }
        if (refresh) updateRefreshTrigger(RefreshSource.ArchivedMemos)
        archivedMemoManager.fetch(refresh)
        if (refresh) {
            clearRefreshingState()
            // Pull-to-refresh is an explicit user action: bypass the backoff.
            syncManager.syncNow(force = true)
        }
    }

    fun loadMoreArchivedMemos() = archivedMemoManager.loadMore()

    fun fetchSearchMemos(refresh: Boolean = false) {
        if (refresh) updateRefreshTrigger(RefreshSource.SearchMemos)
        searchMemoManager.fetch(refresh)
        if (refresh) clearRefreshingState()
    }

    fun loadMoreSearchMemos() = searchMemoManager.loadMore()

    fun searchMemos(
        isExplore: Boolean,
        filter: String?,
        orderBy: MemoOrderBy? = null,
        localFilter: LocalSearchFilter = LocalSearchFilter()
    ) {
        searchMemoManager.updateFilter(filter, orderBy)
        searchMemoManager.updateLocalFilter(localFilter)
        if (!_uiState.value.isOnline) {
            // Offline: search the local cache instead of the server.
            searchMemoManager.searchLocal()
            return
        }
        fetchSearchMemos(refresh = true)
    }

    // --- Offline / sync actions ---

    /**
     * App came to the foreground: flush queued writes and refresh the cache.
     * Automatic trigger - respects the sync backoff (no force).
     */
    fun onForeground() {
        if (!_uiState.value.isOnline) return
        syncManager.syncNow()
        preDownloadManager.maybeAutoDownload()
    }

    /**
     * User-triggered sync (banner, sync buttons, pull-to-refresh):
     * force bypasses the retry backoff / permanent-failure guards.
     */
    fun syncNow() {
        syncManager.syncNow(force = true)
    }

    /**
     * Remove a single queued offline write (user abandons it).
     */
    fun deletePendingOp(opId: String) {
        viewModelScope.launch {
            syncManager.deleteOp(opId)
        }
    }

    fun preDownloadNow() {
        preDownloadManager.downloadAllText()
    }

    fun preDownloadAllAttachments() {
        preDownloadManager.preloadAllAttachments()
    }

    fun clearTextCache() {
        val accountId = activeAccountId() ?: return
        viewModelScope.launch {
            preDownloadManager.clearTextCache(accountId)
            // The in-memory list still shows the wiped cache; reset it so the
            // UI reflects the empty local state (re-fetch/pre-download fills it).
            userMemoManager.reset()
            exploreMemoManager.reset()
            archivedMemoManager.reset()
            refreshTextCacheCount()
        }
    }

    fun clearAttachmentCache() {
        val accountId = activeAccountId() ?: return
        viewModelScope.launch {
            preDownloadManager.clearAttachmentCache(accountId)
        }
    }

    /**
     * Clear both the text cache (local memo DB) and the attachment cache
     * (offline_media files) for the active account.
     */
    fun clearAllCaches() {
        val accountId = activeAccountId() ?: return
        viewModelScope.launch {
            preDownloadManager.clearTextCache(accountId)
            preDownloadManager.clearAttachmentCache(accountId)
            refreshTextCacheCount()
            attachmentCacheManager.refreshUsage(accountId)
        }
    }

    /**
     * Refresh the number of locally cached memos shown in the cache analysis UI.
     */
    fun refreshTextCacheCount() {
        val accountId = activeAccountId() ?: return
        viewModelScope.launch {
            val count = memoCacheRepository.getCachedCount(accountId)
            _uiState.update { it.copy(textCacheCount = count) }
        }
    }

    fun refreshAttachmentCacheUsage() {
        activeAccountId()?.let { accountId ->
            viewModelScope.launch { attachmentCacheManager.refreshUsage(accountId) }
        }
    }

    fun resolveConflict(resolution: ConflictResolution, mergedContent: String? = null) {
        val item = _uiState.value.conflict ?: return
        _uiState.update { it.copy(conflict = null) }
        syncManager.resolveConflict(item, resolution, mergedContent)
        // Resolving may have unblocked other conflicted ops (the sync loop keeps
        // them queued while a dialog is up). Run the queue again (forced - the
        // user just acted) so the next conflict surfaces promptly; LATER
        // deliberately leaves it parked.
        if (resolution != ConflictResolution.LATER) {
            syncManager.syncNow(force = true)
        }
    }

    fun dismissConflict() {
        _uiState.update { it.copy(conflict = null) }
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
        if (!_uiState.value.isOnline) {
            // Offline: serve cached metadata immediately instead of timing out.
            if (refresh) updateRefreshTrigger(RefreshSource.Attachments)
            attachmentManager.loadFromCache()
            if (refresh) clearRefreshingState()
            return
        }
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

    suspend fun listCurrentUserNotifications(maxItems: Int = 100): NotificationsResult {
        val currentApi = api ?: run {
            Log.e("MemosViewModel", "Cannot load notifications: API is unavailable")
            throw IllegalStateException("Unable to access notifications.")
        }
        val userName = _uiState.value.session.currUser?.name
            ?: run {
                Log.e("MemosViewModel", "Cannot load notifications: current user is unavailable")
                throw IllegalStateException()
            }

        val notifications = mutableListOf<UserNotification>()
        var nextPageToken: String? = null
        var isFirstPage = true

        try {
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

                // Persist the first successful page so the notifications screen
                // can show last-known data offline instead of an error. Only a
                // successful response is ever written - a failed fetch never
                // overwrites a good snapshot.
                if (isFirstPage) {
                    isFirstPage = false
                    activeAccountId()?.let { accountId ->
                        runCatching {
                            notificationCacheStore.save(
                                accountId,
                                NotificationsSnapshotData(
                                    notifications = pageNotifications,
                                    savedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                if (pageNotifications.isEmpty() || nextPageToken == null || notifications.size >= maxItems) {
                    break
                }
            }

            return NotificationsResult(notifications)
        } catch (e: Exception) {
            // Offline/failure: serve the last successful first page; the UI
            // badges it as cached instead of showing the error view.
            val accountId = activeAccountId()
            val snapshot = if (accountId != null) {
                runCatching { notificationCacheStore.get(accountId) }.getOrNull()
            } else {
                null
            }
            if (snapshot != null && snapshot.notifications.isNotEmpty()) {
                return NotificationsResult(
                    notifications = snapshot.notifications,
                    savedAt = snapshot.savedAt,
                    fromCache = true
                )
            }
            throw e
        }
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
        // Atomic CAS: two images finishing in the same frame must not drop
        // each other's ratio update.
        _attachmentAspectRatios.update { current ->
            val scaleMap = current[scale]?.toMutableMap() ?: mutableMapOf()
            scaleMap[key] = ratio
            current + (scale to scaleMap)
        }
    }
}
