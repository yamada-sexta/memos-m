package org.example.memosm.api

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.example.memosm.model.SSOCredentials
import org.example.memosm.model.SignInRequest
import org.example.memosm.model.PersonalAccessToken
import org.example.memosm.model.RefreshTokenResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class UnversionedApiTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `removed v1 routes discover the new API and preserve proxy prefixes`() = runBlocking {
        for (status in listOf(404, 410)) {
            for (suffix in listOf("", "api/", "api/v1/")) {
                val paths = mutableListOf<String>()
                val api = MemosApiFactory.create("https://memos.example/notes/$suffix", client { request ->
                    val path = request.url.encodedPath
                    paths += path
                    when (path) {
                        "/notes/api/v1/instance/profile" -> status to "{}"
                        "/notes/api/instance/profile" -> 200 to """{"version":"26.10.0"}"""
                        "/notes/api/memos" -> 200 to """{"memos":[{"name":"memos/one","content":"Hello","visibility":"PRIVATE"}]}"""
                        else -> error("Unexpected path: $path")
                    }
                })
                assertEquals("api", api.constants.apiPath)
                assertEquals("Hello", api.listMemos().memos!!.single().content)
                assertEquals(listOf(
                    "/notes/api/v1/instance/profile", "/notes/api/instance/profile", "/notes/api/memos"
                ), paths)
            }
        }
    }

    @Test
    fun `unrelated profile failures do not switch API roots`() = runBlocking {
        for (status in listOf(401, 403, 429, 500)) {
            val api = MemosApiFactory.create("https://memos.example/notes/", client { request ->
                assertEquals("/notes/api/v1/instance/profile", request.url.encodedPath)
                status to "{}"
            })
            assertEquals("api/v1", api.constants.apiPath)
        }
    }

    @Test
    fun `new SSO schema and token expiry are mapped without changing legacy requests`() = runBlocking {
        val api = unversioned { request ->
            assertEquals("/notes/api/auth/signin", request.url.encodedPath)
            val json = JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject
            val credentials = json.getAsJsonObject("ssoCredentials")
            assertEquals("identity-providers/company", credentials["identityProvider"].asString)
            assertFalse(credentials.has("idpName"))
            assertEquals("code", credentials["code"].asString)
            assertEquals("app://callback", credentials["redirectUri"].asString)
            assertEquals("verifier", credentials["codeVerifier"].asString)
            """{"user":{"name":"users/admin"},"accessToken":"token","accessTokenExpireTime":"2026-10-17T00:00:00Z"}"""
        }
        val response = api.signIn(SignInRequest(ssoCredentials = SSOCredentials(
            "identity-providers/company", "code", "app://callback", "verifier"
        )))
        assertEquals("token", response.accessToken)
        assertEquals("2026-10-17T00:00:00Z", response.accessTokenExpiresAt)
    }

    @Test
    fun `renamed comments settings and statistics remain available to callers`() = runBlocking {
        val api = unversioned { request ->
            when (request.url.encodedPath) {
                "/notes/api/memos/one/comments" -> """{"comments":[{"name":"memos/comment","content":"Comment","visibility":"PRIVATE"}]}"""
                "/notes/api/users/admin/settings" -> """{"userSettings":[{"name":"users/admin/settings/GENERAL","generalSetting":{"locale":"en"}}]}"""
                "/notes/api/users/-/stats" -> """{"userStats":[{"name":"users/admin/stats","tagCounts":{"tag":2}}]}"""
                else -> error("Unexpected path: ${request.url.encodedPath}")
            }
        }
        assertEquals("Comment", api.listMemoComments("memos/one").memos!!.single().content)
        assertEquals("en", api.listUserSettings("users/admin").settings!!.single().generalSetting!!.locale)
        assertEquals(mapOf("tag" to 2), api.listAllUserStats().stats!!.single().tagCount)
    }

    @Test
    fun `time filter epochs use timestamp literals only on unversioned servers`() = runBlocking {
        val legacy = MemosApiFactory.create("https://memos.example/notes/", client { request ->
            assertEquals("/notes/api/v1/instance/profile", request.url.encodedPath)
            200 to """{"version":"0.30.0"}"""
        })
        assertEquals("1700000000", legacy.celTimestamp(1700000000))

        val unversioned = unversioned { _ -> error("No request expected") }
        assertEquals("timestamp(1700000000)", unversioned.celTimestamp(1700000000))
    }

    @Test
    fun `activities degrade to an empty page on servers without the activity service`() = runBlocking {
        val api = unversioned { _ -> error("No request expected") }
        val response = api.listActivities(pageSize = 1000)
        assertTrue(response.activities != null)
        assertEquals(0, response.activities!!.size)
        assertEquals(null, response.nextPageToken)
    }

    @Test
    fun `streaming uploads use the discovered API root and preserve the attachment id`() = runBlocking {
        val file = files.newFile("upload.txt")
        for (apiPath in listOf("api", "api/v1")) {
            val client = client { request ->
                assertEquals("/notes/$apiPath/attachments", request.url.encodedPath)
                assertEquals("upload +&", request.url.queryParameter("attachmentId"))
                val json = JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject
                assertEquals("upload.txt", json["filename"].asString)
                assertEquals("text/plain", json["type"].asString)
                assertEquals("", json["content"].asString)
                200 to """{"name":"attachments/upload"}"""
            }
            val attachment = StreamingAttachmentApi(client, "https://memos.example/notes/", apiPath)
                .createAttachmentFromFile(file, "text/plain", "upload +&")
            assertEquals("attachments/upload", attachment?.name)
        }
    }

    @Test
    fun `token timestamps accept both legacy and current JSON names`() {
        val timestamp = "2026-10-17T00:00:00Z"
        for ((created, expires, used) in listOf(
            Triple("createdAt", "expiresAt", "lastUsedAt"),
            Triple("createTime", "expireTime", "lastUseTime")
        )) {
            val token = GsonProvider.gson.fromJson(
                """{"$created":"$timestamp","$expires":"$timestamp","$used":"$timestamp"}""",
                PersonalAccessToken::class.java
            )
            assertEquals(timestamp, token.createdAt)
            assertEquals(timestamp, token.expiresAt)
            assertEquals(timestamp, token.lastUsedAt)
        }
        for (expires in listOf("expiresAt", "accessTokenExpireTime")) {
            val response = GsonProvider.gson.fromJson(
                """{"accessToken":"token","$expires":"$timestamp"}""", RefreshTokenResponse::class.java
            )
            assertEquals(timestamp, response.expiresAt)
        }
    }

    private suspend fun unversioned(respond: (Request) -> String): MemosApi =
        MemosApiFactory.create("https://memos.example/notes/", client { request ->
            when (request.url.encodedPath) {
                "/notes/api/v1/instance/profile" -> 410 to "{}"
                "/notes/api/instance/profile" -> 200 to """{"version":"26.10.0"}"""
                else -> 200 to respond(request)
            }
        })

    private fun client(respond: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val (status, body) = respond(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test response").body(body.toResponseBody()).build()
        }.build()
}
