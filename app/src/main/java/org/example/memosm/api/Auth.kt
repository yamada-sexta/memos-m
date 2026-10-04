package org.example.memosm.api

import okhttp3.OkHttpClient
import org.example.memosm.model.CreatePersonalAccessTokenRequest
import org.example.memosm.model.PasswordCredentials
import org.example.memosm.model.SignInRequest

/** Sign in with a password and create the long-lived token used by account storage. */
suspend fun loginAndCreateToken(
    api: MemosApi, baseUrl: String, username: String, password: String
): String = signInAndCreateToken(
    api, baseUrl, SignInRequest(passwordCredentials = PasswordCredentials(username.trim(), password))
)

/** Both password and SSO exchange session credentials for a personal access token. */
suspend fun signInAndCreateToken(api: MemosApi, baseUrl: String, request: SignInRequest): String {
    val session = api.signIn(request)
    check(session.accessToken.isNotBlank()) { "Sign-in returned no access token" }
    return createTokenFromSession(baseUrl, session.accessToken)
}

internal suspend fun createTokenFromSession(baseUrl: String, accessToken: String): String {
    // Do not log session responses, authorization codes, or tokens.
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder()
            .header("Authorization", "Bearer $accessToken").build())
    }.build()
    return createTokenForCurrentUser(MemosApiFactory.create(baseUrl, client))
}

internal suspend fun createTokenForCurrentUser(api: MemosApi): String {
    val userName = api.getCurrentSession().user?.name
    check(!userName.isNullOrBlank()) { "Unable to verify the signed-in user" }
    val result = api.createPersonalAccessToken(
        userName, CreatePersonalAccessTokenRequest(
            parent = userName, description = "MemosM${System.currentTimeMillis()}"
        )
    )
    check(result.token.isNotBlank()) { "Server returned no personal access token" }
    return result.token
}
