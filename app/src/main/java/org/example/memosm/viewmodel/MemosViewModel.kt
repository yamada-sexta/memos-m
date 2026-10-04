package org.example.memosm.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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

    private val accountSession = AccountSession(viewModelScope)

    private val api: MemosApi? get() = accountSession.current?.takeIf { it.networkReady }?.api

    private fun activeAccountId(): String? = accountSession.current?.account?.id

    // Managers
    private val userMemoManager: UserMemoListManager = UserMemoListManager(
        scope = accountSession.readScope,
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
            val context = accountSession.current ?: return@CacheCallbacks
            val accountId = context.account.id
            // Merge (never replace) so a page-1 refresh cannot wipe the
            // full-history text cache built by the pre-downloader.
            memoCacheRepository.cacheMemos(accountId, CacheListType.USER, memos, replace = false)
            if (!accountSession.isCurrent(context)) return@CacheCallbacks
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
            scope = accountSession.readScope,
            apiProvider = { api },
            pageSizeProvider = { _uiState.value.appSettings.pageSize },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
                val context = accountSession.current ?: return@CacheCallbacks
                val accountId = context.account.id
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.EXPLORE, memos, replace = false
                )
                if (!accountSession.isCurrent(context)) return@CacheCallbacks
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
            scope = accountSession.readScope,
            apiProvider = { api },
            currentUserProvider = { _uiState.value.session.currUser },
            pageSizeProvider = { _uiState.value.appSettings.pageSize },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { memos ->
                val context = accountSession.current ?: return@CacheCallbacks
                val accountId = context.account.id
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.ARCHIVED, memos, replace = false
                )
                if (!accountSession.isCurrent(context)) return@CacheCallbacks
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
        accountSession.readScope,
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
        accountSession.readScope,
        { api },
        cacheCallbacks = CacheCallbacks(onFetchSuccess = { comments ->
            val context = accountSession.current ?: return@CacheCallbacks
            val accountId = context.account.id
            val parent = commentManager.currentMemoName
            if (parent != null) {
                memoCacheRepository.cacheMemos(
                    accountId, CacheListType.COMMENT, comments, replace = false, parentName = parent
                )
            }
        }, getCachedData = { _ ->
            val context = accountSession.current ?: return@CacheCallbacks emptyList()
            val accountId = context.account.id
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
            scope = accountSession.readScope, accountSession = accountSession, apiProvider = { api },
            initialCellWidth = _uiState.value.attachmentList.cellWidth,
            uploadQueueProvider = { attachmentUploadQueue },
            draftReferenceChecker = { clientId ->
                val accountId = activeAccountId() ?: return@AttachmentManager false
                draftManager.draftsContain(accountId, clientId)
            },
            outboxReferenceChecker = { clientId ->
                val accountId = activeAccountId() ?: return@AttachmentManager false
                syncRepository.getOps(accountId).any { it.payloadJson?.contains(clientId) == true }
            },
            cacheCallbacks = CacheCallbacks(onFetchSuccess = { attachments ->
                val context = accountSession.current ?: return@CacheCallbacks
                val accountId = context.account.id
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
        accountSession = accountSession,
        memoCacheRepository = memoCacheRepository,
        dataStoreManager = dataStoreManager,
        workScheduler = syncWorkScheduler,
        auditLogger = syncAuditLogger,
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
        viewModelScope, _uiState, dataStoreManager, sessionCacheStore, accountSession,
        onAccountSwitched = { account ->
            switchAccountInternal(account)
        },
        onAccountRemoved = { account ->
            viewModelScope.launch {
                syncWorkScheduler.cancel(account.id)
                memoCacheRepository.clearCache(account.id)
                syncManager.clearForAccount(account.id)
                // Drop the deleted account's staged uploads and durable queue.
                attachmentUploadQueue.clearForAccount(account.id)
                attachmentCacheManager.clearAccount(account.id)
                attachmentCacheStore.clearMeta(account.id)
                notificationCacheStore.clear(account.id)
                // Drop the deleted account's snapshots and download metadata.
                sessionCacheStore.clear(account.id)
                dataStoreManager.removeLastSyncTime(account.id)
                dataStoreManager.removeDownloadState(account.id)
            }
        }
    )

    val shortcutDelegate: ShortcutDelegate = ShortcutDelegateImpl(
        viewModelScope,
        _uiState,
        sessionCacheStore,
        accountSession,
        {
            // Filter changes: the cached prefill/merge is unfiltered, so clear
            // the cached-merge flag before refetching - the server-filtered
            // page must replace the list plainly instead of being polluted by
            // cached items from other filters.
            userMemoManager.updateState { it.copy(showingCached = false) }
            userMemoManager.fetch(refresh = true)
        })

    val webhookDelegate: WebhookDelegate = WebhookDelegateImpl(
        viewModelScope, _uiState, accountSession
    )


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
        viewModelScope, _uiState, draftManager, accountSession, { memoActionDelegate }) { userMemoManager.fetch(refresh = true) }

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
        memoListUpdater,
        draftDelegate,
        { attachmentManager },
        { commentManager },
        syncManager,
        memoCacheRepository,
        accountSession,
        draftManager,
        { _uiState.value.isOnline })

    init {
        userDelegate.updateCurrentAccountInList()
        appSettingsDelegate.loadPageSize()
        appSettingsDelegate.loadHeaderScale()
        appSettingsDelegate.loadOfflineSettings()

        startStateCollection()
        startOfflineStateCollection()
        syncManager.startObserving(accountSession.contexts.map { it?.account?.id })
    }

    fun retryAccountConnection() {
        val context = accountSession.current ?: return
        viewModelScope.launch {
            val saved = dataStoreManager.getAccounts().firstOrNull { it.id == context.account.id && it.isActive }
            if (saved != null && accountSession.isCurrent(context)) switchAccountInternal(saved)
        }
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
    private suspend fun switchAccountInternal(account: Account?) {
        accountSession.clear()
        syncManager.cancelSync()
        preDownloadManager.cancel()
        reachabilityMonitor.cancelProbe()
        userMemoManager.reset()
        exploreMemoManager.reset()
        archivedMemoManager.reset()
        searchMemoManager.reset()
        commentManager.clearParent()
        attachmentManager.reset()
        _attachmentAspectRatios.value = emptyMap()
        _uiState.update { it.forAccount(account) }
        if (account == null) return
        val client = okHttpClient.newBuilder()
            .addInterceptor(AuthInterceptor(account.accessToken)).build()
        val provisional = accountSession.activate(
            account, MemosApiFactory.createLatest(account.hostUrl, client), client, networkReady = false
        )
        // Local reads never wait for network version discovery or server reachability.
        userMemoManager.loadFromCache()
        exploreMemoManager.loadFromCache()
        userDelegate.restoreCachedSession()
        draftDelegate.loadDraftsForAccount(account.id)
        refreshTextCacheCount()
        accountSession.readScope.launch { attachmentCacheManager.refreshUsage(account.id) }
        accountSession.readScope.launch {
            val detectedApi = MemosApiFactory.create(account.hostUrl, client)
            if (!accountSession.completeConnection(provisional, detectedApi)) return@launch
            val context = provisional
            fetchCurrentUser()
            reachabilityMonitor.checkNow {
                if (accountSession.isCurrent(context)) {
                    userMemoManager.fetch(refresh = true)
                    exploreMemoManager.fetch(refresh = true)
                    runRecoverySequence()
                }
            }
            accountSession.readScope.launch { attachmentCacheManager.refreshUsage(account.id) }
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
            syncManager.currentPendingOps.collect { (context, ops) ->
                accountSession.update(_uiState, context) {
                    it.copy(pendingOps = ops, pendingOpsCount = ops.size)
                }
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
                _uiState.update { if (state is PreDownloadState.Running && state.accountId != activeAccountId() ||
                        state is PreDownloadState.Done && state.accountId != activeAccountId() ||
                        state is PreDownloadState.Failed && state.accountId != activeAccountId()) it
                    else it.copy(preDownloadState = state) }
                // A finished pre-download changes the cached text count; refresh
                // it so the status bar / panel show up-to-date numbers.
                if (state is PreDownloadState.Done) refreshTextCacheCount()
            }
        }
        viewModelScope.launch {
            combine(accountSession.contexts, attachmentCacheManager.usage) { context, usage ->
                context to usage
            }.collect { (context, snapshot) ->
                if (context != null && snapshot.accountId == context.account.id) {
                    accountSession.update(_uiState, context) { it.copy(attachmentCacheUsage = snapshot.usage) }
                }
            }
        }
        viewModelScope.launch {
            // Per-account key: switching accounts must show that account's own
            // last-sync time, not the previous one's.
            accountSession.contexts.flatMapLatest { context ->
                dataStoreManager.lastSyncTime(context?.account?.id).map { context to it }
            }.conflate().collect { (context, timestamp) ->
                if (context != null) accountSession.update(_uiState, context) {
                    it.copy(lastSyncTime = timestamp)
                }
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
                syncManager.currentPendingOps
            ) { userErr, exploreErr, archivedErr, snapshot ->
                snapshot.context to (listOfNotNull(userErr, exploreErr, archivedErr).firstOrNull()
                    ?: snapshot.ops.asReversed().firstNotNullOfOrNull { it.lastError })
            }.distinctUntilChanged().conflate().collect { (context, err) ->
                accountSession.update(_uiState, context) { it.copy(syncError = err) }
            }
        }
    }

    // --- User & Session (Delegated) ---

    // Exposed for delegation only
    private fun fetchCurrentUser() {
        val context = accountSession.current ?: return
        userDelegate.fetchCurrentUser { user ->
            if (!accountSession.isCurrent(context)) return@fetchCurrentUser
            // User fetched, now fetch related data that requires user name
            val name = user.name ?: return@fetchCurrentUser
            accountSession.readScope.launch { shortcutDelegate.fetchShortcuts(name) }
            accountSession.readScope.launch { webhookDelegate.fetchWebhooks(name) }

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
        val context = accountSession.current ?: return
        viewModelScope.launch {
            if (syncRepository.getOp(opId)?.accountId != context.account.id) return@launch
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
        val context = accountSession.current ?: return
        val accountId = context.account.id
        viewModelScope.launch {
            preDownloadManager.clearTextCache(accountId)
            if (!accountSession.isCurrent(context)) return@launch
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
        val context = accountSession.current ?: return
        val accountId = context.account.id
        viewModelScope.launch {
            preDownloadManager.clearTextCache(accountId)
            preDownloadManager.clearAttachmentCache(accountId)
            if (!accountSession.isCurrent(context)) return@launch
            refreshTextCacheCount()
            attachmentCacheManager.refreshUsage(accountId)
        }
    }

    /**
     * Refresh the number of locally cached memos shown in the cache analysis UI.
     */
    fun refreshTextCacheCount() {
        val context = accountSession.current ?: return
        val accountId = context.account.id
        viewModelScope.launch {
            val count = memoCacheRepository.getCachedCount(accountId)
            accountSession.update(_uiState, context) { it.copy(textCacheCount = count) }
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
        val context = accountSession.current ?: return NotificationsResult(emptyList())
        val currentApi = context.api
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
                if (!accountSession.isCurrent(context)) throw CancellationException("Account switched")
                val pageNotifications = response.notifications.orEmpty()
                notifications += pageNotifications
                nextPageToken = response.nextPageToken?.takeIf { it.isNotBlank() }

                // Persist the first successful page so the notifications screen
                // can show last-known data offline instead of an error. Only a
                // successful response is ever written - a failed fetch never
                // overwrites a good snapshot.
                if (isFirstPage) {
                    isFirstPage = false
                    context.account.id.let { accountId ->
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

            if (!accountSession.isCurrent(context)) throw CancellationException("Account switched")
            return NotificationsResult(notifications)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!accountSession.isCurrent(context)) throw CancellationException("Account switched")
            // Offline/failure: serve the last successful first page; the UI
            // badges it as cached instead of showing the error view.
            val accountId = context.account.id
            val snapshot = runCatching { notificationCacheStore.get(accountId) }.getOrNull()
            if (!accountSession.isCurrent(context)) throw CancellationException("Account switched")
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
