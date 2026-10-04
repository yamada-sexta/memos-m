package org.example.memosm.api

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.example.memosm.model.PasswordCredentials
import org.example.memosm.model.SSOCredentials
import org.example.memosm.model.SignInRequest
import org.junit.Assert.*
import org.junit.Test

class SsoApiTest {
    private val versions = listOf("0.26.0", "0.27.0", "0.28.0", "0.29.0", "0.30.0", "0.31.0", "26.10")

    @Test fun `SSO request uses the correct provider identifier in every server adapter`() = runBlocking {
        versions.forEach { version ->
            val provider = if (version == "0.26.0") "identity-providers/12" else "identity-providers/company"
            val api = api(version) { json ->
                val credentials = json.getAsJsonObject("ssoCredentials")
                assertFalse(json.has("passwordCredentials"))
                if (version == "0.26.0") {
                    assertEquals(12, credentials["idpId"].asInt)
                    assertFalse(credentials.has("idpName"))
                } else {
                    assertEquals(provider, credentials["idpName"].asString)
                    assertFalse(credentials.has("idpId"))
                }
                assertEquals("code +&", credentials["code"].asString)
                assertEquals("org.example.memosm://oauth/callback", credentials["redirectUri"].asString)
                assertEquals("verifier", credentials["codeVerifier"].asString)
            }
            val response = api.signIn(SignInRequest(ssoCredentials = SSOCredentials(
                provider, "code +&", "org.example.memosm://oauth/callback", "verifier"
            )))
            assertEquals("session-token", response.accessToken)
            assertEquals("users/1", response.user.name)
        }
    }

    @Test fun `password requests remain unchanged across server adapters`() = runBlocking {
        versions.forEach { version ->
            api(version) { json ->
                assertEquals("""{"passwordCredentials":{"username":"user","password":"password"}}""", json.toString())
            }.signIn(SignInRequest(passwordCredentials = PasswordCredentials("user", "password")))
        }
    }

    @Test fun `exactly one credential method is required`() {
        assertThrows(IllegalArgumentException::class.java) { SignInRequest() }
        assertThrows(IllegalArgumentException::class.java) {
            SignInRequest(PasswordCredentials("user", "password"), SSOCredentials("identity-providers/a", "code", "uri", "verifier"))
        }
    }

    @Test fun `v026 refuses nonnumeric provider names before sending a sign-in request`() = runBlocking {
        val api = api("0.26.0") { error("Must not send invalid credentials") }
        try {
            api.signIn(SignInRequest(ssoCredentials = SSOCredentials("identity-providers/company", "code", "uri", "verifier")))
            fail("Expected a numeric provider ID validation failure")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun `discovery does not require a client secret`() = runBlocking {
        val provider = api("26.10") {}.listIdentityProviders().identityProviders!!.single()
        assertNull(provider.config.oauth2Config!!.clientSecret)
        assertEquals("Company", provider.title)
        SsoTransaction.create("https://memos.example/", provider, "org.example.memosm://oauth/callback")
        Unit
    }

    @Test fun `personal token helper verifies current user before creating and returns only PAT`() = runBlocking {
        val paths = mutableListOf<String>()
        val api = api("26.10", paths = paths) {}
        assertEquals("personal-token", createTokenForCurrentUser(api))
        assertTrue(paths.indexOf("/notes/api/v1/auth/me") < paths.indexOf("/notes/api/v1/users/1/personalAccessTokens"))
    }

    @Test fun `unverifiable user never creates a personal token`() = runBlocking {
        val paths = mutableListOf<String>()
        val api = api("26.10", paths = paths, userBody = "{\"user\":{}}") {}
        try {
            createTokenForCurrentUser(api)
            fail("Expected user verification to fail")
        } catch (_: IllegalStateException) { }
        assertFalse(paths.any { it.endsWith("personalAccessTokens") })
    }

    @Test fun `empty personal token is not returned as account credentials`() = runBlocking {
        val api = api("26.10", patBody = "{\"personalAccessToken\":{},\"token\":\"\"}") {}
        try {
            createTokenForCurrentUser(api)
            fail("Expected token creation to fail")
        } catch (_: IllegalStateException) { }
    }

    private suspend fun api(
        version: String, paths: MutableList<String> = mutableListOf(),
        userBody: String = """{"user":{"name":"users/1","username":"user"}}""",
        patBody: String = """{"personalAccessToken":{},"token":"personal-token"}""",
        inspect: (com.google.gson.JsonObject) -> Unit
    ): MemosApi {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            paths += request.url.encodedPath
            val body = when (request.url.encodedPath) {
                "/notes/api/v1/instance/profile" -> """{"version":"$version"}"""
                "/notes/api/v1/auth/signin" -> {
                    inspect(JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject)
                    """{"user":{"name":"users/1","username":"user"},"accessToken":"session-token","accessTokenExpiresAt":"2026-10-04T18:00:00Z"}"""
                }
                "/notes/api/v1/auth/me" -> userBody
                "/notes/api/v1/users/1/personalAccessTokens" -> {
                    val json = JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject
                    assertEquals("users/1", json["parent"].asString)
                    assertTrue(json["description"].asString.startsWith("MemosM"))
                    patBody
                }
                "/notes/api/v1/identity-providers" -> """{"identityProviders":[{"name":"identity-providers/company","title":"Company","type":"OAUTH2","config":{"oauth2Config":{"clientId":"client","authUrl":"https://idp.example/authorize","scopes":["openid"]}}}]}"""
                else -> error("Unexpected request path: ${request.url.encodedPath}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        return MemosApiFactory.create("https://memos.example/notes/", client)
    }
}
