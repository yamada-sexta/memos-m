package org.example.memosm.viewmodel.delegates

import android.util.Log
import org.example.memosm.R
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.Reaction
import org.example.memosm.model.UpsertMemoReactionRequest
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.UiMessage
import org.example.memosm.viewmodel.manager.AttachmentManager
import org.example.memosm.viewmodel.manager.CommentListManager

interface MemoActionDelegate {
    fun selectMemo(memo: Memo?)
    fun clearSelectedMemo()
    fun createMemo(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>? = null,
        location: Location? = null,
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
    fun upsertMemoReaction(memo: Memo, reactionType: String)
    fun deleteMemoReaction(memo: Memo, reaction: Reaction)
}

interface MemoListUpdater {
    fun updateMemoInLists(memo: Memo)
    fun removeMemoFromLists(memoName: String)
    fun refreshUserMemos()
    fun handleMemoStateChange(memo: Memo, updated: Memo)
}

class MemoActionDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val apiProvider: () -> MemosApi?,
    private val listUpdater: MemoListUpdater,
    private val draftDelegate: DraftDelegate,
    private val attachmentManagerProvider: () -> AttachmentManager?,
    private val commentManagerProvider: () -> CommentListManager?
) : MemoActionDelegate {

    private val api: MemosApi? get() = apiProvider()
    private val attachmentManager: AttachmentManager? get() = attachmentManagerProvider()
    private val commentManager: CommentListManager? get() = commentManagerProvider()

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
        onSuccess: () -> Unit
    ) {
        val draftIdToDelete = uiState.value.draft.currentEditingDraftId
        scope.launch {
            try {
                uiState.update { it.copy(isPosting = true) }
                val memo = Memo(
                    content = content,
                    visibility = visibility,
                    attachments = attachments,
                    location = location
                )
                val created = api?.createMemo(memo)
                if (created != null) {
                    draftIdToDelete?.let(draftDelegate::deleteDraft)
                    if (uiState.value.draft.currentEditingDraftId == draftIdToDelete) {
                        draftDelegate.setCurrentEditingDraft(null)
                    }
                    onSuccess()
                    listUpdater.refreshUserMemos()
                    uiState.update {
                        it.copy(
                            draft = it.draft.copy(
                                composerResetToken = System.currentTimeMillis().toInt()
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            } finally {
                uiState.update { it.copy(isPosting = false) }
            }
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
        scope.launch {
            try {
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

                val updated = api?.updateMemo(memo.name!!, update, maskParts.joinToString(","))

                if (updated != null) {
                    onSuccess()

                    // Handle local list moves if state changed
                    val oldState = memo.state ?: MemoState.NORMAL
                    val newState = updated.state ?: MemoState.NORMAL

                    if (oldState != newState) {
                        listUpdater.handleMemoStateChange(memo, updated)
                    }

                    listUpdater.updateMemoInLists(updated)
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun deleteMemo(memo: Memo, onSuccess: () -> Unit) {
        scope.launch {
            try {
                api?.deleteMemo(memo.name!!)
                onSuccess()

                // Local update: Remove from all lists
                listUpdater.removeMemoFromLists(memo.name!!)
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun updateMemoPinned(memo: Memo, pinned: Boolean, onSuccess: () -> Unit) {
        scope.launch {
            try {
                val update = memo.copy(pinned = pinned)
                val updated = api?.updateMemo(memo.name!!, update, "pinned")
                if (updated != null) {
                    onSuccess()
                    listUpdater.updateMemoInLists(updated)
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Operation failed", e)
                uiState.update { it.copy(error = UiMessage(R.string.common_operation_failed)) }
            }
        }
    }

    override fun createComment(parentMemo: Memo, content: String, onSuccess: () -> Unit) {
        scope.launch {
            try {
                val comment = Memo(content = content, visibility = parentMemo.visibility)
                api?.createMemoComment(parentMemo.name!!, comment)
                onSuccess()
                commentManager?.fetch(refresh = true)
            } catch (e: Exception) {
            }
        }
    }

    override suspend fun uploadAttachment(uri: Uri, context: Context): Attachment? {
        return attachmentManager?.uploadAttachment(uri, context)
    }

    override fun upsertMemoReaction(memo: Memo, reactionType: String) {
        scope.launch {
            try {
                val reaction = Reaction(contentId = memo.name!!, reactionType = reactionType)
                val request = UpsertMemoReactionRequest(name = memo.name, reaction = reaction)
                api?.upsertMemoReaction(memo.name, request)

                // Fetch latest memo state to be sure about all reactions and update in-place
                val updated = api?.getMemo(memo.name)
                if (updated != null) {
                    listUpdater.updateMemoInLists(updated)
                }
            } catch (e: Exception) {
            }
        }
    }

    override fun deleteMemoReaction(memo: Memo, reaction: Reaction) {
        scope.launch {
            try {
                val reactionName = reaction.name ?: return@launch
                api?.deleteMemoReaction(reactionName)

                // Fetch latest memo state and update in-place
                val updated = api?.getMemo(memo.name!!)
                if (updated != null) {
                    listUpdater.updateMemoInLists(updated)
                }
            } catch (e: Exception) {
            }
        }
    }
}
