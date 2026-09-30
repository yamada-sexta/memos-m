package org.example.memosm.model

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

data class ListMemosResponse(
    val memos: List<Memo>?, val nextPageToken: String?
)

data class ListAttachmentsResponse(
    val attachments: List<Attachment>?, val nextPageToken: String?, val totalSize: Int?
)

@Serializable
enum class Visibility {
    @SerialName("PUBLIC")
    PUBLIC,

    @SerialName("PROTECTED")
    PROTECTED,

    @SerialName("PRIVATE")
    PRIVATE,

    @SerializedName("VISIBILITY_UNSPECIFIED")
    VISIBILITY_UNSPECIFIED
}

@Serializable
enum class MemoState {
    @SerialName("NORMAL")
    NORMAL,

    @SerialName("ARCHIVED")
    ARCHIVED,

    @SerialName("STATE_UNSPECIFIED")
    STATE_UNSPECIFIED
}

data class Memo(
    val name: String? = null,
    val state: MemoState? = null,
    val creator: String? = null,
    @SerializedName("createTime", alternate = ["create_time"]) val createTime: Instant? = null,
    @SerializedName("updateTime", alternate = ["update_time"]) val updateTime: Instant? = null,
    @SerializedName("displayTime", alternate = ["display_time"]) val displayTime: Instant? = null,
    val content: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val tags: List<String>? = null,
    val pinned: Boolean? = null,
    val attachments: List<Attachment>? = null,
    val relations: List<MemoRelation>? = null,
    val reactions: List<Reaction>? = null,
    val property: MemoProperty? = null,
    val parent: String? = null,
    val snippet: String? = null,
    val location: Location? = null
)

data class Attachment(
    val name: String? = null,
    @SerializedName("createTime", alternate = ["create_time"]) val createTime: Instant? = null,
    val filename: String,
    val content: String? = null,
    val externalLink: String? = null,
    val type: String,
    val mimeType: String? = null,
    val size: String? = null,
    val memo: String? = null,
    // Local-only fields for an attachment whose upload is still sitting in the
    // durable upload queue (offline compose). Never sent to the server: the
    // outbox replay strips them after resolving the placeholder against the
    // queue. clientId matches the AttachmentUpload row (forwarded as the API
    // attachmentId); localPath points at the staged bytes for local preview.
    val clientId: String? = null,
    val localPath: String? = null
) {
    val displayType: String
        get() = mimeType ?: type
}


enum class MemoRelationType {
    @SerialName("TYPE_UNSPECIFIED")
    TYPE_UNSPECIFIED,

    @SerialName("REFERENCE")
    COMMENT,

    @SerialName("COMMENT")
    REPLY
}

data class MemoRelation(
    val memo: MemoSnippet, val relatedMemo: MemoSnippet, val type: MemoRelationType
)

data class MemoSnippet(
    val name: String, val snippet: String? = null
)

data class Reaction(
    val name: String? = null,
    val creator: String? = null,
    val contentId: String,
    val reactionType: String,
    val createTime: Instant? = null
)

data class MemoProperty(
    val hasLink: Boolean? = null,
    val hasTaskList: Boolean? = null,
    val hasCode: Boolean? = null,
    val hasIncompleteTasks: Boolean? = null
)

data class Location(
    val placeholder: String? = null, val latitude: Double? = null, val longitude: Double? = null
)

// --- Activity Models ---

data class ListActivitiesResponse(
    val activities: List<Activity>?, val nextPageToken: String?
)

data class Activity(
    val name: String? = null,
    val creator: String? = null,
    val type: String? = null,
    val level: String? = null,
    @SerializedName("createTime", alternate = ["create_time"]) val createTime: Instant? = null,
    val payload: ActivityPayload? = null
)

data class ActivityPayload(
    val memoComment: ActivityMemoCommentPayload? = null
)

data class ActivityMemoCommentPayload(
    val memo: String? = null, val relatedMemo: String? = null
)

// --- Additional Memo Service Models ---

data class ListMemoAttachmentsResponse(
    val attachments: List<Attachment>?, val nextPageToken: String?
)

data class SetMemoAttachmentsRequest(
    val name: String, val attachments: List<Attachment>
)

data class ListMemoCommentsResponse(
    val memos: List<Memo>?, val nextPageToken: String?, val totalSize: Int?
)

data class ListMemoReactionsResponse(
    val reactions: List<Reaction>?, val nextPageToken: String?, val totalSize: Int?
)

data class UpsertMemoReactionRequest(
    val name: String, val reaction: Reaction
)

data class ListMemoRelationsResponse(
    val relations: List<MemoRelation>?, val nextPageToken: String?
)

data class SetMemoRelationsRequest(
    val name: String, val relations: List<MemoRelation>
)
