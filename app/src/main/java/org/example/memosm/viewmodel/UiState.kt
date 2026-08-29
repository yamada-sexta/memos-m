package org.example.memosm.viewmodel

import org.example.memosm.model.Account
import org.example.memosm.model.Activity
import org.example.memosm.model.Attachment
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.InstanceSetting
import org.example.memosm.model.Memo
import org.example.memosm.model.Shortcut
import org.example.memosm.model.User
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.UserStats
import org.example.memosm.model.UserWebhook

// --- Paginated List State (Generic) ---

data class PaginatedListState<T>(
    val items: List<T> = emptyList(),
    val isLoading: Boolean = false,
    val nextPageToken: String? = null,
    val isOffline: Boolean = false,  // True when displaying cached data due to network failure
    val errorMessage: String? = null // Error message from last failed fetch (shown with cached data)
)

// --- Session State (Auth & User) ---

data class SessionState(
    val token: String = "",
    val hostUrl: String = "",
    val currUser: User? = null,
    val userStats: UserStats? = null,
    val userSettings: UserGeneralSetting? = null,
    val webhooks: List<UserWebhook> = emptyList(),
    val instanceProfile: InstanceProfile? = null,
    val instanceSettings: InstanceSetting? = null,
    val activities: List<Activity> = emptyList()
)

// --- Memo List State ---

data class MemoListState(
    val list: PaginatedListState<Memo> = PaginatedListState(),
    val shortcuts: List<Shortcut> = emptyList(),
    val selectedShortcut: Shortcut? = null,
    val selectedHashtag: String? = null
)

// --- Attachment List State ---

data class AttachmentListState(
    val list: PaginatedListState<Attachment> = PaginatedListState(),
    val cellWidth: Float = 240f,
    val aspectRatios: Map<Float, Map<String, Float>> = emptyMap()
)

// --- Draft State ---

data class DraftState(
    val drafts: List<org.example.memosm.model.Draft> = emptyList(),
    val isDraftLoaded: Boolean = false,
    val composerResetToken: Int = 0,
    val currentEditingDraftId: String? = null // Track which draft is being edited
)

// --- Detail Pane State ---

data class DetailPaneState(
    val selectedMemo: Memo? = null,
    val comments: PaginatedListState<Memo> = PaginatedListState(),
    // isLoadingComments is now part of comments.isLoading
)

// --- App Settings (local) ---

data class AppSettings(
    val pageSize: Int = 10,
    val headerScale: Float = 1.0f
)

// --- Main UI State ---

data class MemosUiState(
    val session: SessionState = SessionState(),
    val userMemoList: MemoListState = MemoListState(),
    val exploreMemoList: MemoListState = MemoListState(),
    val archivedMemoList: MemoListState = MemoListState(),
    val searchMemoList: MemoListState = MemoListState(),
    val attachmentList: AttachmentListState = AttachmentListState(),
    val draft: DraftState = DraftState(),
    val detailPane: DetailPaneState = DetailPaneState(),
    val appSettings: AppSettings = AppSettings(),

    val accounts: List<Account> = emptyList(),
    val users: Map<String, User> = emptyMap(),

    val isPosting: Boolean = false,
    val isRefreshing: Boolean = false,
    val refreshTrigger: Long = 0L,
    val refreshSource: RefreshSource = RefreshSource.Manual,
    val error: UiMessage? = null
)

enum class RefreshSource {
    Manual, // Pull-to-refresh or explicit user action
    USerMemos, ExploreMemos, ArchivedMemos, SearchMemos, Attachments
}

// --- Error Response ---

data class MemosErrorResponse(
    val code: Int? = null, val message: String? = null
)
