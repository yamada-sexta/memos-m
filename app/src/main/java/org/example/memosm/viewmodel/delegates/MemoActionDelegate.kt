package org.example.memosm.viewmodel.delegates

import org.example.memosm.data.media.AttachmentUpload

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.ReactionOpPayload
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.Reaction
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.AccountContext
import org.example.memosm.viewmodel.AccountSession
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.UiMessage
import org.example.memosm.viewmodel.manager.AttachmentManager
import java.util.UUID
import kotlin.time.Clock

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
        onSuccess: () -> Unit = {},
    )

    fun updateMemo(
        memo: Memo,
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location? = memo.location,
        state: MemoState? = null,
        onError: () -> Unit = {},
        onSuccess: () -> Unit = {},
    )

    fun deleteMemo(
        memo: Memo,
        onSuccess: () -> Unit = {},
    )

    fun updateMemoPinned(
        memo: Memo,
        pinned: Boolean,
        onSuccess: () -> Unit = {},
    )

    fun createComment(
        parentMemo: Memo,
        content: String,
        onError: () -> Unit = {},
        onSuccess: () -> Unit = {},
    )

    suspend fun uploadAttachment(
        uri: Uri,
        context: Context,
    ): Attachment?

    /** Discard a queued offline upload once nothing references its placeholder anymore. */
    fun discardQueuedUploadIfOrphaned(clientId: String)

    fun upsertMemoReaction(
        memo: Memo,
        reactionType: String,
    )

    fun deleteMemoReaction(
        memo: Memo,
        reaction: Reaction,
    )
}

