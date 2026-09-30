package org.example.memosm.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.model.Memo
import org.example.memosm.model.User
import java.time.Instant

private const val TAG = "PreDownloadManager"
private const val FULL_PAGE_SIZE = 100

/**
 * How long a completed pre-download suppresses auto re-downloads. Kept both in
 * memory (cheap, same-process) and in DataStore (survives process restarts).
 */
private const val AUTO_DOWNLOAD_COOLDOWN_MS = 30 * 60 * 1000L

/**
 * Minimum interval between published Running progress updates. Progress is
 * only informational for the UI; sampling it keeps fast paging/preload loops
 * from flooding collectors with per-page/per-item emissions.
 */
private const val PROGRESS_EMIT_INTERVAL_MS = 500L

/**
 * User-configurable pre-download settings (mirrors AppSettings fields).
 */
data class OfflineSettings(
    val preDownloadText: Boolean = true,
    val preDownloadAttachments: Boolean = true,
    val preDownloadWifiOnly: Boolean = true,
    val preDownloadExplore: Boolean = false
)

sealed class PreDownloadState {
    data object Idle : PreDownloadState()

    data class Running(
        val phase: Phase,
        val page: Int = 0,
        val accountId: String? = null
    ) : PreDownloadState() {
        enum class Phase { TEXT, ATTACHMENTS }
    }

    data class Done(
        val timestamp: Long,
        val textCount: Int = 0,
        val attachmentCount: Int = 0,
        val accountId: String? = null
    ) : PreDownloadState()

    data class Failed(
        val message: String,
        val accountId: String? = null
    ) : PreDownloadState()
}

/**
 * Downloads the full text history (all pages of the user's memos, including
 * archived ones, and optionally the explore feed) into the local cache, so
 * offline browsing and search cover everything - not just what was viewed.
 *
 * Triggers: auto on login/account switch, network recovery, app foreground
 * (when the settings allow it); manual via the sync settings card.
 */
