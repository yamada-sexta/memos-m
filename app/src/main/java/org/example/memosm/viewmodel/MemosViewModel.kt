package org.example.memosm.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.example.memosm.MemosApplication
import org.example.memosm.api.*
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.linkpreview.LinkPreviewRepository
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.data.network.ConnectivityObserver
import org.example.memosm.data.offline.*
import org.example.memosm.data.paging.*
import org.example.memosm.data.sync.*
import org.example.memosm.model.*
import org.example.memosm.viewmodel.delegates.*
import org.example.memosm.viewmodel.manager.AttachmentManager
import org.example.memosm.viewmodel.manager.LocalSearchFilter
import org.example.memosm.viewmodel.manager.ServerReachabilityMonitor

@OptIn(ExperimentalCoroutinesApi::class)
class MemosViewModel(
    private val dataStoreManager: DataStoreManager,
    private val draftManager: DraftManager,
    private val memoCacheRepository: MemoCacheRepository,
    private val okHttpClient: OkHttpClient,
    private val connectivityObserver: ConnectivityObserver,
    private val attachmentCacheManager: AttachmentCacheManager,
    private val syncRepository: SyncRepository,
    private val syncWorkScheduler: SyncWorkScheduler,
    private val attachmentCacheStore: AttachmentCacheStore,
    private val sessionCacheStore: SessionCacheStore,
    private val notificationCacheStore: NotificationCacheStore,
    private val attachmentUploadQueue: AttachmentUploadQueue,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MemosUiState())
    val uiState = _uiState.asStateFlow()
    private val _sessionReady = MutableStateFlow(false)
    val sessionReady = _sessionReady.asStateFlow()
    private val _linkPreviews = MutableStateFlow<LinkPreviewRepository?>(null)
    val linkPreviews = _linkPreviews.asStateFlow()
    private val accountSession = AccountSession(viewModelScope)
    private val readEpoch = MutableStateFlow(0L)
    private val searchQuery = MutableStateFlow<MemoQuery?>(null)
    private val api: MemosApi? get() = accountSession.current?.takeIf { it.networkReady }?.api

    private fun activeAccountId() = accountSession.current?.account?.id

    val memoMapManager =
        org.example.memosm.viewmodel.manager
            .MemoMapManager(accountSession, _uiState, memoCacheRepository, dataStoreManager)

    private val attachmentManager =
        AttachmentManager(
            accountSession,
            uploadQueueProvider = { attachmentUploadQueue },
            draftReferenceChecker = { id -> activeAccountId()?.let { draftManager.draftsContain(it, id) } ?: true },
            outboxReferenceChecker = { id ->
                activeAccountId()?.let { account ->
                    syncRepository.getOps(account).any { it.payloadJson?.contains(id) == true }
                } ?: true
            },
        )

    private val reachabilityMonitor =
        ServerReachabilityMonitor(viewModelScope, { api }, ::activeAccountId) {
            connectivityObserver.refreshBlockedState()
            connectivityObserver.isBlocked.value
        }

    private fun scheduleAutomaticDownload() {
        activeAccountId()?.let { schedulePreDownload(MemosApplication.instance, it) }
    }

    val userDelegate: UserDelegate =
        UserDelegateImpl(
            viewModelScope,
            _uiState,
            dataStoreManager,
            sessionCacheStore,
            accountSession,
            onAccountSwitched = ::switchAccountInternal,
            onAccountRemoved = { account ->
                viewModelScope.launch {
                    syncWorkScheduler.cancel(account.id)
                    cancelPreDownload(MemosApplication.instance, account.id)
                    syncRepository.clearForAccount(account.id)
                    memoCacheRepository.clearCache(account.id)
                    attachmentUploadQueue.clearForAccount(account.id)
                    attachmentCacheManager.clearAccount(account.id)
                    attachmentCacheStore.clearMeta(account.id)
                    notificationCacheStore.clear(account.id)
                    sessionCacheStore.clear(account.id)
                    dataStoreManager.removeLastSyncTime(account.id)
                    dataStoreManager.removeDownloadState(account.id)
                    dataStoreManager.removeSnapshot("map_support", account.id)
                }
            },
        )

    val shortcutDelegate: ShortcutDelegate =
        ShortcutDelegateImpl(
            viewModelScope,
            _uiState,
            sessionCacheStore,
            accountSession,
            ::invalidateReads,
        )
    val webhookDelegate: WebhookDelegate = WebhookDelegateImpl(viewModelScope, _uiState, accountSession)
    val appSettingsDelegate: AppSettingsDelegate =
        AppSettingsDelegateImpl(
            viewModelScope,
            _uiState,
            dataStoreManager,
            onPageSizeChanged = ::invalidateReads,
            onOfflineSettingsChanged = { scheduleAutomaticDownload() },
        )
    val draftDelegate: DraftDelegate =
        DraftDelegateImpl(
            viewModelScope,
            _uiState,
            draftManager,
            accountSession,
            { memoActionDelegate },
            ::invalidateReads,
        )
    val memoActionDelegate: MemoActionDelegate =
        MemoActionDelegateImpl(
            viewModelScope,
            _uiState,
            draftDelegate,
            { attachmentManager },
            memoCacheRepository,
            accountSession,
            draftManager,
            syncWorkScheduler,
            accountExists = { id -> dataStoreManager.getAccounts().any { it.id == id } },
        )

    private fun pages(query: Flow<MemoQuery?>): Flow<PagingData<Memo>> =
        flow {
            var previousIdentity: Pair<AccountContext?, MemoQuery?>? = null
            emitAll(
                combine(
                    accountSession.contexts,
                    query,
                    uiState.map { it.session.currUser to it.connectionState }.distinctUntilChanged(),
                    uiState.map { it.appSettings.pageSize }.distinctUntilChanged(),
                    readEpoch,
                ) { account, request, user, size, epoch -> PagingRequest(account, request, user.first, size, epoch, user.second) }
                    .distinctUntilChanged()
                    .flatMapLatest { request ->
                        flow {
                            val identity = request.account to request.query
                            if (identity != previousIdentity) {
                                previousIdentity = identity
                                emit(PagingData.empty())
                            }
                            val account = request.account
                            val query = request.query
                            if (account != null && query != null) {
                                emitAll(
                                    memoPages(
                                        account,
                                        memoCacheRepository,
                                        query,
                                        request.user,
                                        request.pageSize,
                                        canFetch = {
                                            accountSession.isCurrent(account) && account.networkReady &&
                                                _uiState.value.connectionState == ConnectionState.ONLINE
                                        },
                                        isCurrent = { accountSession.isCurrent(account) },
                                    ),
                                )
                            }
                        }
                    },
            )
        }.cachedIn(viewModelScope)

    private data class PagingRequest(
        val account: AccountContext?,
        val query: MemoQuery?,
        val user: User?,
        val pageSize: Int,
        val epoch: Long,
        val connection: ConnectionState,
    )

    val userMemos =
        pages(
            uiState
                .map { state ->
                    MemoQuery(
                        CacheListType.USER,
                        hashtag = state.userMemoList.selectedHashtag,
                        serverFilter = state.userMemoList.selectedShortcut?.filter,
                    )
                }.distinctUntilChanged(),
        )
    val exploreMemos = pages(flowOf(MemoQuery(CacheListType.EXPLORE)))
    val archivedMemos = pages(flowOf(MemoQuery(CacheListType.ARCHIVED)))
    val searchMemos = pages(searchQuery)
    val comments =
        pages(
            uiState
                .map { it.detailPane.selectedMemo?.name }
                .distinctUntilChanged()
                .map { it?.let { name -> MemoQuery(CacheListType.COMMENT, parentName = name) } },
        )
    val attachments =
        combine(accountSession.contexts, readEpoch, uiState.map { it.connectionState }.distinctUntilChanged()) { account, _, _ -> account }
            .flatMapLatest { account ->
                if (account == null) {
                    flowOf(PagingData.empty())
                } else {
                    attachmentPages(
                        account,
                        attachmentCacheStore,
                        canFetch = {
                            accountSession.isCurrent(account) && account.networkReady &&
                                _uiState.value.connectionState == ConnectionState.ONLINE
                        },
                        isCurrent = { accountSession.isCurrent(account) },
                    )
                }
            }.cachedIn(viewModelScope)

    val commentSnapshot =
        combine(
            accountSession.contexts,
            uiState.map { it.detailPane.selectedMemo?.name }.distinctUntilChanged(),
        ) { account, parent -> account to parent }
            .flatMapLatest { (account, parent) ->
                if (account == null || parent == null) {
                    flowOf(null)
                } else {
                    memoCacheRepository.observeQuery(account.account.id, CacheListType.COMMENT.name + ":$parent")
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val unregisterBackupHook =
        BackupCoordinator.register { restoring ->
            if (restoring) {
                accountSession.clear()
                dataStoreManager.getAccounts().forEach { cancelPreDownload(MemosApplication.instance, it.id) }
                reachabilityMonitor.cancelProbe()
            } else {
                attachmentCacheManager.invalidateLocalIndex()
                if (BackupCoordinator.startupReady.value) {
                    switchAccountInternal(dataStoreManager.getAccounts().firstOrNull { it.isActive })
                    userDelegate.updateCurrentAccountInList()
                }
            }
        }

    init {
        viewModelScope.launch {
            BackupCoordinator.awaitStartupRecovery()
            if (BackupCoordinator.recoveryError.value == null) userDelegate.updateCurrentAccountInList()
            appSettingsDelegate.loadPageSize()
            appSettingsDelegate.loadHeaderScale()
            appSettingsDelegate.loadLinkPreviewEnabled()
            appSettingsDelegate.loadOfflineSettings()
            observeLifecycle()
        }
    }

    private fun invalidateReads() {
        readEpoch.update { it + 1 }
    }

    private suspend fun switchAccountInternal(account: Account?) {
        if (account != null &&
            (!BackupCoordinator.startupReady.value || BackupCoordinator.restoring.value || BackupCoordinator.recoveryError.value != null)
        ) {
            return
        }
        _sessionReady.value = false
        memoMapManager.reset()
        _linkPreviews.value = null
        accountSession.clear()
        reachabilityMonitor.cancelProbe()
        searchQuery.value = null
        _uiState.update { it.forAccount(account) }
        if (account == null) return
        val client = okHttpClient.newBuilder().addInterceptor(AuthInterceptor(account.accessToken)).build()
        val activation =
            accountSession.activate(
                account,
                MemosApiFactory.createLatest(account.hostUrl, client),
                client,
                networkReady = false,
            )
        userDelegate.restoreCachedSessionNow()
        memoMapManager.restoreSupport(activation)
        _sessionReady.value = true
        draftDelegate.loadDraftsForAccount(account.id)
        refreshTextCacheCount()
        accountSession.readScope.launch { attachmentCacheManager.refreshUsage(account.id) }
        accountSession.readScope.launch {
            try {
                val detected = MemosApiFactory.create(account.hostUrl, client)
                if (!accountSession.completeConnection(activation, detected)) return@launch
                _linkPreviews.value =
                    LinkPreviewRepository(
                        CoroutineScope(accountSession.readScope.coroutineContext),
                        detected::getLinkMetadata,
                        canFetch = { _uiState.value.isOnline && _uiState.value.appSettings.linkPreviewEnabled },
                    )
                syncRepository.retryAuthentication(account.id)
                attachmentUploadQueue.retryAuthentication(account.id)
                syncWorkScheduler.schedule(account.id)
                fetchCurrentUser()
                reachabilityMonitor.checkNow()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                accountSession.update(
                    _uiState,
                    activation,
                ) { it.copy(connectionState = ConnectionState.SERVER_UNREACHABLE, syncError = error.message) }
            }
        }
    }

    private fun recoverConnection() {
        if (!BackupCoordinator.startupReady.value || BackupCoordinator.restoring.value ||
            BackupCoordinator.recoveryError.value != null
        ) {
            return
        }
        if (reachabilityMonitor.state.value.connectionState != ConnectionState.ONLINE) return
        invalidateReads()
        activeAccountId()?.let(syncWorkScheduler::schedule)
        memoMapManager.verify()
        memoMapManager.refreshIfOpen()
        scheduleAutomaticDownload()
        if (_uiState.value.session.currUser == null) fetchCurrentUser()
    }

    private fun observeLifecycle() {
        reachabilityMonitor.start()
        viewModelScope.launch {
            var previous = ConnectionState.CHECKING
            reachabilityMonitor.state.collect { reachable ->
                _uiState.update {
                    it.copy(isOnline = reachable.isOnline, connectionState = reachable.connectionState, syncError = reachable.error)
                }
                if (reachable.connectionState == ConnectionState.ONLINE && previous != ConnectionState.ONLINE) {
                    recoverConnection()
                }
                previous = reachable.connectionState
            }
        }
        viewModelScope.launch {
            combine(connectivityObserver.isOnline, connectivityObserver.isBlocked) { online, blocked -> online to blocked }
                .distinctUntilChanged()
                .collect {
                    // Validated Internet is only a hint: local Memos servers may be reachable without it.
                    reachabilityMonitor.checkNow()
                }
        }
        viewModelScope.launch { ServerRateLimit.shared.changes.collect { reachabilityMonitor.checkNow() } }
        viewModelScope.launch {
            accountSession.contexts
                .flatMapLatest { account ->
                    if (account == null) {
                        flowOf(null to emptyList<PendingOp>())
                    } else {
                        syncRepository.getOpsFlow(account.account.id).map { account to it }
                    }
                }.collect { (account, ops) ->
                    if (account != null) {
                        accountSession.update(_uiState, account) { state ->
                            state.copy(
                                pendingOps = ops,
                                pendingOpsCount = ops.size,
                                conflict =
                                    state.conflict?.takeIf { old -> ops.any { it.id == old.opId } }
                                        ?: ops.firstNotNullOfOrNull { it.toConflict() },
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            accountSession.contexts
                .flatMapLatest { account ->
                    if (account == null) {
                        flowOf(false)
                    } else {
                        WorkManager
                            .getInstance(MemosApplication.instance)
                            .getWorkInfosByTagFlow(WorkManagerSyncWorkScheduler.workName(account.account.id))
                            .map { work -> work.any { it.state == WorkInfo.State.RUNNING } }
                    }
                }.distinctUntilChanged()
                .collect { running ->
                    val finished = _uiState.value.isSyncing && !running
                    _uiState.update { it.copy(isSyncing = running) }
                    if (finished) {
                        invalidateReads()
                        memoMapManager.refreshIfOpen()
                    }
                }
        }
        viewModelScope.launch {
            accountSession.contexts
                .flatMapLatest { account ->
                    if (account == null) {
                        flowOf(PreDownloadState.Idle)
                    } else {
                        WorkManager
                            .getInstance(MemosApplication.instance)
                            .getWorkInfosForUniqueWorkFlow(preDownloadWorkName(account.account.id))
                            .map { work ->
                                preDownloadState(
                                    work.firstOrNull { it.state == WorkInfo.State.RUNNING }
                                        ?: work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull(),
                                    account.account.id,
                                )
                            }
                    }
                }.collect { progress ->
                    _uiState.update { it.copy(preDownloadState = progress) }
                    if (progress is PreDownloadState.Done) refreshTextCacheCount()
                }
        }
        viewModelScope.launch {
            combine(accountSession.contexts, attachmentCacheManager.usage) { account, usage -> account to usage }
                .collect { (account, usage) ->
                    if (account != null && usage.accountId == account.account.id) {
                        accountSession.update(_uiState, account) { it.copy(attachmentCacheUsage = usage.usage) }
                    }
                }
        }
        viewModelScope.launch {
            accountSession.contexts
                .flatMapLatest { account ->
                    dataStoreManager.lastSyncTime(account?.account?.id).map { account to it }
                }.collect { (account, time) ->
                    if (account !=
                        null
                    ) {
                        accountSession.update(_uiState, account) { it.copy(lastSyncTime = time) }
                    }
                }
        }
        viewModelScope.launch {
            combine(accountSession.contexts, uiState.map { it.detailPane.selectedMemo?.name }.distinctUntilChanged()) { account, name ->
                account to
                    name
            }.flatMapLatest { (account, name) ->
                if (account == null || name == null) {
                    emptyFlow()
                } else {
                    memoCacheRepository.observeMemo(account.account.id, name).map { account to it }
                }
            }.collect { (account, memo) ->
                accountSession.update(_uiState, account) { state ->
                    state.copy(detailPane = state.detailPane.copy(selectedMemo = memo))
                }
            }
        }
    }

    private fun fetchCurrentUser() {
        val account = accountSession.current ?: return
        userDelegate.fetchCurrentUser { user ->
            if (!accountSession.isCurrent(account)) return@fetchCurrentUser
            memoMapManager.verify()
            user.name?.let { name ->
                accountSession.readScope.launch { shortcutDelegate.fetchShortcuts(name) }
                accountSession.readScope.launch { webhookDelegate.fetchWebhooks(name) }
            }
            scheduleAutomaticDownload()
            invalidateReads()
        }
    }

    fun retryAccountConnection() {
        viewModelScope.launch {
            val account = accountSession.current ?: return@launch
            val saved = dataStoreManager.getAccounts().firstOrNull { it.id == account.account.id && it.isActive }
            if (saved != null && accountSession.isCurrent(account)) switchAccountInternal(saved)
        }
    }

    fun onForeground() {
        if (BackupCoordinator.startupReady.value && !BackupCoordinator.restoring.value && BackupCoordinator.recoveryError.value == null) {
            invalidateReads()
            memoMapManager.refreshIfOpen()
            reachabilityMonitor.checkNow()
        }
    }

    private fun requestRefresh(
        source: RefreshSource,
        explicit: Boolean,
    ) {
        if (explicit) _uiState.update { it.copy(refreshSource = source, refreshTrigger = System.currentTimeMillis()) }
        invalidateReads()
        reachabilityMonitor.checkNow()
    }

    fun fetchUserMemos(refresh: Boolean = false) = requestRefresh(RefreshSource.USerMemos, refresh)

    fun fetchExploreMemos(refresh: Boolean = false) = requestRefresh(RefreshSource.ExploreMemos, refresh)

    fun fetchArchivedMemos(refresh: Boolean = false) = requestRefresh(RefreshSource.ArchivedMemos, refresh)

    fun fetchAttachments(refresh: Boolean = false) = requestRefresh(RefreshSource.Attachments, refresh)

    fun searchMemos(
        filter: MemoSearchFilter,
        orderBy: MemoOrderBy? = null,
        localFilter: LocalSearchFilter = LocalSearchFilter(),
    ) {
        searchQuery.value =
            MemoQuery(CacheListType.SEARCH, search = filter, explore = localFilter.explore, order = orderBy ?: MemoOrderBy.NEWEST)
    }

    fun refreshAfterProfileDetails() {
        viewModelScope.launch {
            userDelegate.reconcileAccount()
            userDelegate.restoreCachedSessionNow(replace = true)
            refreshTextCacheCount()
            refreshAttachmentCacheUsage()
            if (_uiState.value.isOnline) fetchCurrentUser()
            invalidateReads()
        }
    }

    fun refreshAfterEditor() {
        activeAccountId()?.let(draftDelegate::loadDraftsForAccount)
        invalidateReads()
        memoMapManager.refreshIfOpen()
    }

    fun deletePendingOp(opId: String) {
        viewModelScope.launch {
            val account = accountSession.current ?: return@launch
            val op = syncRepository.getOp(opId)?.takeIf { it.accountId == account.account.id } ?: return@launch
            syncRepository.discard(op, memoCacheRepository)
            syncWorkScheduler.schedule(account.account.id)
        }
    }

    fun retryPendingChanges() {
        val account = accountSession.current ?: return
        viewModelScope.launch {
            syncRepository.retryAll(account.account.id)
            attachmentUploadQueue.retryAll(account.account.id)
            syncWorkScheduler.schedule(account.account.id)
        }
    }

    fun reviewConflict(opId: String) {
        val account = accountSession.current ?: return
        viewModelScope.launch {
            val op = syncRepository.getOp(opId)?.takeIf { it.accountId == account.account.id } ?: return@launch
            val conflict = op.toConflict() ?: return@launch
            accountSession.update(_uiState, account) { it.copy(conflict = conflict) }
        }
    }

    fun resolveConflict(
        resolution: ConflictResolution,
        mergedContent: String? = null,
    ) {
        viewModelScope.launch {
            val conflict = _uiState.value.conflict ?: return@launch
            syncRepository.resolveConflict(conflict, resolution, mergedContent, memoCacheRepository)
            _uiState.update { it.copy(conflict = null) }
            if (resolution != ConflictResolution.LATER) activeAccountId()?.let(syncWorkScheduler::schedule)
        }
    }

    fun dismissConflict() {
        _uiState.update { it.copy(conflict = null) }
    }

    fun cacheNow(
        text: Boolean,
        attachments: Boolean,
    ) {
        activeAccountId()?.let { schedulePreDownload(MemosApplication.instance, it, text, attachments, manual = true) }
    }

    fun clearTextCache() {
        activeAccountId()?.let { id ->
            viewModelScope.launch {
                org.example.memosm.data.sync
                    .clearTextCache(id, memoCacheRepository, dataStoreManager)
                refreshTextCacheCount()
            }
        }
    }

    fun clearAttachmentCache() {
        activeAccountId()?.let { id ->
            viewModelScope.launch {
                attachmentCacheManager.clearAccount(id)
            }
        }
    }

    fun clearAllCaches() {
        clearTextCache()
        clearAttachmentCache()
    }

    fun refreshTextCacheCount() {
        val account = accountSession.current ?: return
        viewModelScope.launch {
            val count = memoCacheRepository.getCachedCount(account.account.id)
            accountSession.update(_uiState, account) { it.copy(textCacheCount = count) }
        }
    }

    fun refreshAttachmentCacheUsage() {
        activeAccountId()?.let { id -> viewModelScope.launch { attachmentCacheManager.refreshUsage(id) } }
    }

    fun updateAttachmentCellWidth(width: Float) {
        _uiState.update { it.copy(attachmentList = it.attachmentList.copy(cellWidth = width)) }
    }

    fun updateAttachmentAspectRatio(
        accountId: String?,
        generation: Long,
        key: String,
        ratio: Float,
    ) {
        val account = accountSession.current ?: return
        if (account.account.id != accountId || _uiState.value.accountGeneration != generation || !ratio.isFinite() || ratio <= 0f) return
        accountSession.update(_uiState, account) {
            it.copy(
                attachmentList =
                    it.attachmentList.copy(
                        aspectRatios =
                            it.attachmentList.aspectRatios + (key to ratio),
                    ),
            )
        }
    }

    suspend fun listCurrentUserNotifications(maxItems: Int = 100): NotificationsResult {
        val context = accountSession.current ?: return NotificationsResult(emptyList())
        val currentApi = context.api
        val userName =
            _uiState.value.session.currUser
                ?.name
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

                val response =
                    currentApi.listUserNotifications(
                        user = userName,
                        pageSize = remaining,
                        pageToken = nextPageToken,
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
                                    savedAt = System.currentTimeMillis(),
                                ),
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
                    fromCache = true,
                )
            }
            throw e
        }
    }

    override fun onCleared() {
        memoMapManager.reset()
        reachabilityMonitor.stop()
        unregisterBackupHook()
        super.onCleared()
    }
}
