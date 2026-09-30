package org.example.memosm.viewmodel.delegates

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.example.memosm.data.DraftManager
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Location
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.MemosUiState
import kotlin.coroutines.resume

interface DraftDelegate {
    fun loadDraftsForAccount(accountId: String)
    fun saveDraft(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location? = null,
        draftId: String? = null
    )

    fun deleteDraft(draftId: String)
    fun deleteAllDrafts()
    fun publishAllDrafts(onResult: (Int) -> Unit = {})
    fun setCurrentEditingDraft(draftId: String?)
    fun initializeNewDraftSession(): String
    fun getLatestDraft(): Draft?
    fun clearCurrentEditingDraft()
}

class DraftDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val draftManager: DraftManager,
    private val memoActionDelegateProvider: () -> MemoActionDelegate,
    private val onRefreshUserMemos: () -> Unit
) : DraftDelegate {

    private fun getActiveAccountId(): String? {
        return uiState.value.accounts.find { it.isActive }?.id
    }

    override fun loadDraftsForAccount(accountId: String) {
        scope.launch {
            try {
                val drafts = draftManager.getDrafts(accountId)
                uiState.update {
                    it.copy(draft = it.draft.copy(drafts = drafts, isDraftLoaded = true))
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Error loading drafts for account $accountId", e)
                uiState.update { it.copy(draft = it.draft.copy(isDraftLoaded = true)) }
            }
        }
    }

    override fun saveDraft(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location?,
        draftId: String?
    ) {
        val accountId = getActiveAccountId() ?: return
        val existingDraftId = draftId ?: uiState.value.draft.currentEditingDraftId

        val draft = Draft(
            id = existingDraftId ?: java.util.UUID.randomUUID().toString(),
            content = content,
            visibility = visibility,
            attachments = attachments,
            location = location,
            createdAt = if (existingDraftId != null) {
                uiState.value.draft.drafts.find { it.id == existingDraftId }?.createdAt
                    ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            },
            updatedAt = System.currentTimeMillis()
        )

        // Only save if there's actual content
        if (!draft.hasContent()) return

        scope.launch {
            draftManager.saveDraft(accountId, draft)
            loadDraftsForAccount(accountId)
        }
    }

    override fun deleteDraft(draftId: String) {
        val accountId = getActiveAccountId() ?: return
        scope.launch {
            draftManager.deleteDraft(accountId, draftId)
            loadDraftsForAccount(accountId)
        }
    }

    override fun deleteAllDrafts() {
        val accountId = getActiveAccountId() ?: return
        scope.launch {
            draftManager.clearDrafts(accountId)
            loadDraftsForAccount(accountId)
            // If the current editing draft was one of them, clear it
            setCurrentEditingDraft(null)
        }
    }

    override fun publishAllDrafts(onResult: (Int) -> Unit) {
        val accountId = getActiveAccountId() ?: return
        val drafts = uiState.value.draft.drafts
        if (drafts.isEmpty()) return

        scope.launch {
            var published = 0
            // Publishing goes through MemoActionDelegate.createMemo, so an
            // offline publish lands in the outbox (optimistic cache entry +
            // PendingOp queue) instead of failing silently. A draft that fails
            // online (server error) is kept and the loop continues with the
            // next one.
            // Clear the editing pointer up front: createMemo deletes the
            // "current editing" draft on success, which must not consume one
            // of the drafts being published here.
            setCurrentEditingDraft(null)
            for (draft in drafts) {
                if (!draft.hasContent()) continue
                // Deterministic memoId derived from the draft id so a user
                // retry after a timeout deduplicates server-side.
                val memoId = java.util.UUID.nameUUIDFromBytes(
                    draft.id.toByteArray(Charsets.UTF_8)
                ).toString()
                if (publishDraft(draft, memoId)) {
                    draftManager.deleteDraft(accountId, draft.id)
                    published++
                }
            }
            loadDraftsForAccount(accountId)
            onRefreshUserMemos()
            onResult(published)
        }
    }

    /**
     * Publish a single draft via the outbox path. Returns true when the memo
     * was created online or queued for sync (offline); false when the server
     * rejected it and the draft must be kept.
     */
    private suspend fun publishDraft(draft: Draft, memoId: String): Boolean =
        suspendCancellableCoroutine { cont ->
            memoActionDelegateProvider().createMemo(
                content = draft.content,
                visibility = draft.visibility,
                attachments = draft.attachments.ifEmpty { null },
                location = draft.location,
                memoId = memoId,
                onError = { cont.resume(false) },
                onSuccess = { cont.resume(true) }
            )
        }

    override fun setCurrentEditingDraft(draftId: String?) {
        uiState.update {
            it.copy(draft = it.draft.copy(currentEditingDraftId = draftId))
        }
    }

    override fun initializeNewDraftSession(): String {
        val newDraftId = java.util.UUID.randomUUID().toString()
        setCurrentEditingDraft(newDraftId)
        return newDraftId
    }

    override fun getLatestDraft(): Draft? {
        return uiState.value.draft.drafts.maxByOrNull { it.updatedAt }
    }

    override fun clearCurrentEditingDraft() {
        val draftId = uiState.value.draft.currentEditingDraftId
        if (draftId != null) {
            deleteDraft(draftId)
        }
        setCurrentEditingDraft(null)
    }
}
