package org.example.memosm.api

import org.example.memosm.model.ListActivitiesResponse
import org.example.memosm.model.ListAllUserStatsResponse
import org.example.memosm.model.ListMemoCommentsResponse
import org.example.memosm.model.ListMemosResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.PasswordCredentials
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.SignInResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/** REST schema introduced when Memos removed the /api/v1 version marker. */
interface MemosApiUnversioned : MemosApiV0300 {
    @GET("users/-/stats")
    override suspend fun listAllUserStats(): ListAllUserStatsResponse

    @POST("auth/signin")
    suspend fun signInUnversioned(@Body request: SignInRequestUnversioned): SignInResponseDtoV0280
}

data class SignInRequestUnversioned(
    val passwordCredentials: PasswordCredentials?,
    val ssoCredentials: SsoCredentialsUnversioned?
)

data class SsoCredentialsUnversioned(
    val identityProvider: String,
    val code: String,
    val redirectUri: String,
    val codeVerifier: String
)

class MemosApiUnversionedImpl(
    private val api: MemosApiUnversioned,
    private val delegate: MemosApi = MemosApiV0300Impl(api),
    private val commentsAsMemos: Boolean = false,
) : MemosApi by delegate {
    override val constants = delegate.constants.copy(apiPath = "api")

    // Since 26.11.2, comments are memos with a parent. Keep top-level feeds and
    // comment queries separate, and choose the write route before sending it.
    override suspend fun listMemos(
        pageSize: Int?,
        pageToken: String?,
        state: String?,
        orderBy: String?,
        filter: String?,
        showDeleted: Boolean?,
    ): ListMemosResponse =
        delegate.listMemos(
            pageSize,
            pageToken,
            state,
            orderBy,
            if (commentsAsMemos) {
                filter?.takeIf { it.isNotBlank() }?.let { "($it) && parent == null" } ?: "parent == null"
            } else {
                filter
            },
            showDeleted,
        )

    override suspend fun listMemoComments(
        memo: String,
        pageSize: Int?,
        pageToken: String?,
        orderBy: String?,
    ): ListMemoCommentsResponse {
        if (!commentsAsMemos) return delegate.listMemoComments(memo, pageSize, pageToken, orderBy)
        val page =
            delegate.listMemos(
                pageSize = pageSize,
                pageToken = pageToken,
                orderBy = orderBy,
                filter = "parent == ${GsonProvider.gson.toJson(memo)}",
            )
        return ListMemoCommentsResponse(page.memos, page.nextPageToken, null)
    }

    override suspend fun createMemoComment(
        memo: String,
        comment: Memo,
        commentId: String?,
    ): Memo =
        if (commentsAsMemos) {
            api.createMemoV0280(MemoV0280.fromModel(comment).copy(parent = memo), commentId).toModel()
        } else {
            delegate.createMemoComment(memo, comment, commentId)
        }

    // Memos 26.10 removed the activity service together with the /api/v1
    // marker. Answer with an empty page instead of a 404 so existing callers
    // keep working against servers without the service.
    override suspend fun listActivities(
        pageSize: Int?,
        pageToken: String?
    ): ListActivitiesResponse = ListActivitiesResponse(
        activities = emptyList(),
        nextPageToken = null
    )

    override suspend fun signIn(request: SignInRequest): SignInResponse =
        api.signInUnversioned(SignInRequestUnversioned(
            request.passwordCredentials,
            request.ssoCredentials?.let {
                SsoCredentialsUnversioned(it.idpName, it.code, it.redirectUri, it.codeVerifier)
            }
        )).toModel()
}
