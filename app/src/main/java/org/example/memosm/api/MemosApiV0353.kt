package org.example.memosm.api

import org.example.memosm.model.LinkMetadata
import org.example.memosm.model.Activity
import org.example.memosm.model.Attachment
import org.example.memosm.model.CreatePersonalAccessTokenRequest
import org.example.memosm.model.CreatePersonalAccessTokenResponse
import org.example.memosm.model.CurrentSessionResponse
import org.example.memosm.model.IdentityProvider
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.InstanceSetting
import org.example.memosm.model.ListActivitiesResponse
import org.example.memosm.model.ListAllUserStatsResponse
import org.example.memosm.model.ListAttachmentsResponse
import org.example.memosm.model.ListIdentityProvidersResponse
import org.example.memosm.model.ListMemoAttachmentsResponse
import org.example.memosm.model.ListMemoCommentsResponse
import org.example.memosm.model.ListMemoReactionsResponse
import org.example.memosm.model.ListMemoRelationsResponse
import org.example.memosm.model.ListMemosResponse
import org.example.memosm.model.ListPersonalAccessTokensResponse
import org.example.memosm.model.ListUserNotificationsResponse
import org.example.memosm.model.ListUserSettingsResponse
import org.example.memosm.model.ListUserWebhooksResponse
import org.example.memosm.model.ListUsersResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.Reaction
import org.example.memosm.model.RefreshTokenRequest
import org.example.memosm.model.RefreshTokenResponse
import org.example.memosm.model.SetMemoAttachmentsRequest
import org.example.memosm.model.SetMemoRelationsRequest
import org.example.memosm.model.Shortcut
import org.example.memosm.model.ShortcutResponse
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInResponse
import org.example.memosm.model.UpsertMemoReactionRequest
import org.example.memosm.model.UseRole
import org.example.memosm.model.UseState
import org.example.memosm.model.User
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.UserNotification
import org.example.memosm.model.UserSetting
import org.example.memosm.model.UserStats
import org.example.memosm.model.UserWebhook
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MemosApiV0353 {

    @GET("api/v1/memos/-/linkMetadata")
    suspend fun getLinkMetadata(@Query("url") url: String): LinkMetadata

    // --- ActivityService ---
    @GET("api/v1/activities")
    suspend fun listActivities(
        @Query("pageSize") pageSize: Int? = null, @Query("pageToken") pageToken: String? = null
    ): ListActivitiesResponse

    @GET("api/v1/{activity}")
    suspend fun getActivity(@Path("activity", encoded = true) activity: String): Activity

    // --- AttachmentService ---
    @GET("api/v1/attachments")
    suspend fun listAttachments(
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("filter") filter: String? = null,
        @Query("orderBy") orderBy: String? = null
    ): ListAttachmentsResponse

    @POST("api/v1/attachments")
    suspend fun createAttachment(
        @Body attachment: Attachment, @Query("attachmentId") attachmentId: String? = null
    ): Attachment

    @GET("api/v1/{attachment}")
    suspend fun getAttachment(@Path("attachment", encoded = true) attachment: String): Attachment

    @DELETE("api/v1/{attachment}")
    suspend fun deleteAttachment(@Path("attachment", encoded = true) attachment: String)

    @PATCH("api/v1/{attachment}")
    suspend fun updateAttachment(
        @Path("attachment", encoded = true) attachment: String,
        @Body attachmentData: Attachment,
        @Query("updateMask") updateMask: String
    ): Attachment


    // --- AuthService ---
    @GET("api/v1/auth/me")
    suspend fun getCurrentSession(): CurrentSessionResponseV0353

    @POST("api/v1/auth/refresh")
    suspend fun refreshToken(@Body request: RefreshTokenRequest): RefreshTokenResponse

    @POST("api/v1/auth/signin")
    suspend fun signIn(@Body request: SignInRequest): SignInResponseV0353

    @POST("api/v1/auth/signout")
    suspend fun signOut()

    // --- IdentityProviderService ---
    @GET("api/v1/identity-providers")
    suspend fun listIdentityProviders(): ListIdentityProvidersResponse

    @POST("api/v1/identity-providers")
    suspend fun createIdentityProvider(
        @Body identityProvider: IdentityProvider,
        @Query("identityProviderId") identityProviderId: String? = null
    ): IdentityProvider

    @GET("api/v1/{identityProvider}")
    suspend fun getIdentityProvider(
        @Path(
            "identityProvider", encoded = true
        ) identityProvider: String
    ): IdentityProvider

    @DELETE("api/v1/{identityProvider}")
    suspend fun deleteIdentityProvider(
        @Path(
            "identityProvider", encoded = true
        ) identityProvider: String
    )

    @PATCH("api/v1/{identityProvider}")
    suspend fun updateIdentityProvider(
        @Path("identityProvider", encoded = true) identityProvider: String,
        @Body identityProviderData: IdentityProvider,
        @Query("updateMask") updateMask: String
    ): IdentityProvider

    // --- InstanceService ---
    @GET("api/v1/instance/profile")
    suspend fun getInstanceProfile(): InstanceProfile

    @GET("api/v1/instance/{instance}")
    suspend fun getInstanceSetting(
        @Path(
            "instance",
            encoded = true
        ) instance: String
    ): InstanceSetting

    @PATCH("api/v1/instance/{instance}/*")
    suspend fun updateInstanceSetting(
        @Path("instance") instance: String,
        @Body setting: InstanceSetting,
        @Query("updateMask") updateMask: String
    ): InstanceSetting

    // --- MemoService ---
    @GET("api/v1/memos")
    suspend fun listMemos(
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("state") state: String? = null,
        @Query("orderBy") orderBy: String? = null,
        @Query("filter") filter: String? = null,
        @Query("showDeleted") showDeleted: Boolean? = null
    ): ListMemosResponse

    @POST("api/v1/memos")
    suspend fun createMemo(
        @Body memo: Memo, @Query("memoId") memoId: String? = null
    ): Memo

    @GET("api/v1/{memo}")
    suspend fun getMemo(@Path("memo", encoded = true) memo: String): Memo

    @DELETE("api/v1/{memo}")
    suspend fun deleteMemo(
        @Path("memo", encoded = true) memo: String, @Query("force") force: Boolean? = null
    )

    @PATCH("api/v1/{memo}")
    suspend fun updateMemo(
        @Path("memo", encoded = true) memo: String,
        @Body memoData: Memo,
        @Query("updateMask") updateMask: String
    ): Memo

    @GET("api/v1/{memo}/attachments")
    suspend fun listMemoAttachments(
        @Path("memo", encoded = true) memo: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null
    ): ListMemoAttachmentsResponse

    @PATCH("api/v1/{memo}/attachments")
    suspend fun setMemoAttachments(
        @Path("memo", encoded = true) memo: String, @Body request: SetMemoAttachmentsRequest
    )

    @GET("api/v1/{memo}/comments")
    suspend fun listMemoComments(
        @Path("memo", encoded = true) memo: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("orderBy") orderBy: String? = null
    ): ListMemoCommentsResponse

    @POST("api/v1/{memo}/comments")
    suspend fun createMemoComment(
        @Path("memo", encoded = true) memo: String,
        @Body comment: Memo,
        @Query("commentId") commentId: String? = null
    ): Memo

    @GET("api/v1/{memo}/reactions")
    suspend fun listMemoReactions(
        @Path("memo", encoded = true) memo: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null
    ): ListMemoReactionsResponse

    @POST("api/v1/{memo}/reactions")
    suspend fun upsertMemoReaction(
        @Path("memo", encoded = true) memo: String, @Body request: UpsertMemoReactionRequest
    ): Reaction

    @DELETE("api/v1/{reaction}")
    suspend fun deleteMemoReaction(
        @Path("reaction", encoded = true) reaction: String
    )

    @GET("api/v1/{memo}/relations")
    suspend fun listMemoRelations(
        @Path("memo", encoded = true) memo: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null
    ): ListMemoRelationsResponse

    @PATCH("api/v1/{memo}/relations")
    suspend fun setMemoRelations(
        @Path("memo", encoded = true) memo: String, @Body request: SetMemoRelationsRequest
    )

    // --- UserService ---
    @GET("api/v1/users")
    suspend fun listUsers(
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("filter") filter: String? = null,
        @Query("showDeleted") showDeleted: Boolean? = null
    ): ListUsersResponseV0353

    @POST("api/v1/users")
    suspend fun createUser(
        @Body user: UserSnapshot,
        @Query("userId") userId: String? = null,
        @Query("validateOnly") validateOnly: Boolean? = null,
        @Query("requestId") requestId: String? = null
    ): UserV0353

    @GET("api/v1/{user}")
    suspend fun getUser(
        @Path("user", encoded = true) user: String, @Query("readMask") readMask: String? = null
    ): UserV0353

    @DELETE("api/v1/{user}")
    suspend fun deleteUser(
        @Path("user", encoded = true) user: String, @Query("force") force: Boolean? = null
    )

    @PATCH("api/v1/{user}")
    suspend fun updateUser(
        @Path("user", encoded = true) user: String,
        @Body userData: UserSnapshot,
        @Query("updateMask") updateMask: String,
        @Query("allowMissing") allowMissing: Boolean? = null
    ): UserV0353

    @GET("api/v1/{user}/notifications")
    suspend fun listUserNotifications(
        @Path("user", encoded = true) user: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null,
        @Query("filter") filter: String? = null
    ): ListUserNotificationsResponse

    @DELETE("api/v1/{user}/notifications/{notification}")
    suspend fun deleteUserNotification(
        @Path("user", encoded = true) user: String,
        @Path("notification", encoded = true) notification: String
    )

    @PATCH("api/v1/{user}/notifications/{notification}")
    suspend fun updateUserNotification(
        @Path("user", encoded = true) user: String,
        @Path("notification", encoded = true) notification: String,
        @Body notificationData: UserNotification,
        @Query("updateMask") updateMask: String
    ): UserNotification

    @GET("api/v1/{user}/personalAccessTokens")
    suspend fun listPersonalAccessTokens(
        @Path("user", encoded = true) user: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null
    ): ListPersonalAccessTokensResponse

    @POST("api/v1/{user}/personalAccessTokens")
    suspend fun createPersonalAccessToken(
        @Path("user", encoded = true) user: String, @Body request: CreatePersonalAccessTokenRequest
    ): CreatePersonalAccessTokenResponse

    @DELETE("api/v1/{user}/personalAccessTokens/{personalAccessToken}")
    suspend fun deletePersonalAccessToken(
        @Path("user", encoded = true) user: String,
        @Path("personalAccessToken", encoded = true) personalAccessToken: String
    )

    @GET("api/v1/{user}/settings")
    suspend fun listUserSettings(
        @Path("user", encoded = true) user: String,
        @Query("pageSize") pageSize: Int? = null,
        @Query("pageToken") pageToken: String? = null
    ): ListUserSettingsResponse

    @GET("api/v1/{user}/settings/{setting}")
    suspend fun getUserSetting(
        @Path("user", encoded = true) user: String, @Path("setting") setting: String
    ): UserSetting

    @PATCH("api/v1/{user}/settings/{setting}")
    suspend fun updateUserSetting(
        @Path("user", encoded = true) user: String,
        @Path("setting") setting: String,
        @Body settingData: UserSetting,
        @Query("updateMask") updateMask: String
    ): UserSetting

    @GET("api/v1/{user}/shortcuts")
    suspend fun getShortcuts(@Path("user", encoded = true) user: String): ShortcutResponse

    @POST("api/v1/{user}/shortcuts")
    suspend fun createShortcut(
        @Path("user", encoded = true) user: String,
        @Body shortcut: Shortcut,
        @Query("validateOnly") validateOnly: Boolean? = null
    ): Shortcut

    @DELETE("api/v1/{user}/shortcuts/{shortcut}")
    suspend fun deleteShortcut(
        @Path("user", encoded = true) user: String,
        @Path("shortcut", encoded = true) shortcut: String
    )

    @PATCH("api/v1/{user}/shortcuts/{shortcut}")
    suspend fun updateShortcut(
        @Path("user", encoded = true) user: String,
        @Path("shortcut", encoded = true) shortcut: String,
        @Body shortcutData: Shortcut,
        @Query("updateMask") updateMask: String? = null
    ): Shortcut

    @GET("api/v1/{user}/webhooks")
    suspend fun listUserWebhooks(
        @Path(
            "user", encoded = true
        ) user: String
    ): ListUserWebhooksResponse

    @POST("api/v1/{user}/webhooks")
    suspend fun createUserWebhook(
        @Path("user", encoded = true) user: String, @Body webhook: UserWebhook
    ): UserWebhook

    @DELETE("api/v1/{user}/webhooks/{webhook}")
    suspend fun deleteUserWebhook(
        @Path("user", encoded = true) user: String, @Path("webhook", encoded = true) webhook: String
    )

    @PATCH("api/v1/{user}/webhooks/{webhook}")
    suspend fun updateUserWebhook(
        @Path("user", encoded = true) user: String,
        @Path("webhook", encoded = true) webhook: String,
        @Body webhookData: UserWebhook,
        @Query("updateMask") updateMask: String
    ): UserWebhook

    @GET("api/v1/{user}:getStats")
    suspend fun getUserStats(@Path("user", encoded = true) user: String): UserStats

    @GET("api/v1/users:stats")
    suspend fun listAllUserStats(): ListAllUserStatsResponse
}

data class UserV0353(
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

data class CurrentSessionResponseV0353(val user: UserV0353?) {
    fun toModel(): CurrentSessionResponse = CurrentSessionResponse(user = user)
}

data class SignInResponseV0353(
    val user: UserV0353,
    val accessToken: String,
    val accessTokenExpiresAt: String
) {
    fun toModel(): SignInResponse = SignInResponse(
        user = user,
        accessToken = accessToken,
        accessTokenExpiresAt = accessTokenExpiresAt
    )
}

data class ListUsersResponseV0353(
    val users: List<UserV0353>?,
    val nextPageToken: String? = null,
    val totalSize: Int? = null
) {
    fun toModel(): ListUsersResponse = ListUsersResponse(
        users = users,
        nextPageToken = nextPageToken,
        totalSize = totalSize
    )
}
