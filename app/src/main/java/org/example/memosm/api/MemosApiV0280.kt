package org.example.memosm.api

import android.util.Log
import com.google.gson.annotations.SerializedName
import kotlin.time.Instant
import org.example.memosm.model.Attachment
import org.example.memosm.model.CurrentSessionResponse
import org.example.memosm.model.ListMemoCommentsResponse
import org.example.memosm.model.ListMemosResponse
import org.example.memosm.model.ListUsersResponse
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoProperty
import org.example.memosm.model.MemoRelation
import org.example.memosm.model.MemoRelationType
import org.example.memosm.model.MemoSnippet
import org.example.memosm.model.MemoState
import org.example.memosm.model.Reaction
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInResponse
import org.example.memosm.model.UseRole
import org.example.memosm.model.UseState
import org.example.memosm.model.User
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.Visibility
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MemosApiV0280 : MemosApiV0270 {
    @GET("api/v1/auth/me")
    suspend fun getCurrentUserV0280(): GetCurrentUserResponseDtoV0280

    @POST("api/v1/auth/signin")
    suspend fun signInV0280(@Body request: SignInRequest): SignInResponseDtoV0280

    @GET("api/v1/users")
    suspend fun listUsersV0280(
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("filter") filter: String? = null,
        @Query("showDeleted") showDeleted: Boolean? = null
    ): ListUsersResponseV0280

    @POST("api/v1/users")
    suspend fun createUserV0280(
        @Body user: UserSnapshot,
        @Query("userId") userId: String? = null,
        @Query("validateOnly") validateOnly: Boolean? = null,
        @Query("requestId") requestId: String? = null
    ): UserV0280

    @GET("api/v1/{user}")
    suspend fun getUserV0280(
        @Path("user", encoded = true) user: String,
        @Query("readMask") readMask: String? = null
    ): UserV0280

    @PATCH("api/v1/{user}")
    suspend fun updateUserV0280(
        @Path("user", encoded = true) user: String,
        @Body userData: UserSnapshot,
        @Query("updateMask") updateMask: String,
        @Query("allowMissing") allowMissing: Boolean? = null
    ): UserV0280

    @GET("api/v1/memos")
    suspend fun listMemosV0280(
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("state") state: String? = null,
        @Query("orderBy") orderBy: String? = null,
        @Query("filter") filter: String? = null,
        @Query("showDeleted") showDeleted: Boolean? = null
    ): ListMemosResponseV0280

    @POST("api/v1/memos")
    suspend fun createMemoV0280(
        @Body memo: MemoV0280,
        @Query("memoId") memoId: String? = null
    ): MemoV0280

    @GET("api/v1/{memo}")
    suspend fun getMemoV0280(@Path("memo", encoded = true) memo: String): MemoV0280

    @PATCH("api/v1/{memo}")
    suspend fun updateMemoV0280(
        @Path("memo", encoded = true) memo: String,
        @Body memoData: MemoV0280,
        @Query("updateMask") updateMask: String
    ): MemoV0280

    @GET("api/v1/{memo}/comments")
    suspend fun listMemoCommentsV0280(
        @Path("memo", encoded = true) memo: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String? = null
    ): ListMemoCommentsResponseV0280

    @POST("api/v1/{memo}/comments")
    suspend fun createMemoCommentV0280(
        @Path("memo", encoded = true) memo: String,
        @Body comment: MemoV0280,
        @Query("commentId") commentId: String? = null
    ): MemoV0280
}

data class UserV0280(
    override val name: String? = null,
    override val role: UseRole? = null,
    override val username: String? = null,
    override val email: String? = null,
    override val displayName: String? = null,
    override val avatarUrl: String? = null,
    override val description: String? = null,
    override val password: String? = null,
    override val state: UseState? = null,
    override val createTime: String? = null,
    override val updateTime: String? = null,
    override val token: String? = null
) : User

private fun String?.nullIfBlank(): String? = this?.takeIf { it.isNotBlank() }

fun UserV0280.normalized(): UserV0280 = copy(
    name = name.nullIfBlank(),
    username = username.nullIfBlank(),
    email = email.nullIfBlank(),
    displayName = displayName.nullIfBlank(),
    avatarUrl = avatarUrl.nullIfBlank(),
    description = description.nullIfBlank(),
    password = password.nullIfBlank(),
    createTime = createTime.nullIfBlank(),
    updateTime = updateTime.nullIfBlank(),
    token = token.nullIfBlank()
)

