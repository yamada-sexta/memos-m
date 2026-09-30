package org.example.memosm.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.model.Memo
import org.example.memosm.model.User

private const val TAG = "SyncManager"

/**
 * Replays queued offline writes against the server once connectivity is back,
 * detects content conflicts (server modified while we were offline) and lets
 * the user decide how to resolve them.
 *
 * Ops are processed in creation order so chained edits (create -> update ->
 * delete) apply correctly. A failed op does not block the rest of the queue;
 * it keeps its lastError and is retried on the next sync.
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val repository: SyncRepository,
    private val memoCacheRepository: MemoCacheRepository,
    private val dataStoreManager: DataStoreManager,
    private val workScheduler: SyncWorkScheduler,
    private val auditLogger: SyncAuditLogger,
    private val apiProvider: () -> MemosApi?,
    private val accountIdProvider: () -> String?,
    private val currentUserProvider: () -> User?,
    private val isOnlineProvider: () -> Boolean,
    private val attachmentUploadQueueProvider: () -> AttachmentUploadQueue? = { null },
    private val onMemoSynced: suspend (memo: Memo, tempName: String?) -> Unit,
    private val onMemoDeleted: suspend (memoName: String) -> Unit,
    private val onCommentsRefresh: suspend () -> Unit,
    private val onConflict: (ConflictItem) -> Unit
) {

    private val gson = GsonProvider.gson

    private val _pendingOps = MutableStateFlow<List<PendingOp>>(emptyList())
    val pendingOps: StateFlow<List<PendingOp>> = _pendingOps.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private var syncJob: Job? = null
    private var retryJob: Job? = null

    /**
     * Serializes op processing: a background sync and a user conflict decision
     * must never touch the same op concurrently (double push, or a re-triggered
     * conflict for an op that was just resolved).
     */
    private val opMutex = Mutex()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun startObserving(accountIdFlow: Flow<String?>) {
        scope.launch {
            accountIdFlow.flatMapLatest { accountId ->
                if (accountId == null) flowOf(emptyList())
                else repository.getOpsFlow(accountId)
            }.collect { ops ->
                _pendingOps.value = ops
            }
        }
    }

    suspend fun enqueue(op: PendingOp) {
        repository.enqueue(op)
        auditLogger.record(op.accountId, "QUEUE", "ENQUEUED", op.type, op.memoName)
        workScheduler.schedule(op.accountId)
    }

    suspend fun deleteOp(id: String) {
        repository.getOp(id)?.let { auditLogger.record(it.accountId, "QUEUE", "DISCARDED", it.type, it.memoName) }
        repository.deleteOp(id)
    }

    suspend fun clearForAccount(accountId: String) = repository.clearForAccount(accountId)

    fun cancelSync() {
        syncJob?.cancel()
        retryJob?.cancel()
        _isSyncing.value = false
    }

    /**
     * Attempt to replay all queued ops for the active account.
     * No-op when offline or already syncing.
     *
     * [force] bypasses the retry guards (permanently-failed ops and the
     * exponential backoff window) - used for explicit user actions
     * (Sync Now button, pull-to-refresh, conflict resolved).
     */
    fun syncNow(force: Boolean = false) {
        if (!isOnlineProvider() || _isSyncing.value) return
        val accountId = accountIdProvider() ?: return
        syncJob?.cancel()
        syncJob = scope.launch {
            _isSyncing.value = true
            try {
                opMutex.withLock {
                    val api = apiProvider() ?: return@withLock
                    var ops = repository.getOps(accountId)
                    val executor = newExecutor(api, accountId, currentUserProvider())
                    if (ops.isNotEmpty()) {
                        Log.d(TAG, "syncNow: syncing ${ops.size} ops")
                    }
                    val now = System.currentTimeMillis()
                    var index = 0
                    while (index < ops.size) {
                        // Reload after each acknowledgement so temporary-name
                        // remaps and rebased follow-ups are used immediately.
                        val opId = ops[index].id
                        val op = repository.getOp(opId) ?: run {
                            ops = repository.getOps(accountId)
                            index++
                            continue
                        }
                        if (!force) {
                            // Ops the server rejected with a 4xx will never succeed
                            // by retrying - leave them queued (visible in the UI)
                            // until the user discards or force-syncs.
                            if (op.permanentlyFailed) {
                                index++
                                continue
                            }
                            // Exponential backoff: 30s, 1m, 2m, 4m, 8m, capped at 10m.
                            // A failing op would otherwise be retried on every
                            // foreground/resume/network-recovery.
                            val backoffMillis = minOf(
                                30_000L shl minOf(op.attemptCount, 4), 600_000L
                            )
                            if (now - op.lastAttemptAt < backoffMillis) {
                                index++
                                continue
                            }
                        }
                        try {
                            val success = executor.replay(op)
                            if (success) {
                                repository.deleteOp(op.id)
                                auditLogger.record(accountId, "SYNC", "SUCCESS", op.type, op.memoName)
                                ops = repository.getOps(accountId)
                                continue
                            }
                            index++
                        } catch (e: Exception) {
                            Log.e(TAG, "Op ${op.id} (${op.type}) failed", e)
                            // 4xx means the server deterministically rejected the
                            // op - retrying is pointless, mark it permanent. 5xx
                            // and transport errors are transient: keep retrying
                            // with backoff. 408/429 are transient too (timeout /
                            // rate limit), matching OutboxSyncWorker and
                            // AttachmentUploadQueue.
                            val permanent = e is retrofit2.HttpException &&
                                e.code() in 400..499 && e.code() !in setOf(408, 429)
                            repository.markFailed(
                                op.id, op.attemptCount + 1, e.message,
                                System.currentTimeMillis(), permanent
                            )
                            auditLogger.record(
                                accountId,
                                "SYNC",
                                if (permanent) "REJECTED" else "RETRY",
                                op.type,
                                op.memoName,
                                failureCode(e)
                            )
                            if (!permanent) {
                                scheduleRetry(accountId, op.attemptCount + 1)
                            }
                            index++
                        }
                    }
                    dataStoreManager.saveLastSyncTime(accountId, System.currentTimeMillis())
                }
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun scheduleRetry(accountId: String, attemptCount: Int) {
        if (!isOnlineProvider()) return
        val delayMs = minOf(30_000L shl minOf(attemptCount, 4), 600_000L)
        workScheduler.schedule(accountId)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (accountIdProvider() == accountId && isOnlineProvider()) {
                syncNow()
            }
        }
    }

    private fun failureCode(error: Exception): String = when (error) {
        is retrofit2.HttpException -> "http_${error.code()}"
        else -> error.javaClass.simpleName.take(64)
    }

    /**
     * Apply the user's decision for a detected conflict. [mergedContent] is
     * required for [ConflictResolution.MERGE]: the user-edited third version
     * combining the local and server content.
     */
    fun resolveConflict(
        item: ConflictItem,
        resolution: ConflictResolution,
        mergedContent: String? = null
    ) {
        scope.launch {
            val accountId = accountIdProvider() ?: return@launch
            val api = apiProvider() ?: return@launch
            opMutex.withLock {
                val executor = newExecutor(api, accountId, currentUserProvider())
                when (resolution) {
                    ConflictResolution.KEEP_LOCAL -> {
                        val op = repository.getOp(item.opId) ?: return@withLock
                        val local = gson.fromJson(op.payloadJson, Memo::class.java)
                            ?: return@withLock
                        try {
                            val updated = api.updateMemo(
                                item.memoName, local, op.updateMask ?: "content"
                            )
                            // Follow-up edits were based on the same local version;
                            // re-anchor them to the freshly-applied server state so
                            // they don't re-trigger a conflict on the next sync.
                            executor.anchorFollowUps(
                                item.memoName, op.baseUpdateTime, updated.updateTime
                            )
                            executor.cacheMemo(updated)
                            repository.deleteOp(item.opId)
                            onMemoSynced(updated, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "Keep-local push failed for $item.memoName", e)
                            // Leave the op queued and re-surface the conflict so the
                            // user can retry or defer.
                            onConflict(item)
                        }
                    }

                    ConflictResolution.MERGE -> {
                        val op = repository.getOp(item.opId) ?: return@withLock
                        val local = gson.fromJson(op.payloadJson, Memo::class.java)
                            ?: return@withLock
                        if (mergedContent == null) {
                            // MERGE without a merged version would silently
                            // overwrite the server version with the local one;
                            // refuse and re-surface the conflict instead.
                            Log.e(TAG, "MERGE resolution without mergedContent for ${item.memoName}")
                            onConflict(item)
                            return@withLock
                        }
                        val merged = local.copy(content = mergedContent)
                        try {
                            val updated = api.updateMemo(
                                item.memoName, merged, op.updateMask ?: "content"
                            )
                            executor.anchorFollowUps(
                                item.memoName, op.baseUpdateTime, updated.updateTime
                            )
                            executor.cacheMemo(updated)
                            repository.deleteOp(item.opId)
                            onMemoSynced(updated, null)
                        } catch (e: Exception) {
                            Log.e(TAG, "Merge push failed for $item.memoName", e)
                            onConflict(item)
                        }
                    }

                    ConflictResolution.KEEP_SERVER -> {
                        val op = repository.getOp(item.opId) ?: return@withLock
                        repository.deleteOp(item.opId)
                        // Re-anchor follow-up edits (queued while offline, based
                        // on the discarded local version) to the adopted server
                        // state so they apply on top of it instead of re-triggering
                        // a conflict for the same memo.
                        executor.anchorFollowUps(
                            item.memoName, op.baseUpdateTime,
                            item.serverMemo.updateTime
                        )
                        executor.cacheMemo(item.serverMemo)
                        onMemoSynced(item.serverMemo, null)
                    }

                    ConflictResolution.LATER -> {
                        // Leave the op queued; it will re-trigger a conflict on next sync.
                    }
                }
            }
        }
    }

    /**
     * Per-op replay logic is shared with [OutboxSyncWorker] via
     * [OpReplayExecutor]; this wires the UI-facing callbacks (conflict
     * surfacing, comment refresh, memo update/delete notifications) into it.
     */
    private fun newExecutor(api: MemosApi, accountId: String, currentUser: User?) =
        OpReplayExecutor(
            api = api,
            repository = repository,
            memoCacheRepository = memoCacheRepository,
            auditLogger = auditLogger,
            accountId = accountId,
            currentUser = currentUser,
            attachmentUploadQueueProvider = attachmentUploadQueueProvider,
            onMemoSynced = onMemoSynced,
            onMemoDeleted = onMemoDeleted,
            onCommentsRefresh = onCommentsRefresh,
            onConflict = onConflict
        )
}
