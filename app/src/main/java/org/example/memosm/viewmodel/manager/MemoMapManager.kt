package org.example.memosm.viewmodel.manager

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.api.resolveMemoOrderBy
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.sync.PendingOpType
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
import retrofit2.HttpException
import kotlinx.coroutines.flow.first

/** Full location history, independent of feed paging, with account-scoped capability discovery. */
class MemoMapManager(
    private val session: AccountSession,
    private val state: MutableStateFlow<MemosUiState>,
    private val cache: MemoCacheRepository,
    private val settings: DataStoreManager
) {
    private var probeJob: Job? = null
    private var loadJob: Job? = null
    private var opened = false
    private var loadRevision = 0L
    private val changed = mutableMapOf<String, Memo?>()

    fun reset() {
        loadRevision++
        probeJob?.cancel()
        loadJob?.cancel()
        opened = false
        changed.clear()
    }

    suspend fun restoreSupport(context: AccountContext) {
        val support = try {
            settings.snapshotJson("map_support", context.account.id).first()?.let {
                GsonProvider.gson.fromJson(it, MapSupport::class.java)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        val knownVersion = state.value.session.instanceProfile?.version
        if (support?.hostUrl == normalizeMapHost(context.account.hostUrl) &&
            (knownVersion == null || support.version == knownVersion)) {
            session.update(state, context) { it.copy(memoMap = it.memoMap.copy(
                capability = support.capability.takeIf { it == MapCapability.SUPPORTED || it == MapCapability.UNSUPPORTED }
                    ?: MapCapability.UNKNOWN
            )) }
        }
    }

    fun verify() {
        val context = session.current ?: return
        if (!context.networkReady || state.value.session.currUser == null ||
            state.value.connectionState != ConnectionState.ONLINE || probeJob?.isActive == true) return
        probeJob = session.readScope.launch {
            try {
                val profile = try { context.api.getInstanceProfile() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                val previous = settings.snapshotJson("map_support", context.account.id).first()?.let {
                    runCatching { GsonProvider.gson.fromJson(it, MapSupport::class.java) }.getOrNull()
                }
                if (previous != null && (previous.hostUrl != normalizeMapHost(context.account.hostUrl) ||
                        (profile != null && previous.version != profile.version))) {
                    loadRevision++
                    loadJob?.cancel()
                    session.update(state, context) { it.copy(memoMap = it.memoMap.copy(
                        capability = MapCapability.UNKNOWN, memos = emptyList(), complete = false
                    )) }
                }
                val result = context.api.listMemos(pageSize = 1, filter = "has_location", state = "NORMAL")
                // Some forks silently ignore unsupported filters. Do not enable their map when this is observable.
                val capability = if (result.memos.orEmpty().all { it.location != null })
                    MapCapability.SUPPORTED else MapCapability.UNSUPPORTED
                val version = if (profile != null) profile.version else previous?.version
                rememberSupport(context, MapSupport(normalizeMapHost(context.account.hostUrl), version, capability))
                if (capability == MapCapability.SUPPORTED && opened && session.isCurrent(context)) load()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (rejectsMapApi(error)) {
                    rememberSupport(context, MapSupport(normalizeMapHost(context.account.hostUrl),
                        state.value.session.instanceProfile?.version, MapCapability.UNSUPPORTED))
                }
                // Authentication, offline, throttling and server errors leave the last verified answer intact.
            }
        }
    }

    private suspend fun rememberSupport(context: AccountContext, support: MapSupport) {
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
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(isLoading = false)) }
    }

    fun selectScope(scope: MapScope) {
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(
            scope = scope, memos = emptyList(), savedView = null, complete = false
        )) }
        load()
    }

    fun selectView(view: Shortcut?) {
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(savedView = view, memos = emptyList(), complete = false)) }
        load()
    }

    fun upsert(memo: Memo) {
        val name = memo.name ?: return
        changed[name] = memo
        val context = session.current ?: return
        session.update(state, context) { current ->
            val map = current.memoMap
            val keep = map.memos.filterNot { it.name == name }
            current.copy(memoMap = map.copy(memos = sortedMapMemos(keep +
                listOf(memo).filter { map.savedView == null && it.belongsOnMap(map.scope, current.session.currUser?.name) })))
        }
        if (state.value.memoMap.savedView != null) refreshIfOpen()
    }

    fun forget(name: String) {
        changed[name] = null
        val context = session.current ?: return
        session.update(state, context) { it.copy(memoMap = it.memoMap.copy(memos = it.memoMap.memos.filterNot { m -> m.name == name })) }
    }

    fun refreshIfOpen() { if (opened) load() }

    fun connectionUnavailable() {
        probeJob?.cancel()
        loadJob?.cancel()
        if (opened) load()
    }

    fun load() {
        val context = session.current ?: return
        if (state.value.memoMap.capability != MapCapability.SUPPORTED) return
        val revision = ++loadRevision
        loadJob?.cancel()
        changed.clear()
        loadJob = session.readScope.launch {
            val map = state.value.memoMap
            val scope = map.scope
            val creator = state.value.session.currUser?.name
            val listType = when (scope) {
                MapScope.MEMOS -> CacheListType.MAP_USER
                MapScope.EXPLORE -> CacheListType.MAP_EXPLORE
                MapScope.ALL -> CacheListType.MAP_ALL
            }
            val fallbackTypes = when (scope) {
                MapScope.MEMOS -> listOf(CacheListType.USER, CacheListType.MAP_ALL)
                MapScope.EXPLORE -> listOf(CacheListType.EXPLORE, CacheListType.MAP_ALL)
                MapScope.ALL -> listOf(CacheListType.USER, CacheListType.EXPLORE, CacheListType.MAP_USER, CacheListType.MAP_EXPLORE)
            }
            val viewFilter = map.savedView?.filter?.takeIf { it.isNotBlank() }
            val online = context.networkReady && state.value.connectionState == ConnectionState.ONLINE
            val startedAt = System.currentTimeMillis()
            session.update(state, context) { it.copy(memoMap = it.memoMap.copy(
                isLoading = online, isOffline = !online, complete = false, loadFailed = false,
                filterUnavailable = !online && viewFilter != null,
                memos = if (!online && viewFilter != null) emptyList() else it.memoMap.memos
            )) }
            try {
                val cached = cache.getCachedMemos(context.account.id, listType)
                val feed = fallbackTypes.flatMap { cache.getCachedMemos(context.account.id, it) }
                if (!session.isCurrent(context) || revision != loadRevision) return@launch
                val local = (cached + feed).groupBy { it.name }.values.map { copies ->
                    copies.maxBy { it.updateTime ?: it.createTime ?: kotlin.time.Instant.DISTANT_PAST }
                }
                val pending = state.value.pendingOps.filter { it.accountId == context.account.id }
                pending.filter { it.type == PendingOpType.DELETE.name }.forEach { it.memoName?.let { name -> changed[name] = null } }
                pending.filter { it.type == PendingOpType.CREATE.name || it.type == PendingOpType.UPDATE.name }.forEach {
                    val memo = runCatching { GsonProvider.gson.fromJson(it.payloadJson, Memo::class.java) }.getOrNull()
                    if (memo != null && !memo.name.isNullOrBlank()) changed[memo.name] = memo
                }
                fun publish(memos: Collection<Memo>, complete: Boolean = false) {
                    if (!session.isCurrent(context) || revision != loadRevision) return
                    val byName = memos.associateBy { it.name }.toMutableMap()
                    changed.forEach { (name, memo) ->
                        if (memo == null) byName.remove(name)
                        else if (viewFilter == null || name in byName) byName[name] = memo
                    }
                    session.update(state, context) { current -> current.copy(memoMap = current.memoMap.copy(
                        memos = sortedMapMemos(byName.values.filter { it.belongsOnMap(scope, creator) }), complete = complete
                    )) }
                }
                if (viewFilter == null) publish(local)
                if (!online) return@launch
                val scopeFilter = when (scope) {
                    MapScope.MEMOS -> context.api.buildMemoCreatorFilter(state.value.session.currUser) ?: return@launch
                    MapScope.EXPLORE -> "visibility in ['PUBLIC', 'PROTECTED']"
                    MapScope.ALL -> null // The server enforces access; include every memo this account can read.
                }
                val filter = listOfNotNull(scopeFilter, "has_location", viewFilter).joinToString(" && ") { "($it)" }
                val received = linkedMapOf<String, Memo>()
                val tokens = mutableSetOf<String>()
                var token: String? = null
                do {
                    val response = context.api.listMemos(pageSize = 500, pageToken = token, state = "NORMAL",
                        filter = filter, orderBy = context.api.resolveMemoOrderBy(MemoOrderBy.NEWEST))
                    if (!session.isCurrent(context) || revision != loadRevision) return@launch
                    response.memos.orEmpty().forEach { memo -> memo.name?.let { received[it] = memo } }
                    if (viewFilter == null) {
                        val cachePage = response.memos.orEmpty().mapNotNull { memo ->
                            if (memo.name in changed) changed[memo.name] else memo
                        }.filter { it.belongsOnMap(scope, creator) }
                        cache.cacheMemos(context.account.id, listType, cachePage, replace = false)
                    }
                    publish(received.values)
                    token = response.nextPageToken?.takeIf { it.isNotBlank() }
                    check(token == null || tokens.add(token)) { "Repeated map page token" }
                } while (token != null)
                if (viewFilter == null) {
                    cache.pruneMissingFromList(context.account.id, listType,
                        cached.mapNotNull { it.name }.filterNot { it in received || it in changed }, startedAt)
                    cache.trimCachedMemos(context.account.id, listType, maxOf(500, state.value.appSettings.textCacheMaxMb * 50))
                }
                publish(received.values, complete = true)
                session.update(state, context) { it.copy(memoMap = it.memoMap.copy(isOffline = false)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (rejectsMapApi(error)) rememberSupport(context, MapSupport(normalizeMapHost(context.account.hostUrl),
                    state.value.session.instanceProfile?.version, MapCapability.UNSUPPORTED))
                session.update(state, context) { it.copy(memoMap = it.memoMap.copy(
                    loadFailed = true, isOffline = it.connectionState != ConnectionState.ONLINE
                )) }
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
    val message = error.response()?.errorBody()?.string().orEmpty()
    return Regex("(?:undeclared|unknown|unsupported|unrecognized)\\s+(?:reference\\s+to\\s+|(?:filter|field|variable|identifier)\\s*)?['\"`]?has_location\\b" +
        "|has_location['\"`]?\\s+(?:is\\s+)?(?:unsupported|unknown|not supported|unrecognized)", RegexOption.IGNORE_CASE)
        .containsMatchIn(message)
}