data class GetCurrentUserResponseDtoV0280(val user: UserV0280) {
    fun toModel(): CurrentSessionResponse = CurrentSessionResponse(user = user.normalized())
}

data class SignInResponseDtoV0280(
    val user: UserV0280,
    val accessToken: String,
    val accessTokenExpiresAt: String
) {
    fun toModel(): SignInResponse = SignInResponse(
        user = user.normalized(),
        accessToken = accessToken,
        accessTokenExpiresAt = accessTokenExpiresAt
    )
}

data class ListUsersResponseV0280(
    val users: List<UserV0280>?,
    val nextPageToken: String? = null,
    val totalSize: Int? = null
) {
    fun toModel(): ListUsersResponse = ListUsersResponse(
        users = users?.map { it.normalized() },
        nextPageToken = nextPageToken,
        totalSize = totalSize
    )
}

data class MemoV0280(
    val name: String? = null,
    val state: MemoState? = null,
    val creator: String? = null,
    @SerializedName("createTime", alternate = ["create_time"]) val createTime: Instant? = null,
    @SerializedName("updateTime", alternate = ["update_time"]) val updateTime: Instant? = null,
    val content: String,
    val visibility: Visibility = Visibility.PRIVATE,
    val tags: List<String>? = null,
    val pinned: Boolean? = null,
    val attachments: List<Attachment>? = null,
    val relations: List<MemoRelationV0280>? = null,
    val reactions: List<Reaction>? = null,
    val property: MemoProperty? = null,
    val parent: String? = null,
    val snippet: String? = null,
    val location: Location? = null
) {
    fun toModel(): Memo {
        if (creator.isNullOrBlank()) {
            Log.w("MemosApiV0280", "Memo parse: missing creator for memo=$name")
        } else if (!creator.startsWith("users/")) {
            Log.w("MemosApiV0280", "Memo parse: unexpected creator format creator=$creator memo=$name")
        } else {
            Log.d(
                "MemosApiV0280",
                "Memo parse: memo=$name creator=$creator createTime=$createTime updateTime=$updateTime"
            )
        }

        return Memo(
            name = name,
            state = state,
            creator = creator,
            createTime = createTime,
            updateTime = updateTime,
            displayTime = createTime ?: updateTime,
            content = content,
            visibility = visibility,
            tags = tags,
            pinned = pinned,
            attachments = attachments,
            relations = relations?.map { it.toModel() },
            reactions = reactions,
            property = property,
            parent = parent,
            snippet = snippet,
            location = location
        )
    }

    companion object {
        fun fromModel(memo: Memo): MemoV0280 = MemoV0280(
            name = memo.name,
            state = memo.state,
            createTime = memo.createTime,
            updateTime = memo.updateTime,
            content = memo.content,
            visibility = memo.visibility,
            pinned = memo.pinned,
            attachments = memo.attachments,
            relations = memo.relations?.map { MemoRelationV0280.fromModel(it) },
            location = memo.location
        )
    }
}

data class MemoRelationV0280(
    val memo: MemoSnippet,
    val relatedMemo: MemoSnippet,
    val type: String
) {
    fun toModel(): MemoRelation = MemoRelation(
        memo = memo,
        relatedMemo = relatedMemo,
        type = when (type) {
            "COMMENT" -> MemoRelationType.COMMENT
            "REFERENCE" -> MemoRelationType.REPLY
            else -> MemoRelationType.TYPE_UNSPECIFIED
        }
    )

    companion object {
        fun fromModel(relation: MemoRelation): MemoRelationV0280 = MemoRelationV0280(
            memo = relation.memo,
            relatedMemo = relation.relatedMemo,
            type = when (relation.type) {
                MemoRelationType.COMMENT -> "COMMENT"
                MemoRelationType.REPLY -> "REFERENCE"
                MemoRelationType.TYPE_UNSPECIFIED -> "TYPE_UNSPECIFIED"
            }
        )
    }
}

data class ListMemosResponseV0280(
    val memos: List<MemoV0280>?,
    val nextPageToken: String? = null
) {
    fun toModel(): ListMemosResponse = ListMemosResponse(
        memos = memos?.map { it.toModel() },
        nextPageToken = nextPageToken
    )
}

data class ListMemoCommentsResponseV0280(
    val memos: List<MemoV0280>?,
    val nextPageToken: String? = null,
    val totalSize: Int? = null
) {
    fun toModel(): ListMemoCommentsResponse = ListMemoCommentsResponse(
        memos = memos?.map { it.toModel() },
        nextPageToken = nextPageToken,
        totalSize = totalSize
    )
}