/** User actions only commit local state. WorkManager owns every network mutation. */
class MemoActionDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val draftDelegate: DraftDelegate,
    private val attachmentManagerProvider: () -> AttachmentManager?,
    private val memoCacheRepository: MemoCacheRepository,
    private val accountSession: AccountSession,
    private val draftManager: DraftManager,
    private val workScheduler: SyncWorkScheduler,
    private val accountExists: suspend (String) -> Boolean = { true },
) : MemoActionDelegate {
    private val gson = GsonProvider.gson

    override fun selectMemo(memo: Memo?) {
        val context = accountSession.current ?: return
        accountSession.update(uiState, context) {
            it.copy(detailPane = it.detailPane.copy(selectedMemo = memo))
        }
    }

    override fun clearSelectedMemo() = selectMemo(null)

    private fun mutate(
        context: AccountContext,
        onError: () -> Unit = {},
        onSuccess: () -> Unit = {},
        afterCommit: suspend () -> Unit = {},
        attachments: List<Attachment>? = null,
        prepare: suspend (List<Attachment>?) -> Pair<PendingOp, Memo?>?,
    ) {
        val generation = BackupCoordinator.generation
        scope.launch {
            accountSession.update(uiState, context) { it.copy(isPosting = true) }
            try {
                val commit: suspend (
                    List<Attachment>?,
                    List<AttachmentUpload>,
                ) -> Unit = { prepared, uploads ->
                    val mutation = prepare(prepared)
                    BackupCoordinator.withStorageLock {
                        check(BackupCoordinator.generation == generation && !BackupCoordinator.restoring.value) { "Storage was restored" }
                        check(accountExists(context.account.id)) { "Account was removed" }
                        if (mutation != null) {
                            val (op, memo) = mutation
                            memoCacheRepository.commitMutation(
                                op,
                                memo,
                                when {
                                    op.parentName != null || memo?.parent != null -> CacheListType.COMMENT
                                    memo?.state == MemoState.ARCHIVED -> CacheListType.ARCHIVED
                                    else -> CacheListType.USER
                                },
                                op.parentName ?: memo?.parent,
                                uploads,
                            )
                        }
                    }
                    if (mutation != null) {
                        try {
                            workScheduler.schedule(context.account.id)
                        } catch (
                            error: Exception,
                        ) {
                            Log.e("MemoActions", "Could not schedule committed mutation", error)
                        }
                    }
                }
                val manager = attachmentManagerProvider()
                if (attachments != null && manager != null) {
                    manager.withPreparedAttachments(context, attachments, commit)
                } else {
                    require(attachments.orEmpty().all { !it.name.isNullOrBlank() || it.clientId != null }) { "Uploads are unavailable" }
                    commit(attachments, emptyList())
                }
                afterCommit()
                if (accountSession.isCurrent(context)) onSuccess()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("MemoActions", "Could not save local mutation", error)
                accountSession.update(uiState, context) { it.copy(error = UiMessage(R.string.common_operation_failed)) }
                if (accountSession.isCurrent(context)) onError()
            } finally {
                accountSession.update(uiState, context) { it.copy(isPosting = false) }
            }
        }
    }

    override fun createMemo(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>?,
        location: Location?,
        memoId: String?,
        onError: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        val context = accountSession.current ?: return
        val draftId = uiState.value.draft.currentEditingDraftId
        val id =
            memoId ?: draftId?.let { UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)).toString() }
                ?: UUID.randomUUID().toString()
        val creator =
            context.account.user?.name ?: uiState.value.session.currUser
                ?.name
        mutate(context, onError, onSuccess, attachments = attachments.orEmpty(), afterCommit = {
            // Await originating-account draft cleanup before the editor can finish.
            draftId?.let { draftManager.deleteDraft(context.account.id, it) }
            if (accountSession.isCurrent(context) && uiState.value.draft.currentEditingDraftId == draftId) {
                draftDelegate.setCurrentEditingDraft(null)
            }
        }) { prepared ->
            val now = Clock.System.now()
            val memo =
                Memo(
                    name = "offline-$id",
                    content = content,
                    visibility = visibility,
                    attachments = prepared,
                    location = location,
                    state = MemoState.NORMAL,
                    creator = creator,
                    createTime = now,
                    updateTime = now,
                    displayTime = now,
                )
            PendingOp.new(context.account.id, PendingOpType.CREATE, memo.name, payloadJson = gson.toJson(memo), id = id) to memo
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
        onSuccess: () -> Unit,
    ) {
        val context = accountSession.current ?: return
        val name = memo.name ?: return
        mutate(context, onError, onSuccess, attachments = attachments) { prepared ->
            val update =
                memo.copy(
                    content = content,
                    visibility = visibility,
                    attachments = prepared,
                    location = location,
                    state = state ?: memo.state,
                )
            val mask =
                buildList {
                    if (update.content != memo.content) add("content")
                    if (update.visibility != memo.visibility) add("visibility")
                    if (prepared != memo.attachments.orEmpty()) add("attachments")
                    if (update.location != memo.location) add("location")
                    if (update.state != memo.state) add("state")
                }.joinToString(",")
            if (mask.isEmpty()) return@mutate null
            PendingOp.new(
                context.account.id,
                PendingOpType.UPDATE,
                name,
                parentName = memo.parent,
                payloadJson = gson.toJson(update),
                updateMask = mask,
                baseUpdateTime = memo.updateTime?.toString(),
            ) to update
        }
    }

    override fun deleteMemo(
        memo: Memo,
        onSuccess: () -> Unit,
    ) {
        val context = accountSession.current ?: return
        val name = memo.name ?: return
        mutate(context, onSuccess = onSuccess) {
            PendingOp.new(context.account.id, PendingOpType.DELETE, name, parentName = memo.parent) to memo
        }
    }

    override fun updateMemoPinned(
        memo: Memo,
        pinned: Boolean,
        onSuccess: () -> Unit,
    ) {
        val context = accountSession.current ?: return
        val name = memo.name ?: return
        mutate(context, onSuccess = onSuccess) {
            val update = memo.copy(pinned = pinned)
            PendingOp.new(
                context.account.id,
                PendingOpType.UPDATE,
                name,
                parentName = memo.parent,
                payloadJson = gson.toJson(update),
                updateMask = "pinned",
                baseUpdateTime = memo.updateTime?.toString(),
            ) to update
        }
    }

    override fun createComment(
        parentMemo: Memo,
        content: String,
        onError: () -> Unit,
        onSuccess: () -> Unit,
    ) {
        val context = accountSession.current ?: return
        val parent = parentMemo.name ?: return
        val id = UUID.randomUUID().toString()
        val creator =
            context.account.user?.name ?: uiState.value.session.currUser
                ?.name
        mutate(context, onError, onSuccess) {
            val now = Clock.System.now()
            val comment =
                Memo(
                    name = "offline-comment-$id",
                    parent = parent,
                    content = content,
                    visibility = parentMemo.visibility,
                    creator = creator,
                    createTime = now,
                    updateTime = now,
                    displayTime = now,
                    state = MemoState.NORMAL,
                )
            PendingOp.new(
                context.account.id,
                PendingOpType.COMMENT_CREATE,
                comment.name,
                parent,
                gson.toJson(comment),
                id = id,
            ) to comment
        }
    }

    override fun upsertMemoReaction(
        memo: Memo,
        reactionType: String,
    ) = reaction(memo, reactionType, add = true)

    override fun deleteMemoReaction(
        memo: Memo,
        reaction: Reaction,
    ) = reaction(memo, reaction.reactionType, add = false)

    private fun reaction(
        memo: Memo,
        type: String,
        add: Boolean,
    ) {
        val context = accountSession.current ?: return
        val name = memo.name ?: return
        val mine =
            context.account.user?.name ?: uiState.value.session.currUser
                ?.name ?: return
        mutate(context) {
            PendingOp.new(
                context.account.id,
                if (add) PendingOpType.REACTION_UPSERT else PendingOpType.REACTION_DELETE,
                name,
                parentName = memo.parent,
                payloadJson = gson.toJson(ReactionOpPayload(type, mine)),
            ) to memo
        }
    }

    override suspend fun uploadAttachment(
        uri: Uri,
        context: Context,
    ): Attachment? {
        val account = accountSession.current ?: return null
        return attachmentManagerProvider()?.uploadAttachment(uri, context).takeIf { accountSession.isCurrent(account) }
    }

    override fun discardQueuedUploadIfOrphaned(clientId: String) {
        scope.launch { attachmentManagerProvider()?.discardQueuedUploadIfOrphaned(clientId) }
    }
}
