package org.example.memosm.viewmodel.delegates

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.MemosApplication
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.ReactionOpPayload
import org.example.memosm.data.sync.SyncManager
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.Reaction
import org.example.memosm.model.UpsertMemoReactionRequest
import org.example.memosm.model.User
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.UiMessage
import org.example.memosm.viewmodel.manager.AttachmentManager
import org.example.memosm.viewmodel.manager.CommentListManager
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Instant
import java.util.UUID

interface MemoActionDelegate {
    fun selectMemo(memo: Memo?)
    fun clearSelectedMemo()
    fun createMemo(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>? = null,
        location: Location? = null,
        memoId: String? = null,
        onError: () -> Unit = {},
        onSuccess: () -> Unit = {}
    )

    fun updateMemo(
        memo: Memo,
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location? = null,
        state: MemoState? = null,
        onSuccess: () -> Unit = {}
    )

    fun deleteMemo(memo: Memo, onSuccess: () -> Unit = {})
    fun updateMemoPinned(memo: Memo, pinned: Boolean, onSuccess: () -> Unit = {})
    fun createComment(parentMemo: Memo, content: String, onSuccess: () -> Unit = {})
    suspend fun uploadAttachment(uri: Uri, context: Context): Attachment?

    /** Discard a queued offline upload once nothing references its placeholder anymore. */
    fun discardQueuedUploadIfOrphaned(clientId: String)
    fun upsertMemoReaction(memo: Memo, reactionType: String)
    fun deleteMemoReaction(memo: Memo, reaction: Reaction)
}

interface MemoListUpdater {
    fun updateMemoInLists(memo: Memo)
    fun removeMemoFromLists(memoName: String)
    fun refreshUserMemos()
    fun handleMemoStateChange(memo: Memo, updated: Memo)
    fun insertMemoIntoUserList(memo: Memo)
}

class MemoActionDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val apiProvider: () -> MemosApi?,
    private val listUpdater: MemoListUpdater,
    private val draftDelegate: DraftDelegate,
    private val attachmentManagerProvider: () -> AttachmentManager?,
    private val commentManagerProvider: () -> CommentListManager?,
    private val syncManager: SyncManager,
    private val memoCacheRepository: MemoCacheRepository,
    private val accountIdProvider: () -> String?,
    private val currentUserProvider: () -> User?,
    private val isOnlineProvider: () -> Boolean
) : MemoActionDelegate {

    private val api: MemosApi? get() = apiProvider()
    private val attachmentManager: AttachmentManager? get() = attachmentManagerProvider()
    private val commentManager: CommentListManager? get() = commentManagerProvider()
    private val gson = GsonProvider.gson

    private fun toastOfflineSaved() {
        Toast.makeText(
            MemosApplication.instance,
            MemosApplication.instance.getString(R.string.offline_saved_message),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** True when the failure is connectivity-related and safe to queue offline. */
    private fun shouldQueueOffline(e: Exception): Boolean =
        !isOnlineProvider() || e is IOException

    /** Keep the upstream UI error localized while retaining a diagnostic log. */
    private fun reportOperationFailure(e: Exception) {
        Log.e("MemosViewModel", "Operation failed", e)
        uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
    }

    /**
     * Queue [op] for durable replay, apply the optimistic local change, report
     * success and tell the user the change was saved offline. Shared by the
     * known-offline fast path and the online-attempt fallback of every write
     * so the two copies cannot drift apart.
     */
    private suspend fun applyOffline(
        op: PendingOp,
        onSuccess: () -> Unit = {},
        applyOptimistic: suspend () -> Unit
    ) {
        syncManager.enqueue(op)
        applyOptimistic()
        onSuccess()
        toastOfflineSaved()
    }

    private fun currentAccountId(): String? = accountIdProvider()

    private fun currentUser(): User? = currentUserProvider()

    override fun selectMemo(memo: Memo?) {
        uiState.update {
            it.copy(detailPane = it.detailPane.copy(selectedMemo = memo))
        }
        if (memo != null) {
            commentManager?.setMemo(memo.name ?: "")
        }
    }

    override fun clearSelectedMemo() = selectMemo(null)

    override fun createMemo(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>?,
        location: Location?,
        memoId: String?,
        onError: () -> Unit,
        onSuccess: () -> Unit
    ) {
        val draftIdToDelete = uiState.value.draft.currentEditingDraftId
        val accountId = currentAccountId()
        // One idempotency key per user create action: the online attempt and any
        // offline-queue fallback must reuse the same memoId so a replay after a
        // timeout cannot create a duplicate server-side. Callers with their own
        // stable identity (e.g. draft publishing) can supply [memoId].
        val clientId = memoId ?: UUID.randomUUID().toString()
        val memo = Memo(
            content = content,
            visibility = visibility,
            attachments = attachments,
            location = location
        )
        scope.launch {
            if (accountId != null && !isOnlineProvider()) {
                // Offline: queue the create and show the memo locally right away.
                val tempName = "offline-${UUID.randomUUID()}"
                // Stamp local timestamps so the optimistic memo sorts to the
                // top of the list (null displayTime would sort to the bottom).
                val now = Clock.System.now()
                val localMemo = memo.copy(
                    name = tempName,
                    state = MemoState.NORMAL,
                    createTime = now,
                    updateTime = now,
                    displayTime = now
                )
                applyOffline(
                    PendingOp.new(
                        accountId = accountId,
                        type = PendingOpType.CREATE,
                        memoName = tempName,
                        payloadJson = gson.toJson(localMemo)
                    ),
                    onSuccess
                ) {
                    applyLocalCreate(localMemo, draftIdToDelete)
                }
                return@launch
            }

            try {
                uiState.update { it.copy(isPosting = true) }
                val created = api?.createMemo(memo, clientId)
                if (created != null) {
                    draftIdToDelete?.let(draftDelegate::deleteDraft)
                    if (uiState.value.draft.currentEditingDraftId == draftIdToDelete) {
                        draftDelegate.setCurrentEditingDraft(null)
                    }
                    onSuccess()
                    listUpdater.refreshUserMemos()
                    // Keep the local cache fresh for offline browsing.
                    accountId?.let {
                        memoCacheRepository.upsertCachedMemo(it, created, CacheListType.USER)
                    }
                    uiState.update {
                        it.copy(
                            draft = it.draft.copy(
                                composerResetToken = System.currentTimeMillis().toInt()
                            )
                        )
                    }
                } else {
                    // No memo came back (e.g. no API bound): report failure so
                    // callers awaiting a result (draft publishing) don't hang.
                    onError()
                }
            } catch (e: Exception) {
                if (accountId != null && shouldQueueOffline(e)) {
                    val tempName = "offline-${UUID.randomUUID()}"
                    // Stamp local timestamps so the optimistic memo sorts to the
                    // top of the list (null displayTime would sort to the bottom).
                    val now = Clock.System.now()
                    val localMemo = memo.copy(
                        name = tempName,
                        state = MemoState.NORMAL,
                        createTime = now,
                        updateTime = now,
                        displayTime = now
                    )
                    applyOffline(
                        PendingOp.new(
                            accountId = accountId,
                            type = PendingOpType.CREATE,
                            memoName = tempName,
                            payloadJson = gson.toJson(localMemo),
                            // The server may already have created this memo before
                            // the request failed; reuse the same memoId so the
                            // replay deduplicates instead of creating a duplicate.
                            id = clientId
                        ),
                        onSuccess
                    ) {
                        applyLocalCreate(localMemo, draftIdToDelete)
                    }
                } else {
                    reportOperationFailure(e)
                    onError()
                }
            } finally {
                uiState.update { it.copy(isPosting = false) }
            }
        }
    }

    private suspend fun applyLocalCreate(localMemo: Memo, draftIdToDelete: String?) {
        val accountId = currentAccountId()
        if (accountId != null) {
            memoCacheRepository.upsertCachedMemo(
                accountId, localMemo, CacheListType.USER, order = 0
            )
        }
        listUpdater.insertMemoIntoUserList(localMemo)
        draftIdToDelete?.let(draftDelegate::deleteDraft)
        if (uiState.value.draft.currentEditingDraftId == draftIdToDelete) {
            draftDelegate.setCurrentEditingDraft(null)
        }
        uiState.update {
            it.copy(
                draft = it.draft.copy(
                    composerResetToken = System.currentTimeMillis().toInt()
                )
            )
        }
    }

    override fun updateMemo(
        memo: Memo,
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location?,
        state: MemoState?,
        onSuccess: () -> Unit
    ) {
        val accountId = currentAccountId()
        val name = memo.name ?: return
        val update = memo.copy(
            content = content,
            visibility = visibility,
            attachments = attachments,
            location = location,
            state = state
        )
        val maskParts = mutableListOf("content", "visibility", "attachments", "location")
        if (state != null) {
            maskParts.add("state")
        }
        val mask = maskParts.joinToString(",")
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.UPDATE,
                memoName = name,
                payloadJson = gson.toJson(update),
                updateMask = mask,
                baseUpdateTime = memo.updateTime?.toString()
            )
        }

        scope.launch {
            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp, onSuccess) {
                    applyLocalUpdate(memo, update)
                }
                return@launch
            }

            try {
                val updated = api?.updateMemo(name, update, mask)
                if (updated != null) {
                    onSuccess()

                    // Handle local list moves if state changed
                    val oldState = memo.state ?: MemoState.NORMAL
                    val newState = updated.state ?: MemoState.NORMAL

                    if (oldState != newState) {
                        listUpdater.handleMemoStateChange(memo, updated)
                    }

                    listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(updated)
                }
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp, onSuccess) {
                        applyLocalUpdate(memo, update)
                    }
                } else {
                    reportOperationFailure(e)
                }
            }
        }
    }

    private suspend fun applyLocalUpdate(oldMemo: Memo, updated: Memo) {
        val oldState = oldMemo.state ?: MemoState.NORMAL
        val newState = updated.state ?: MemoState.NORMAL
        if (oldState != newState) {
            listUpdater.handleMemoStateChange(oldMemo, updated)
        } else {
            listUpdater.updateMemoInLists(updated)
        }
        cacheLocalMemo(updated)
    }

    private suspend fun cacheLocalMemo(memo: Memo) {
        val accountId = currentAccountId() ?: return
        val name = memo.name ?: return
        // Atomic cross-list-type upsert (single transaction) - the previous
        // remove+upsert pair could race a concurrent writer and lose the row.
        memoCacheRepository.upsertCachedMemoState(
            accountId,
            memo,
            if (memo.state == MemoState.ARCHIVED) CacheListType.ARCHIVED else CacheListType.USER
        )
    }

    override fun deleteMemo(memo: Memo, onSuccess: () -> Unit) {
        val accountId = currentAccountId()
        val name = memo.name ?: return
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.DELETE,
                memoName = name
            )
        }
        scope.launch {
            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp, onSuccess) {
                    memoCacheRepository.removeCachedMemo(accountId, name)
                    listUpdater.removeMemoFromLists(name)
                }
                return@launch
            }

            try {
                api?.deleteMemo(name)
                onSuccess()

                // Local update: Remove from all lists
                listUpdater.removeMemoFromLists(name)
                accountId?.let { memoCacheRepository.removeCachedMemo(it, name) }
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp, onSuccess) {
                        memoCacheRepository.removeCachedMemo(accountId, name)
                        listUpdater.removeMemoFromLists(name)
                    }
                } else {
                    reportOperationFailure(e)
                }
            }
        }
    }

    override fun updateMemoPinned(memo: Memo, pinned: Boolean, onSuccess: () -> Unit) {
        val accountId = currentAccountId()
        val name = memo.name ?: return
        val update = memo.copy(pinned = pinned)
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.UPDATE,
                memoName = name,
                payloadJson = gson.toJson(update),
                updateMask = "pinned",
                baseUpdateTime = memo.updateTime?.toString()
            )
        }
        scope.launch {
            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp, onSuccess) {
                    listUpdater.updateMemoInLists(update)
                    cacheLocalMemo(update)
                }
                return@launch
            }

            try {
                val updated = api?.updateMemo(name, update, "pinned")
                if (updated != null) {
                    onSuccess()
                    listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(updated)
                }
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp, onSuccess) {
                        listUpdater.updateMemoInLists(update)
                        cacheLocalMemo(update)
                    }
                } else {
                    reportOperationFailure(e)
                }
            }
        }
    }

    override fun createComment(parentMemo: Memo, content: String, onSuccess: () -> Unit) {
        val accountId = currentAccountId()
        val parentName = parentMemo.name ?: return
        val comment = Memo(content = content, visibility = parentMemo.visibility, parent = parentName)
        val tempName = "offline-comment-${UUID.randomUUID()}"
        val localComment = comment.copy(name = tempName, parent = parentName)
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.COMMENT_CREATE,
                memoName = tempName,
                parentName = parentName,
                payloadJson = gson.toJson(localComment)
            )
        }
        scope.launch {
            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp, onSuccess) {
                    applyLocalComment(localComment, parentName)
                }
                return@launch
            }

            try {
                api?.createMemoComment(parentName, comment)
                onSuccess()
                commentManager?.fetch(refresh = true)
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp, onSuccess) {
                        applyLocalComment(localComment, parentName)
                    }
                } else {
                    reportOperationFailure(e)
                }
            }
        }
    }

    /**
     * Show the optimistic comment and persist it so it survives restarts / a
     * failed sync until the server list refresh replaces it.
     */
    private suspend fun applyLocalComment(localComment: Memo, parentName: String) {
        commentManager?.upsert(
            localComment,
            { it.name == localComment.name },
            compareBy { it.createTime }
        )
        val accountId = currentAccountId() ?: return
        memoCacheRepository.upsertCachedMemo(
            accountId, localComment, CacheListType.COMMENT, parentName = parentName
        )
    }

    override suspend fun uploadAttachment(uri: Uri, context: Context): Attachment? {
        return attachmentManager?.uploadAttachment(uri, context)
    }

    override fun discardQueuedUploadIfOrphaned(clientId: String) {
        scope.launch { attachmentManager?.discardQueuedUploadIfOrphaned(clientId) }
    }

    override fun upsertMemoReaction(memo: Memo, reactionType: String) {
        val accountId = currentAccountId()
        val name = memo.name ?: return
        val mine = currentUser()?.name
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.REACTION_UPSERT,
                memoName = name,
                payloadJson = gson.toJson(
                    ReactionOpPayload(reactionType = reactionType, creator = mine)
                )
            )
        }
        scope.launch {
            // Optimistic local update for instant feedback (online or offline).
            val optimistic = memo.copy(
                reactions = withLocalReactions(memo, reactionType, mine, add = true)
            )
            listUpdater.updateMemoInLists(optimistic)

            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp) {
                    cacheLocalMemo(optimistic)
                }
                return@launch
            }

            try {
                val reaction = Reaction(contentId = name, reactionType = reactionType)
                val request = UpsertMemoReactionRequest(name = memo.name, reaction = reaction)
                api?.upsertMemoReaction(name, request)

                // Fetch latest memo state to be sure about all reactions and update in-place
                val updated = api?.getMemo(name)
                if (updated != null) {
                    listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(updated)
                }
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp) {
                        cacheLocalMemo(optimistic)
                    }
                } else {
                    // The server rejected the request (e.g. 401/404): roll the
                    // optimistic reaction back and surface the error, instead of
                    // leaving a reaction on screen that was never applied.
                    reportOperationFailure(e)
                    listUpdater.updateMemoInLists(memo)
                }
            }
        }
    }

    override fun deleteMemoReaction(memo: Memo, reaction: Reaction) {
        val accountId = currentAccountId()
        val name = memo.name ?: return
        val mine = currentUser()?.name
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.REACTION_DELETE,
                memoName = name,
                payloadJson = gson.toJson(
                    ReactionOpPayload(reactionType = reaction.reactionType, creator = mine)
                )
            )
        }
        scope.launch {
            // Optimistic local update for instant feedback (online or offline).
            val optimistic = memo.copy(
                reactions = memo.reactions.orEmpty().filterNot {
                    it.reactionType == reaction.reactionType && (mine == null || it.creator == mine)
                }
            )
            listUpdater.updateMemoInLists(optimistic)

            if (accountId != null && pendingOp != null && !isOnlineProvider()) {
                applyOffline(pendingOp) {
                    cacheLocalMemo(optimistic)
                }
                return@launch
            }

            try {
                val reactionName = reaction.name ?: return@launch
                api?.deleteMemoReaction(reactionName)

                // Fetch latest memo state and update in-place
                val updated = api?.getMemo(name)
                if (updated != null) {
                    listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(updated)
                }
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(e)) {
                    applyOffline(pendingOp) {
                        cacheLocalMemo(optimistic)
                    }
                } else {
                    // The server rejected the request (e.g. 401/404): roll the
                    // optimistic removal back and surface the error, instead of
                    // leaving the reaction hidden while it still exists on the server.
                    reportOperationFailure(e)
                    listUpdater.updateMemoInLists(memo)
                }
            }
        }
    }

    /**
     * Rebuild the reaction list after (un)toggling [reactionType] for the
     * current user, mirroring the server's upsert semantics.
     */
    private fun withLocalReactions(
        memo: Memo, reactionType: String, mine: String?, add: Boolean
    ): List<Reaction> {
        val current = memo.reactions.orEmpty()
        val filtered = current.filterNot {
            it.reactionType == reactionType && (mine == null || it.creator == mine)
        }
        if (!add) return filtered
        return filtered + Reaction(
            contentId = memo.name ?: "", reactionType = reactionType, creator = mine
        )
    }
}
