package org.example.memosm.viewmodel.delegates

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession
import org.example.memosm.data.DraftManager
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

private fun toastOfflineSaved() {
    Toast.makeText(
        MemosApplication.instance,
        MemosApplication.instance.getString(R.string.offline_saved_message),
        Toast.LENGTH_SHORT
    ).show()
}


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
        onError: () -> Unit = {},
        onSuccess: () -> Unit = {}
    )

    fun deleteMemo(memo: Memo, onSuccess: () -> Unit = {})
    fun updateMemoPinned(memo: Memo, pinned: Boolean, onSuccess: () -> Unit = {})
    fun createComment(parentMemo: Memo, content: String, onError: () -> Unit = {}, onSuccess: () -> Unit = {})
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
    private val listUpdater: MemoListUpdater,
    private val draftDelegate: DraftDelegate,
    private val attachmentManagerProvider: () -> AttachmentManager?,
    private val commentManagerProvider: () -> CommentListManager?,
    private val syncManager: SyncManager,
    private val memoCacheRepository: MemoCacheRepository,
    private val accountSession: AccountSession,
    private val draftManager: DraftManager,
    private val isOnlineProvider: () -> Boolean,
    private val notifyOfflineSaved: () -> Unit = ::toastOfflineSaved
) : MemoActionDelegate {

    private val attachmentManager: AttachmentManager? get() = attachmentManagerProvider()
    private val commentManager: CommentListManager? get() = commentManagerProvider()
    private val gson = GsonProvider.gson


    /** True when the failure is connectivity-related and safe to queue offline. */
    private fun shouldQueueOffline(context: AccountContext, e: Exception): Boolean =
        e is IOException || (accountSession.isCurrent(context) && !isOnlineProvider())

    /** Keep the upstream UI error localized while retaining a diagnostic log. */
    private fun reportOperationFailure(context: AccountContext, e: Exception) {
        Log.e("MemosViewModel", "Operation failed", e)
        accountSession.update(uiState, context) { it.copy(error = UiMessage(R.string.common_operation_failed)) }
    }

    /**
     * Queue [op] for durable replay, apply the optimistic local change, report
     * success and tell the user the change was saved offline. Shared by the
     * known-offline fast path and the online-attempt fallback of every write
     * so the two copies cannot drift apart.
     */
    private suspend fun applyOffline(
        context: AccountContext,
        op: PendingOp,
        onSuccess: () -> Unit = {},
        applyOptimistic: suspend () -> Unit
    ) {
        syncManager.enqueue(op)
        applyOptimistic()
        if (accountSession.isCurrent(context)) {
            onSuccess()
            notifyOfflineSaved()
        }
    }

    private fun currentUser(): User? = uiState.value.session.currUser

    override fun selectMemo(memo: Memo?) {
        val context = accountSession.current ?: return
        accountSession.update(uiState, context) {
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
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val draftIdToDelete = uiState.value.draft.currentEditingDraftId
        val accountId = context.account.id
        // One idempotency key per user create action: the online attempt and any
        // offline-queue fallback must reuse the same memoId so a replay after a
        // timeout cannot create a duplicate server-side. Callers with their own
        // stable identity (e.g. draft publishing) can supply [memoId].
        val clientId = memoId ?: UUID.randomUUID().toString()
        var memo = Memo(
            content = content,
            visibility = visibility,
            attachments = attachments,
            location = location
        )
        scope.launch {
            try {
                val prepared = attachmentManager?.prepareRestoredDraftAttachments(context, memo.attachments.orEmpty()) ?: memo.attachments.orEmpty()
                memo = memo.copy(attachments = prepared.ifEmpty { null })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { reportOperationFailure(context, error); if (accountSession.isCurrent(context)) onError(); return@launch }
            val attachmentsPending = memo.attachments.orEmpty().any { it.name.isNullOrBlank() && it.clientId != null }
            if (accountId != null && (!wasOnline || attachmentsPending)) {
                // Offline: queue the create and show the memo locally right away.
                val tempName = "offline-$clientId"
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
                    context,
                    PendingOp.new(
                        accountId = accountId,
                        type = PendingOpType.CREATE,
                        memoName = tempName,
                        payloadJson = gson.toJson(localMemo),
                        id = clientId
                    ),
                    onSuccess
                ) {
                    applyLocalCreate(context, localMemo, draftIdToDelete)
                }
                return@launch
            }

            try {
                accountSession.update(uiState, context) { it.copy(isPosting = true) }
                val created = api.createMemo(memo, clientId)
                if (created != null) {
                    draftIdToDelete?.let { draftManager.deleteDraft(context.account.id, it) }
                    if (accountSession.isCurrent(context) && uiState.value.draft.currentEditingDraftId == draftIdToDelete) {
                        draftDelegate.setCurrentEditingDraft(null)
                    }
                    if (accountSession.isCurrent(context)) listUpdater.refreshUserMemos()
                    // Keep the local cache fresh for offline browsing.
                    accountId?.let {
                        memoCacheRepository.upsertCachedMemo(it, created, CacheListType.USER)
                    }
                    accountSession.update(uiState, context) {
                        it.copy(
                            draft = it.draft.copy(
                                composerResetToken = System.currentTimeMillis().toInt()
                            )
                        )
                    }
                    if (accountSession.isCurrent(context)) onSuccess()
                } else {
                    // No memo came back (e.g. no API bound): report failure so
                    // callers awaiting a result (draft publishing) don't hang.
                    if (accountSession.isCurrent(context)) onError()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && shouldQueueOffline(context, e)) {
                    val tempName = "offline-$clientId"
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
                        context,
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
                        applyLocalCreate(context, localMemo, draftIdToDelete)
                    }
                } else {
                    reportOperationFailure(context, e)
                    if (accountSession.isCurrent(context)) onError()
                }
            } finally {
                accountSession.update(uiState, context) { it.copy(isPosting = false) }
            }
        }
    }

    private suspend fun applyLocalCreate(context: AccountContext, localMemo: Memo, draftIdToDelete: String?) {
        val accountId = context.account.id
        if (accountId != null) {
            memoCacheRepository.upsertCachedMemo(
                accountId, localMemo, CacheListType.USER, order = 0
            )
        }
        if (accountSession.isCurrent(context)) listUpdater.insertMemoIntoUserList(localMemo)
        draftIdToDelete?.let { draftManager.deleteDraft(context.account.id, it) }
        if (accountSession.isCurrent(context) && uiState.value.draft.currentEditingDraftId == draftIdToDelete) {
            draftDelegate.setCurrentEditingDraft(null)
        }
        accountSession.update(uiState, context) {
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
        onError: () -> Unit,
        onSuccess: () -> Unit
    ) {
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
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
            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp, onSuccess) {
                    applyLocalUpdate(context, memo, update)
                }
                return@launch
            }

            try {
                val updated = api.updateMemo(name, update, mask)
                if (updated != null) {
                    // Handle local list moves if state changed
                    val oldState = memo.state ?: MemoState.NORMAL
                    val newState = updated.state ?: MemoState.NORMAL

                    if (oldState != newState) {
                        if (accountSession.isCurrent(context)) listUpdater.handleMemoStateChange(memo, updated)
                    }

                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(context, updated)
                    if (accountSession.isCurrent(context)) onSuccess()
                } else if (accountSession.isCurrent(context)) {
                    reportOperationFailure(context, IllegalStateException("Memo update returned no result"))
                    onError()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp, onSuccess) {
                        applyLocalUpdate(context, memo, update)
                    }
                } else {
                    reportOperationFailure(context, e)
                    if (accountSession.isCurrent(context)) onError()
                }
            }
        }
    }

    private suspend fun applyLocalUpdate(context: AccountContext, oldMemo: Memo, updated: Memo) {
        val oldState = oldMemo.state ?: MemoState.NORMAL
        val newState = updated.state ?: MemoState.NORMAL
        if (oldState != newState) {
            if (accountSession.isCurrent(context)) listUpdater.handleMemoStateChange(oldMemo, updated)
        } else {
            if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(updated)
        }
        cacheLocalMemo(context, updated)
    }

    private suspend fun cacheLocalMemo(context: AccountContext, memo: Memo) {
        val accountId = context.account.id
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
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
        val name = memo.name ?: return
        val pendingOp = accountId?.let {
            PendingOp.new(
                accountId = it,
                type = PendingOpType.DELETE,
                memoName = name
            )
        }
        scope.launch {
            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp, onSuccess) {
                    memoCacheRepository.removeCachedMemo(accountId, name)
                    if (accountSession.isCurrent(context)) listUpdater.removeMemoFromLists(name)
                }
                return@launch
            }

            try {
                api.deleteMemo(name)
                if (accountSession.isCurrent(context)) onSuccess()

                // Local update: Remove from all lists
                if (accountSession.isCurrent(context)) listUpdater.removeMemoFromLists(name)
                accountId?.let { memoCacheRepository.removeCachedMemo(it, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp, onSuccess) {
                        memoCacheRepository.removeCachedMemo(accountId, name)
                        if (accountSession.isCurrent(context)) listUpdater.removeMemoFromLists(name)
                    }
                } else {
                    reportOperationFailure(context, e)
                }
            }
        }
    }

    override fun updateMemoPinned(memo: Memo, pinned: Boolean, onSuccess: () -> Unit) {
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
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
            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp, onSuccess) {
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(update)
                    cacheLocalMemo(context, update)
                }
                return@launch
            }

            try {
                val updated = api.updateMemo(name, update, "pinned")
                if (updated != null) {
                    if (accountSession.isCurrent(context)) onSuccess()
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(context, updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp, onSuccess) {
                        if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(update)
                        cacheLocalMemo(context, update)
                    }
                } else {
                    reportOperationFailure(context, e)
                }
            }
        }
    }

    override fun createComment(parentMemo: Memo, content: String, onError: () -> Unit, onSuccess: () -> Unit) {
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
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
            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp, onSuccess) {
                    applyLocalComment(context, localComment, parentName)
                }
                return@launch
            }

            try {
                val created = api.createMemoComment(parentName, comment)
                memoCacheRepository.upsertCachedMemo(accountId, created, CacheListType.COMMENT, parentName = parentName)
                if (accountSession.isCurrent(context)) onSuccess()
                if (accountSession.isCurrent(context)) commentManager?.fetch(refresh = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp, onSuccess) {
                        applyLocalComment(context, localComment, parentName)
                    }
                } else {
                    reportOperationFailure(context, e)
                    if (accountSession.isCurrent(context)) onError()
                }
            }
        }
    }

    /**
     * Show the optimistic comment and persist it so it survives restarts / a
     * failed sync until the server list refresh replaces it.
     */
    private suspend fun applyLocalComment(context: AccountContext, localComment: Memo, parentName: String) {
        if (accountSession.isCurrent(context)) commentManager?.upsert(
            localComment,
            { it.name == localComment.name },
            compareBy { it.createTime }
        )
        val accountId = context.account.id
        memoCacheRepository.upsertCachedMemo(
            accountId, localComment, CacheListType.COMMENT, parentName = parentName
        )
    }

    override suspend fun uploadAttachment(uri: Uri, context: Context): Attachment? {
        val account = accountSession.current ?: return null
        val attachment = attachmentManager?.uploadAttachment(uri, context)
        return attachment.takeIf { accountSession.isCurrent(account) }
    }

    override fun discardQueuedUploadIfOrphaned(clientId: String) {
        val context = accountSession.current ?: return
        scope.launch { attachmentManager?.discardQueuedUploadIfOrphaned(clientId) }
    }

    override fun upsertMemoReaction(memo: Memo, reactionType: String) {
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
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
            if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(optimistic)

            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp) {
                    cacheLocalMemo(context, optimistic)
                }
                return@launch
            }

            try {
                val reaction = Reaction(contentId = name, reactionType = reactionType)
                val request = UpsertMemoReactionRequest(name = memo.name, reaction = reaction)
                api.upsertMemoReaction(name, request)

                // Fetch latest memo state to be sure about all reactions and update in-place
                val updated = api.getMemo(name)
                if (updated != null) {
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(context, updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp) {
                        cacheLocalMemo(context, optimistic)
                    }
                } else {
                    // The server rejected the request (e.g. 401/404): roll the
                    // optimistic reaction back and surface the error, instead of
                    // leaving a reaction on screen that was never applied.
                    reportOperationFailure(context, e)
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(memo)
                }
            }
        }
    }

    override fun deleteMemoReaction(memo: Memo, reaction: Reaction) {
        val context = accountSession.current ?: return
        val connection = context.connection
        val api = connection.api
        val wasOnline = connection.networkReady && isOnlineProvider()
        val accountId = context.account.id
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
            if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(optimistic)

            if (accountId != null && pendingOp != null && !wasOnline) {
                applyOffline(context, pendingOp) {
                    cacheLocalMemo(context, optimistic)
                }
                return@launch
            }

            try {
                val reactionName = reaction.name ?: return@launch
                api.deleteMemoReaction(reactionName)

                // Fetch latest memo state and update in-place
                val updated = api.getMemo(name)
                if (updated != null) {
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(updated)
                    cacheLocalMemo(context, updated)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (accountId != null && pendingOp != null && shouldQueueOffline(context, e)) {
                    applyOffline(context, pendingOp) {
                        cacheLocalMemo(context, optimistic)
                    }
                } else {
                    // The server rejected the request (e.g. 401/404): roll the
                    // optimistic removal back and surface the error, instead of
                    // leaving the reaction hidden while it still exists on the server.
                    reportOperationFailure(context, e)
                    if (accountSession.isCurrent(context)) listUpdater.updateMemoInLists(memo)
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
