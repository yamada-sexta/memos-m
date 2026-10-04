package org.example.memosm.model

import com.google.gson.annotations.SerializedName

data class CurrentSessionResponse(
    val user: User?
)

enum class UseState {
    @SerializedName("NORMAL")
    NORMAL,

    @SerializedName("DELETED")
    DELETED,

    @SerializedName("BANNED")
    BANNED
}

enum class UseRole {
    @SerializedName("ADMIN")
    ADMIN,

    @SerializedName("USER")
    USER
}


interface User {
    val name: String?
    val role: UseRole?
    val username: String?
    val email: String?
    val displayName: String?
    val avatarUrl: String?
    val description: String?
    val password: String?
    val state: UseState?
    val createTime: String?
    val updateTime: String?
    val token: String?
}

data class UserSnapshot(
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

fun User.toUserSnapshot(token: String? = this.token): UserSnapshot = UserSnapshot(
    name = name,
    role = role,
    username = username,
    email = email,
    displayName = displayName,
    avatarUrl = avatarUrl,
    description = description,
    password = password,
    state = state,
    createTime = createTime,
    updateTime = updateTime,
    token = token
)

data class UserStats(
    val name: String? = null,
    @SerializedName(
        value = "memoDisplayTimestamps",
        alternate = [
            "memoCreatedTimestamps",
            "memo_display_timestamps",
            "memo_created_timestamps"
        ]
    )
    val memoDisplayTimestamps: List<String>? = null,
    @SerializedName(value = "memoTypeStats", alternate = ["memo_type_stats"])
    val memoTypeStats: MemoTypeStats? = null,
    @SerializedName(value = "tagCount", alternate = ["tag_count"])
    val tagCount: Map<String, Int>? = null,
    @SerializedName(value = "pinnedMemos", alternate = ["pinned_memos"])
    val pinnedMemos: List<String>? = null,
    @SerializedName(value = "totalMemoCount", alternate = ["total_memo_count"])
    val totalMemoCount: Int? = null
)

data class MemoTypeStats(
    @SerializedName(value = "linkCount", alternate = ["link_count"])
    val linkCount: Int? = null,
    @SerializedName(value = "codeCount", alternate = ["code_count"])
    val codeCount: Int? = null,
    @SerializedName(value = "todoCount", alternate = ["todo_count"])
    val todoCount: Int? = null,
    @SerializedName(value = "undoCount", alternate = ["undo_count"])
    val undoCount: Int? = null
)

data class ShortcutResponse(
    val shortcuts: List<Shortcut>? = null
)

data class Shortcut(
    val name: String? = null, val title: String? = null, val filter: String? = null
)

data class InstanceProfile(
    val owner: String? = null,
    val version: String? = null,
    val mode: String? = null, // Deprecated in v0.26.0
    val instanceUrl: String? = null,
    val demo: Boolean? = null, // Added in v0.26.0
    val admin: UserSnapshot? = null // Added in v0.26.0
)

// --- Auth Models ---

data class RefreshTokenRequest(
    val dummy: String? = null // Usually empty
)

data class RefreshTokenResponse(
    // @SerializedName("access_token")
    val accessToken: String,
    // @SerializedName("expires_at")
    val expiresAt: String? = null
)

data class SignInRequest(
    val passwordCredentials: PasswordCredentials? = null,
    val ssoCredentials: SSOCredentials? = null
) {
    init {
        require((passwordCredentials != null) xor (ssoCredentials != null)) {
            "Provide exactly one authentication method"
        }
    }
}

data class PasswordCredentials(
    val username: String, val password: String
)

data class SSOCredentials(
    val idpName: String, val code: String, val redirectUri: String, val codeVerifier: String
)

data class SignInResponse(
    val user: User, val accessToken: String, val accessTokenExpiresAt: String
)

data class GetCurrentUserResponse(
    val user: User
)

// --- Identity Provider Models ---

data class ListIdentityProvidersResponse(
    val identityProviders: List<IdentityProvider>?
)

data class IdentityProvider(
    val name: String? = null,
    val type: String,
    val title: String,
    val identifierFilter: String? = null,
    val config: IdentityProviderConfig
)

data class IdentityProviderConfig(
    val oauth2Config: OAuth2Config? = null
)

data class OAuth2Config(
    val clientId: String,
    val clientSecret: String? = null,
    val authUrl: String,
    val tokenUrl: String,
    val userInfoUrl: String,
    val scopes: List<String>,
    val fieldMapping: FieldMapping
)

data class FieldMapping(
    val identifier: String, val displayName: String, val email: String, val avatarUrl: String
)

// --- Instance Models ---

data class InstanceSetting(
    val name: String? = null,
    val generalSetting: GeneralSetting? = null,
    val storageSetting: StorageSetting? = null,
    val memoRelatedSetting: MemoRelatedSetting? = null
)

data class GeneralSetting(
    val disallowUserRegistration: Boolean? = null,
    val disallowPasswordAuth: Boolean? = null,
    val additionalScript: String? = null,
    val additionalStyle: String? = null,
    val customProfile: CustomProfile? = null,
    // week_start_day_offset is the week start day offset from Sunday.
    // 0: Sunday, 1: Monday, 2: Tuesday, 3: Wednesday, 4: Thursday, 5: Friday, 6: Saturday
    // Default is Sunday.
    val weekStartDayOffset: Int? = null,
    val disallowChangeUsername: Boolean? = null,
    val disallowChangeNickname: Boolean? = null
)

data class CustomProfile(
    val title: String? = null, val description: String? = null, val logoUrl: String? = null
)

data class MemoRelatedSetting(
    val disallowPublicVisibility: Boolean? = null,
    val displayWithUpdateTime: Boolean? = null,
    val contentLengthLimit: Int? = null,
    val enableDoubleClickEdit: Boolean? = null,
    val reactions: List<String>? = null
)


data class StorageSetting(
    val storageType: StorageType? = null,
    val filepathTemplate: String? = null,
    val uploadSizeLimitMb: String? = null,
    val s3Config: S3Config? = null
)

enum class StorageType {
    @SerializedName("STORAGE_TYPE_UNSPECIFIED")
    STORAGE_TYPE_UNSPECIFIED,

    @SerializedName("DATABASE")
    DATABASE,

    @SerializedName("LOCAL")
    LOCAL,

    @SerializedName("S3")
    S3
}


data class S3Config(
    val accessKeyId: String,
    val accessKeySecret: String,
    val endpoint: String,
    val region: String,
    val bucket: String,
    val usePathStyle: Boolean
)

// --- User Expansion Models ---

data class ListUsersResponse(
    val users: List<User>?, val nextPageToken: String? = null, val totalSize: Int? = null
)

data class ListUserNotificationsResponse(
    val notifications: List<UserNotification>?, val nextPageToken: String?
)

data class UserNotification(
    val name: String? = null,
    val sender: String? = null,
    val status: String? = null,
    val createTime: String? = null,
    val type: String? = null,
    val activityId: Int? = null
)

data class ListPersonalAccessTokensResponse(
    val personalAccessTokens: List<PersonalAccessToken>?,
    val nextPageToken: String?,
    val totalSize: Int?
)

data class PersonalAccessToken(
    val name: String? = null,
    val description: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    val lastUsedAt: String? = null
)

data class CreatePersonalAccessTokenRequest(
    val parent: String, val description: String? = null, val expiresInDays: Int? = null
)

data class CreatePersonalAccessTokenResponse(
    val personalAccessToken: PersonalAccessToken, val token: String
)

data class ListUserSettingsResponse(
    val settings: List<UserSetting>?, val nextPageToken: String?, val totalSize: Int?
)

data class UserSetting(
    val name: String? = null, @SerializedName(
        "generalSetting", alternate = ["general_setting", "general", "GENERAL", "GeneralSetting"]
    ) val generalSetting: UserGeneralSetting? = null, @SerializedName(
        "webhooksSetting", alternate = ["webhooks_setting"]
    ) val webhooksSetting: UserWebhooksSetting? = null
)

data class UserGeneralSetting(
    val locale: String? = null, @SerializedName(
        "memoVisibility", alternate = ["memo_visibility"]
    ) val memoVisibility: Visibility? = null, val theme: String? = null
)

data class UserWebhooksSetting(
    val webhooks: List<UserWebhook>? = null
)

data class ListUserWebhooksResponse(
    val webhooks: List<UserWebhook>?
)

data class UserWebhook(
    val name: String? = null,
    val url: String,
    val displayName: String? = null,
    val createTime: String? = null,
    val updateTime: String? = null
)

data class ListAllUserStatsResponse(
    val stats: List<UserStats>?
)
