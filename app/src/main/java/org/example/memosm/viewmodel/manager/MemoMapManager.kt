package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.resolveMemoOrderBy
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.cache.defaultQueryKey
import org.example.memosm.model.MapCapability
import org.example.memosm.model.MapScope
import org.example.memosm.model.MapSupport
import org.example.memosm.model.Memo
import org.example.memosm.model.Shortcut
import org.example.memosm.model.belongsOnMap
import org.example.memosm.model.normalizeMapHost
import org.example.memosm.model.sortedMapMemos
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession
import org.example.memosm.viewmodel.ConnectionState
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.isOffline
import retrofit2.HttpException

/** Full location history, independent of feed paging, with account-scoped capability discovery. */
class MemoMapManager(
    private val session: AccountSession,
    private val state: MutableStateFlow<MemosUiState>,
    private val cache: MemoCacheRepository,
    private val settings: DataStoreManager,
) {
    private var probeJob: Job? = null
    private var loadJob: Job? = null
    private var observationJob: Job? = null
    private var opened = false
    private var loadRevision = 0L

    fun reset() {
        loadRevision++
        probeJob?.cancel()
        loadJob?.cancel()
        observationJob?.cancel()
        opened = false
        observationJob?.cancel()
    }

    suspend fun restoreSupport(context: AccountContext) {
        val support =
            try {
                settings.snapshotJson("map_support", context.account.id).first()?.let {
                    GsonProvider.gson.fromJson(it, MapSupport::class.java)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        val knownVersion =
            state.value.session.instanceProfile
                ?.version
        if (support?.hostUrl == normalizeMapHost(context.account.hostUrl) &&
            (knownVersion == null || support.version == knownVersion)
        ) {
            session.update(state, context) {
                it.copy(
                    memoMap =
                        it.memoMap.copy(
                            capability =
                                support.capability.takeIf { it == MapCapability.SUPPORTED || it == MapCapability.UNSUPPORTED }
                                    ?: MapCapability.UNKNOWN,
                        ),
                )
            }
        }
    }

    fun verify() {
        val context = session.current ?: return
        if (!context.networkReady || state.value.session.currUser == null ||
            state.value.connectionState != ConnectionState.ONLINE || probeJob?.isActive == true
        ) {
            return
        }
        probeJob =
            session.readScope.launch {
                try {
                    val profile =
                        try {
                            context.api.getInstanceProfile()
                        } catch (
                            cancelled: CancellationException,
                        ) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    val previous =
                        settings.snapshotJson("map_support", context.account.id).first()?.let {
                            runCatching { GsonProvider.gson.fromJson(it, MapSupport::class.java) }.getOrNull()
                        }
                    if (previous != null && (
                            previous.hostUrl != normalizeMapHost(context.account.hostUrl) ||
                                (profile != null && previous.version != profile.version)
                        )
                    ) {
                        loadRevision++
                        loadJob?.cancel()
                        session.update(state, context) {
                            it.copy(
                                memoMap =
                                    it.memoMap.copy(
                                        capability = MapCapability.UNKNOWN,
                                        memos = emptyList(),
                                        complete = false,
                                    ),
                            )
                        }
                    }
                    val result = context.api.listMemos(pageSize = 1, filter = "has_location", state = "NORMAL")
                    // Some forks silently ignore unsupported filters. Do not enable their map when this is observable.
                    val capability =
                        if (result.memos.orEmpty().all { it.location != null }) {
                            MapCapability.SUPPORTED
                        } else {
                            MapCapability.UNSUPPORTED
                        }
                    val version = if (profile != null) profile.version else previous?.version
                    rememberSupport(context, MapSupport(normalizeMapHost(context.account.hostUrl), version, capability))
                    if (capability == MapCapability.SUPPORTED && opened && session.isCurrent(context)) load()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (rejectsMapApi(error)) {
                        rememberSupport(
                            context,
                            MapSupport(
                                normalizeMapHost(context.account.hostUrl),
                                state.value.session.instanceProfile
                                    ?.version,
                                MapCapability.UNSUPPORTED,
                            ),
                        )
                    }
                    // Authentication, offline, throttling and server errors leave the last verified answer intact.
                }
            }
    }

    private suspend fun rememberSupport(
        context: AccountContext,
        support: MapSupport,
    ) {
        if (!session.isCurrent(context)) return
        settings.saveSnapshotJson("map_support", context.account.id, GsonProvider.gson.toJson(support))
        if (!session.isCurrent(context)) return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(capability = support.capability)) }
        if (support.capability != MapCapability.SUPPORTED) loadJob?.cancel()
    }

    fun open() {
        opened = true
        load()
    }

    fun close() {
        opened = false
        loadRevision++
        loadJob?.cancel()
        observationJob?.cancel()
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(isLoading = false)) }
    }

    fun selectScope(scope: MapScope) {
        val context = session.current ?: return
        session.update(state, context) {
            it.copy(
                memoMap =
                    it.memoMap.copy(
                        scope = scope,
                        memos = emptyList(),
                        savedView = null,
                        complete = false,
                    ),
            )
        }
        load()
    }

    fun selectView(view: Shortcut?) {
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(savedView = view, memos = emptyList(), complete = false)) }
        load()
    }

    fun refreshIfOpen() {
        if (opened) load()
    }

    fun connectionUnavailable() {
        probeJob?.cancel()
        loadJob?.cancel()
        if (opened) load()
    }

    fun load() {
        val context = session.current ?: return
        val storageGeneration = BackupCoordinator.generation
        if (state.value.memoMap.capability != MapCapability.SUPPORTED) return
        val revision = ++loadRevision
        loadJob?.cancel()
        observationJob?.cancel()
        val map = state.value.memoMap
        val scope = map.scope
        val creator =
            state.value.session.currUser
                ?.name
        val listType =
            when (scope) {
                MapScope.MEMOS -> CacheListType.MAP_USER
                MapScope.EXPLORE -> CacheListType.MAP_EXPLORE
                MapScope.ALL -> CacheListType.MAP_ALL
            }
        val sourceTypes =
            when (scope) {
                MapScope.MEMOS -> {
                    listOf(listType, CacheListType.USER, CacheListType.MAP_ALL)
                }

                MapScope.EXPLORE -> {
                    listOf(listType, CacheListType.EXPLORE, CacheListType.MAP_ALL)
                }

                MapScope.ALL -> {
                    listOf(
                        listType,
                        CacheListType.USER,
                        CacheListType.EXPLORE,
                        CacheListType.MAP_USER,
                        CacheListType.MAP_EXPLORE,
                    )
                }
            }
        val viewFilter = map.savedView?.filter?.takeIf { it.isNotBlank() }
        val queryKey = defaultQueryKey(listType) + viewFilter?.let { ":view:$it" }.orEmpty()
        val online = context.networkReady && state.value.connectionState == ConnectionState.ONLINE
        val offline = state.value.connectionState.isOffline
        session.update(state, context) {
            it.copy(
                memoMap =
                    it.memoMap.copy(
                        isLoading = online,
                        isOffline = offline,
                        complete = false,
                        loadFailed = false,
                        filterUnavailable = offline && viewFilter != null,
                        memos = if (offline && viewFilter != null) emptyList() else it.memoMap.memos,
                    ),
            )
        }
        if (offline && viewFilter != null) return

        observationJob =
            session.readScope.launch {
                try {
                    val values =
                        if (viewFilter == null) {
                            cache.observeCachedMemos(context.account.id, sourceTypes)
                        } else {
                            cache.observeQueryMemos(context.account.id, queryKey)
                        }
                    // Both flows trigger invalidation; read rows and completion together in one Room transaction.
                    combine(values, cache.observeQuery(context.account.id, queryKey)) { _, _ -> Unit }
                        .collect {
                            val (memos, query) =
                                cache.readCollectionSnapshot(
                                    context.account.id,
                                    queryKey,
                                    sourceTypes,
                                    allowFallback = viewFilter == null,
                                )
                            if (revision == loadRevision) {
                                session.update(state, context) {
                                    it.copy(
                                        memoMap =
                                            it.memoMap.copy(
                                                memos = sortedMapMemos(memos.filter { memo -> memo.belongsOnMap(scope, creator) }),
                                                complete = query?.complete == true,
                                            ),
                                    )
                                }
                            }
                        }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    _: Exception,
                ) {
                    session.update(state, context) { it.copy(memoMap = it.memoMap.copy(loadFailed = true)) }
                }
            }
        if (!online) return
        loadJob =
            session.readScope.launch {
                try {
                    val scopeFilter =
                        when (scope) {
                            MapScope.MEMOS -> context.api.buildMemoCreatorFilter(state.value.session.currUser) ?: return@launch
                            MapScope.EXPLORE -> "visibility in ['PUBLIC', 'PROTECTED']"
                            MapScope.ALL -> null
                        }
                    val filter = listOfNotNull(scopeFilter, "has_location", viewFilter).joinToString(" && ") { "($it)" }
                    val generation =
                        BackupCoordinator.withStorageLock {
                            if (!session.isCurrent(context) || storageGeneration != BackupCoordinator.generation) {
                                throw CancellationException("Account storage changed")
                            }
                            cache.beginRefresh(context.account.id, queryKey, listType)
                        }
                    val tokens = mutableSetOf<String>()
                    var token: String? = null
                    do {
                        val response =
                            context.api.listMemos(
                                pageSize = 500,
                                pageToken = token,
                                state = "NORMAL",
                                filter = filter,
                                orderBy = context.api.resolveMemoOrderBy(MemoOrderBy.NEWEST),
                            )
                        if (!session.isCurrent(context) || revision != loadRevision) return@launch
                        val next = response.nextPageToken?.takeIf { it.isNotBlank() }
                        check(next == null || tokens.add(next)) { "Repeated map page token" }
                        BackupCoordinator.withStorageLock {
                            if (!session.isCurrent(context) || revision != loadRevision ||
                                storageGeneration != BackupCoordinator.generation
                            ) {
                                throw CancellationException("Account storage changed")
                            }
                            cache.ingestPage(
                                context.account.id,
                                queryKey,
                                listType,
                                response.memos ?: error("Server omitted map memos"),
                                nextPageToken = next,
                                refresh = token == null,
                                expectedGeneration = generation,
                                expectedPageToken = token,
                            )
                        }
                        token = next
                    } while (token != null)
                    session.update(state, context) { it.copy(memoMap = it.memoMap.copy(isOffline = false)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (rejectsMapApi(error)) {
                        rememberSupport(
                            context,
                            MapSupport(
                                normalizeMapHost(context.account.hostUrl),
                                state.value.session.instanceProfile
                                    ?.version,
                                MapCapability.UNSUPPORTED,
                            ),
                        )
                    }
                    session.update(state, context) {
                        it.copy(
                            memoMap =
                                it.memoMap.copy(loadFailed = true, isOffline = it.connectionState.isOffline),
                        )
                    }
                } finally {
                    if (revision == loadRevision) session.update(state, context) { it.copy(memoMap = it.memoMap.copy(isLoading = false)) }
                }
            }
    }
}

/** Only an explicit API/filter incompatibility is evidence of missing support. */
internal fun rejectsMapApi(error: Exception): Boolean {
    if (error !is HttpException) return false
    if (error.code() == 404 || error.code() == 501) return true
    if (error.code() != 400) return false
    val message =
        error
            .response()
            ?.errorBody()
            ?.string()
            .orEmpty()
    return Regex(
        "(?:undeclared|unknown|unsupported|unrecognized)\\s+(?:reference\\s+to\\s+|(?:filter|field|variable|identifier)\\s*)?['\"`]?has_location\\b" +
            "|has_location['\"`]?\\s+(?:is\\s+)?(?:unsupported|unknown|not supported|unrecognized)",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(message)
}
