package org.example.memosm.viewmodel.delegates

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.example.memosm.api.MemosApi
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.MemoSyncState
import org.example.memosm.model.Visibility
import org.example.memosm.viewmodel.MemosUiState

interface DraftDelegate {
    fun loadDraftsForAccount(accountId: String)
    fun saveDraft(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location? = null,
        remoteName: String? = null,
        state: MemoState? = MemoState.NORMAL,
        syncState: MemoSyncState = MemoSyncState.PENDING_CREATE,
        draftId: String? = null
    )

    fun deleteDraft(draftId: String)
    fun publishAllDrafts(onResult: (Int) -> Unit = {})
    fun publishDraft(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location? = null,
        remoteMemo: Memo? = null,
        state: MemoState? = null,
        onSuccess: () -> Unit = {}
    )
    fun setCurrentEditingDraft(draftId: String?)
    fun initializeNewDraftSession(): String
    fun getLatestDraft(): Draft?
    fun clearCurrentEditingDraft()
}

class DraftDelegateImpl(
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MemosUiState>,
    private val memoCacheRepository: MemoCacheRepository,
    private val apiProvider: () -> MemosApi?,
    private val onRefreshUserMemos: () -> Unit
) : DraftDelegate {

    private val api: MemosApi? get() = apiProvider()

    private fun getActiveAccountId(): String? {
        return uiState.value.accounts.find { it.isActive }?.id
    }

    override fun loadDraftsForAccount(accountId: String) {
        scope.launch {
            try {
                val drafts = memoCacheRepository.getDrafts(accountId)
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
        remoteName: String?,
        state: MemoState?,
        syncState: MemoSyncState,
        draftId: String?
    ) {
        val accountId = getActiveAccountId() ?: return
        val existingDraftId = draftId ?: uiState.value.draft.currentEditingDraftId

        val draft = Draft(
            id = existingDraftId ?: java.util.UUID.randomUUID().toString(),
            remoteName = remoteName,
            syncState = syncState,
            state = state,
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

        scope.launch {
            if (draft.hasContent()) {
                memoCacheRepository.saveDraft(accountId, draft)
            } else if (existingDraftId != null) {
                memoCacheRepository.deleteDraft(accountId, existingDraftId)
            }
            loadDraftsForAccount(accountId)
        }
    }

    override fun deleteDraft(draftId: String) {
        val accountId = getActiveAccountId() ?: return
        scope.launch {
            memoCacheRepository.deleteDraft(accountId, draftId)
            loadDraftsForAccount(accountId)
        }
    }

    override fun publishAllDrafts(onResult: (Int) -> Unit) {
        val accountId = getActiveAccountId() ?: return
        val drafts = uiState.value.draft.drafts
        if (drafts.isEmpty()) return

        scope.launch {
            var published = 0
            try {
                uiState.update { it.copy(isPosting = true) }
                for (draft in drafts) {
                    if (!draft.hasContent()) continue
                    try {
                        val memo = Memo(
                            name = draft.remoteName,
                            localId = draft.id,
                    syncState = draft.syncState,
                    state = draft.state,
                    content = draft.content,
                    visibility = draft.visibility,
                            attachments = draft.attachments.ifEmpty { null },
                            location = draft.location
                        )
                        val synced = syncDraftMemo(memo)
                        if (synced != null) {
                            memoCacheRepository.deleteDraft(accountId, draft.id)
                            published++
                        }
                    } catch (e: Exception) {
                        Log.e("MemosViewModel", "Failed to publish draft ${draft.id}", e)
                    }
                }
            } finally {
                uiState.update { it.copy(isPosting = false) }
                loadDraftsForAccount(accountId)
                setCurrentEditingDraft(null)
                onRefreshUserMemos()
                onResult(published)
            }
        }
    }

    override fun publishDraft(
        content: String,
        visibility: Visibility,
        attachments: List<Attachment>,
        location: Location?,
        remoteMemo: Memo?,
        state: MemoState?,
        onSuccess: () -> Unit
    ) {
        val accountId = getActiveAccountId() ?: return
        val syncState =
            if (remoteMemo?.name == null) MemoSyncState.PENDING_CREATE else MemoSyncState.PENDING_UPDATE
        val draftId = uiState.value.draft.currentEditingDraftId ?: initializeNewDraftSession()

        scope.launch {
            try {
                uiState.update { it.copy(isPosting = true) }
                val existingDraft = uiState.value.draft.drafts.find { it.id == draftId }
                val draft = Draft(
                    id = draftId,
                    remoteName = remoteMemo?.name,
                    syncState = syncState,
                    state = state ?: remoteMemo?.state ?: MemoState.NORMAL,
                    content = content,
                    visibility = visibility,
                    attachments = attachments,
                    location = location,
                    createdAt = existingDraft?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                if (draft.hasContent()) {
                    memoCacheRepository.saveDraft(accountId, draft)
                }
                val synced = syncDraftMemo(draft.toMemo())
                if (synced != null) {
                    memoCacheRepository.deleteDraft(accountId, draftId)
                    setCurrentEditingDraft(null)
                    onRefreshUserMemos()
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e("MemosViewModel", "Failed to sync draft $draftId", e)
                uiState.update { it.copy(error = e.message) }
            } finally {
                uiState.update { it.copy(isPosting = false) }
                loadDraftsForAccount(accountId)
            }
        }
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

    private suspend fun syncDraftMemo(memo: Memo): Memo? {
        val currentApi = api ?: return null
        return if (memo.name.isNullOrBlank()) {
            currentApi.createMemo(
                Memo(
                    content = memo.content,
                    visibility = memo.visibility,
                    attachments = memo.attachments,
                    location = memo.location,
                    state = memo.state
                )
            )
        } else {
            val maskParts = mutableListOf("content", "visibility", "attachments", "location")
            if (memo.state != null) {
                maskParts.add("state")
            }
            currentApi.updateMemo(
                memo.name,
                Memo(
                    content = memo.content,
                    visibility = memo.visibility,
                    attachments = memo.attachments,
                    location = memo.location,
                    state = memo.state
                ),
                maskParts.joinToString(",")
            )
        }
    }
}