class PreDownloadManager(
    private val scope: CoroutineScope,
    private val memoCacheRepository: MemoCacheRepository,
    private val dataStoreManager: DataStoreManager,
    private val attachmentCacheManager: AttachmentCacheManager,
    private val attachmentCacheStore: AttachmentCacheStore,
    private val apiProvider: () -> MemosApi?,
    private val accountIdProvider: () -> String?,
    private val userProvider: () -> User?,
    private val hostUrlProvider: () -> String,
    private val tokenProvider: () -> String,
    private val isOnlineProvider: () -> Boolean,
    private val isWifiProvider: () -> Boolean
) {

    private val _state = MutableStateFlow<PreDownloadState>(PreDownloadState.Idle)
    val state: StateFlow<PreDownloadState> = _state.asStateFlow()

    private var job: Job? = null

    // Last time a Running progress update was published; progress is sampled
    // (see maybeEmitProgress) so a fast paging loop cannot flood collectors.
    private var lastProgressEmitAt = 0L

    /**
     * Publish [state] at most once per [PROGRESS_EMIT_INTERVAL_MS]. The first
     * update of a run always goes through; Done/Failed/Idle are emitted
     * directly (never sampled) so terminal states are always delivered.
     */
    private fun maybeEmitProgress(state: PreDownloadState.Running) {
        val now = System.currentTimeMillis()
        if (now - lastProgressEmitAt < PROGRESS_EMIT_INTERVAL_MS) return
        lastProgressEmitAt = now
        _state.value = state
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (_state.value is PreDownloadState.Running) {
            _state.value = PreDownloadState.Idle
        }
    }

    val isRunning: Boolean
        get() = job?.isActive == true

    /**
     * Read pre-download settings straight from DataStore. The UI state is
     * populated by an async collect after process start, so reading the
     * settings from there during early startup races with the DataStore
     * loading and can see stale defaults (e.g. wifi-only=true while the user
     * disabled it) - a fresh launch would then skip the pre-download it was
     * supposed to run.
     */
    private suspend fun readOfflineSettings(): OfflineSettings = OfflineSettings(
        preDownloadText = dataStoreManager.preDownloadText.first(),
        preDownloadAttachments = dataStoreManager.preDownloadAttachments.first(),
        preDownloadWifiOnly = dataStoreManager.preDownloadWifiOnly.first(),
        preDownloadExplore = dataStoreManager.preDownloadExplore.first()
    )

    /**
     * Auto-triggered entry point: downloads text (if enabled) and then
     * attachments (if enabled). Skipped when a full text download already
     * finished recently (30 min) to avoid re-downloading on every reconnect.
     * The cooldown is scoped to the account that performed the download:
     * switching to another account must not be starved of its own
     * pre-download because a different account finished one.
     */
    fun maybeAutoDownload() {
        val current = _state.value
        if (current is PreDownloadState.Done &&
            current.accountId == accountIdProvider() &&
            System.currentTimeMillis() - current.timestamp < AUTO_DOWNLOAD_COOLDOWN_MS
        ) {
            return
        }
        val accountId = accountIdProvider()
        scope.launch {
            // The in-memory cooldown above is lost on process restart; the
            // persisted one keeps a fresh launch right after a completed
            // pre-download from re-downloading everything.
            if (accountId != null) {
                val lastAt = dataStoreManager.lastPreDownloadAt(accountId).first()
                if (lastAt > 0L &&
                    System.currentTimeMillis() - lastAt < AUTO_DOWNLOAD_COOLDOWN_MS
                ) {
                    Log.d(TAG, "maybeAutoDownload skipped (recent pre-download at $lastAt)")
                    return@launch
                }
            }
            val settings = readOfflineSettings()
            if (settings.preDownloadText) {
                downloadAllText()
            } else if (settings.preDownloadAttachments) {
                preloadAllAttachments()
            }
        }
    }

    /**
     * Worker-safe variant of [maybeAutoDownload]: applies the same gates
     * (persisted cooldown, settings toggles) and then suspends until the
     * download run finishes, so a WorkManager caller can await completion on
     * its own scope instead of fire-and-forget on a UI scope.
     */
    suspend fun runAutoDownloadBlocking() {
        val accountId = accountIdProvider() ?: return
        val lastAt = dataStoreManager.lastPreDownloadAt(accountId).first()
        if (lastAt > 0L &&
            System.currentTimeMillis() - lastAt < AUTO_DOWNLOAD_COOLDOWN_MS
        ) {
            Log.d(TAG, "runAutoDownloadBlocking skipped (recent pre-download at $lastAt)")
            return
        }
        val settings = readOfflineSettings()
        if (settings.preDownloadText) {
            downloadAllText()
        } else if (settings.preDownloadAttachments) {
            preloadAllAttachments()
        }
        job?.join()
    }

    /**
     * Download every page of the user's memos (and archived ones, plus the
     * explore feed when enabled) into the local cache.
     *
     * Incremental: when a previous full download exists (textSyncCursor > 0),
     * only memos whose update_time is newer than the cursor are fetched and
     * merged into the existing cache, so reconnects don't re-download the whole
     * history. Servers that reject the update_time filter (old versions) fall
     * back to a full download automatically.
     */
    fun downloadAllText() {
        if (job?.isActive == true) return
        if (!isOnlineProvider()) return
        val accountId = accountIdProvider() ?: return
        val api = apiProvider() ?: return

        job = scope.launch {
            lastProgressEmitAt = 0L
            val settings = readOfflineSettings()
            if (settings.preDownloadWifiOnly && !isWifiProvider()) {
                Log.d(TAG, "downloadAllText skipped (wifi-only enabled, not on wifi)")
                _state.value = PreDownloadState.Idle
                return@launch
            }
            _state.value = PreDownloadState.Running(
                PreDownloadState.Running.Phase.TEXT, accountId = accountId
            )
            try {
                val user = userProvider()
                if (user == null) {
                    // User identity is not ready yet (login just happened) - the
                    // creator filter cannot be built, so nothing can be downloaded.
                    // Stay Idle instead of marking Done, otherwise the 30-minute
                    // cooldown would swallow the retry triggered after the user
                    // is fetched.
                    Log.d(TAG, "downloadAllText skipped (user identity not ready)")
                    _state.value = PreDownloadState.Idle
                    return@launch
                }
                // Incremental cursor is owned exclusively by the pre-downloader
                // (see TEXT_SYNC_CURSOR); SyncManager's lastSyncTime updates
                // must not shrink this window. Also require a non-empty cache:
                // if the DB was cleared while the cursor survived (e.g. system
                // cache eviction), an incremental run would permanently miss
                // everything older than the cursor.
                // Additionally, only run incrementally within a 24h window:
                // memos deleted on the server (by another device) are invisible
                // to an incremental fetch, so a full (replace) run at least
                // every 24h reconciles the cache with the server's current
                // list and evicts stale rows.
                val lastSync = dataStoreManager.textSyncCursor.first()
                val cachedCount = memoCacheRepository.getCachedCount(accountId)
                val incremental = lastSync > 0L && cachedCount > 0 &&
                    System.currentTimeMillis() - lastSync < 24 * 60 * 60 * 1000L
                val textCapMb = dataStoreManager.textCacheMaxMb.first()
                // Rough per-tier cap: ~50 memos per MB, floored at 500.
                val textKeep = maxOf(500, textCapMb * 50)
                var totalText = 0
                val filter = api.buildMemoCreatorFilter(user)
                if (!filter.isNullOrBlank()) {
                    totalText += downloadList(
                        api, accountId, CacheListType.USER, filter, state = null,
                        incrementalSince = if (incremental) lastSync else null
                    )
                    memoCacheRepository.trimCachedMemos(accountId, CacheListType.USER, textKeep)
                    totalText += downloadList(
                        api, accountId, CacheListType.ARCHIVED, filter, state = "ARCHIVED",
                        incrementalSince = if (incremental) lastSync else null
                    )
                    memoCacheRepository.trimCachedMemos(accountId, CacheListType.ARCHIVED, textKeep)
                }
                if (settings.preDownloadExplore) {
                    totalText += downloadList(
                        api,
                        accountId,
                        CacheListType.EXPLORE,
                        "visibility in ['PUBLIC', 'PROTECTED']",
                        state = null,
                        incrementalSince = if (incremental) lastSync else null
                    )
                    memoCacheRepository.trimCachedMemos(accountId, CacheListType.EXPLORE, textKeep)
                }

                var attachmentCount = 0
                if (settings.preDownloadAttachments) {
                    _state.value =
                        PreDownloadState.Running(PreDownloadState.Running.Phase.ATTACHMENTS)
                    attachmentCount = preloadAllAttachmentsInternal(accountId, api)
                }
                _state.value = PreDownloadState.Done(
                    timestamp = System.currentTimeMillis(),
                    textCount = totalText,
                    attachmentCount = attachmentCount,
                    accountId = accountId
                )
                dataStoreManager.saveTextSyncCursor(System.currentTimeMillis())
                dataStoreManager.saveLastPreDownloadAt(
                    accountId, System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "downloadAllText failed", e)
                _state.value = PreDownloadState.Failed(
                    e.message ?: "Unknown error", accountId = accountId
                )
            }
        }
    }

    /**
     * Preload attachments for a set of memos that just became visible
     * (list fetch success / detail view).
     */
    fun preloadVisibleAttachments(memos: List<Memo>) {
        if (!isOnlineProvider()) return
        val accountId = accountIdProvider() ?: return
        val hostUrl = hostUrlProvider()
        val token = tokenProvider()
        scope.launch {
            // Read the toggle from DataStore (see readOfflineSettings for why
            // the UI-state snapshot is not used here).
            if (!dataStoreManager.preDownloadAttachments.first()) return@launch
            attachmentCacheManager.preload(hostUrl, token, accountId, memos)
        }
    }

    /**
     * Manual: download attachments of every memo currently in the cache.
     */
    fun preloadAllAttachments() {
        if (job?.isActive == true) return
        if (!isOnlineProvider()) return
        val accountId = accountIdProvider() ?: return
        val api = apiProvider() ?: return
        job = scope.launch {
            lastProgressEmitAt = 0L
            _state.value = PreDownloadState.Running(
                PreDownloadState.Running.Phase.ATTACHMENTS, accountId = accountId
            )
            try {
                val count = preloadAllAttachmentsInternal(accountId, api)
                _state.value = PreDownloadState.Done(
                    timestamp = System.currentTimeMillis(),
                    attachmentCount = count,
                    accountId = accountId
                )
                dataStoreManager.saveLastPreDownloadAt(
                    accountId, System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _state.value = PreDownloadState.Failed(
                    e.message ?: "Unknown error", accountId = accountId
                )
            }
        }
    }

    /**
     * Clear the locally cached memo text for the account.
     * Resets the incremental-sync cursor so the next pre-download starts a
     * fresh full download instead of only fetching memos changed since the
     * last sync (which would miss the full history after a cache wipe).
     * Also clears the persisted cooldown: with the cache gone the next
     * pre-download must not be suppressed by a recent (pre-clear) run.
     */
    suspend fun clearTextCache(accountId: String) {
        memoCacheRepository.clearCache(accountId)
        dataStoreManager.saveTextSyncCursor(0L)
        dataStoreManager.saveLastPreDownloadAt(accountId, 0L)
        _state.value = PreDownloadState.Idle
    }

    /**
     * Clear downloaded attachments for the account.
     */
    suspend fun clearAttachmentCache(accountId: String) {
        attachmentCacheManager.clearAccount(accountId)
    }

    private suspend fun downloadList(
        api: MemosApi,
        accountId: String,
        listType: CacheListType,
        filter: String,
        state: String?,
        incrementalSince: Long? = null
    ): Int {
        var count = 0
        var pageToken: String? = null
        var page = 1
        var useIncremental = incrementalSince != null && incrementalSince > 0L
        var effectiveFilter = filter
        if (useIncremental) {
            effectiveFilter =
                "$filter && update_time >= \"${Instant.ofEpochMilli(incrementalSince!!)}\""
            Log.d(TAG, "downloadList incremental since=$incrementalSince: $effectiveFilter")
        }
        do {
            maybeEmitProgress(
                PreDownloadState.Running(
                    PreDownloadState.Running.Phase.TEXT, page, accountId
                )
            )
            val response = try {
                api.listMemos(
                    pageSize = FULL_PAGE_SIZE,
                    pageToken = pageToken,
                    filter = effectiveFilter,
                    state = state
                )
            } catch (e: Exception) {
                if (useIncremental) {
                    // Old server versions may reject the update_time filter;
                    // fall back to a full download (still merged, never wiping
                    // memos that were already cached for this list). A
                    // pageToken from the rejected filter is meaningless for
                    // the plain one, so restart paging from the first page.
                    Log.w(TAG, "update_time filter rejected, falling back to full download", e)
                    useIncremental = false
                    effectiveFilter = filter
                    pageToken = null
                    page = 1
                    api.listMemos(
                        pageSize = FULL_PAGE_SIZE,
                        pageToken = null,
                        filter = effectiveFilter,
                        state = state
                    )
                } else {
                    throw e
                }
            }
            val memos = response.memos.orEmpty()
            // replace=true only for a full (non-incremental) paging run, where
            // the last page means the whole list has been seen. Incremental runs
            // merge so older cached memos are never lost.
            memoCacheRepository.cacheMemos(
                accountId, listType, memos,
                replace = pageToken == null && !useIncremental
            )
            count += memos.size
            pageToken = response.nextPageToken?.takeIf { it.isNotBlank() }
            page++
        } while (pageToken != null && memos.isNotEmpty())
        return count
    }

    private suspend fun preloadAllAttachmentsInternal(accountId: String, api: MemosApi): Int {
        val hostUrl = hostUrlProvider()
        val token = tokenProvider()
        val allMemos =
            memoCacheRepository.getCachedMemos(accountId, CacheListType.USER) +
                memoCacheRepository.getCachedMemos(accountId, CacheListType.ARCHIVED)
        attachmentCacheManager.preload(hostUrl, token, accountId, allMemos)
        // Attachments known only from the metadata table (no cached memo
        // references them, e.g. harvested earlier or listed on the Resources
        // page) would otherwise never be pre-downloaded. Dedupe happens twice:
        // the exists() filter here keeps the batch small, and download()
        // itself skips already-cached files, so repeats are cheap no-ops.
        val missing = attachmentCacheStore.getCachedMeta(accountId).filter { attachment ->
            val name = attachment.name ?: return@filter false
            !attachmentCacheStore.exists(accountId, name)
        }
        if (missing.isNotEmpty()) {
            Log.d(TAG, "preloading ${missing.size} metadata-only attachments")
            attachmentCacheManager.preloadAttachments(
                hostUrl, token, accountId,
                missing.map { it to (it.memo ?: "") }
            )
        }
        return attachmentCacheManager.usage.value.count
    }
}
