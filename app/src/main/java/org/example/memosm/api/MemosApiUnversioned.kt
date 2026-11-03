package org.example.memosm.api

import org.example.memosm.model.ListActivitiesResponse
import org.example.memosm.model.ListAllUserStatsResponse
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
    private val delegate: MemosApi = MemosApiV0300Impl(api)
) : MemosApi by delegate {
    override val constants = delegate.constants.copy(
        apiPath = "api",
        celTimestampLiterals = true
    )

    // Class delegation forwards interface default methods to the delegate,
    // whose constants predate the timestamp literal requirement. Invoke the
    // default implementation against the constants of this class instead.
    override fun celTimestamp(epochSeconds: Long): String =
        super<MemosApi>.celTimestamp(epochSeconds)

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
