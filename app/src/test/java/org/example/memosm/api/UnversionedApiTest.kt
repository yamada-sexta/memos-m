package org.example.memosm.api

import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.example.memosm.model.ListMemosResponse
import org.example.memosm.model.Memo
import org.example.memosm.model.PersonalAccessToken
import org.example.memosm.model.RefreshTokenResponse
import org.example.memosm.model.SSOCredentials
import org.example.memosm.model.SignInRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnversionedApiTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun `removed v1 routes discover the new API and preserve proxy prefixes`() =
        runBlocking {
            for (status in listOf(404, 410)) {
                for (suffix in listOf("", "api/", "api/v1/")) {
                    val paths = mutableListOf<String>()
                    val api =
                        MemosApiFactory.create(
                            "https://memos.example/notes/$suffix",
                            client { request ->
                                val path = request.url.encodedPath
                                paths += path
                                when (path) {
                                    "/notes/api/v1/instance/profile" -> {
                                        status to "{}"
                                    }

                                    "/notes/api/instance/profile" -> {
                                        200 to """{"version":"26.10.0"}"""
                                    }

                                    "/notes/api/memos" -> {
                                        200 to
                                            """{"memos":[{"name":"memos/one","content":"Hello","visibility":"PRIVATE"}]}"""
                                    }

                                    else -> {
                                        error("Unexpected path: $path")
                                    }
                                }
                            },
                        )
                    assertEquals("api", api.constants.apiPath)
                    assertEquals(
                        "Hello",
                        api
                            .listMemos()
                            .memos!!
                            .single()
                            .content,
                    )
                    assertEquals(
                        listOf(
                            "/notes/api/v1/instance/profile",
                            "/notes/api/instance/profile",
                            "/notes/api/memos",
                        ),
                        paths,
                    )
                }
            }
        }

    @Test
    fun `unrelated profile failures do not switch API roots`() =
        runBlocking {
            for (status in listOf(401, 403, 429, 500)) {
                val api =
                    MemosApiFactory.create(
                        "https://memos.example/notes/",
                        client { request ->
                            assertEquals("/notes/api/v1/instance/profile", request.url.encodedPath)
                            status to "{}"
                        },
                    )
                assertEquals("api/v1", api.constants.apiPath)
            }
        }

    @Test
    fun `new SSO schema and token expiry are mapped without changing legacy requests`() =
        runBlocking {
            val api =
                unversioned { request ->
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
            val response =
                api.signIn(
                    SignInRequest(
                        ssoCredentials =
                            SSOCredentials(
                                "identity-providers/company",
                                "code",
                                "app://callback",
                                "verifier",
                            ),
                    ),
                )
            assertEquals("token", response.accessToken)
            assertEquals("2026-10-17T00:00:00Z", response.accessTokenExpiresAt)
        }

    @Test
    fun `renamed comments settings and statistics remain available to callers`() =
        runBlocking {
            val api =
                unversioned { request ->
                    when (request.url.encodedPath) {
                        "/notes/api/memos/one/comments" -> """{"comments":[{"name":"memos/comment","content":"Comment","visibility":"PRIVATE"}]}"""
                        "/notes/api/users/admin/settings" -> """{"userSettings":[{"name":"users/admin/settings/GENERAL","generalSetting":{"locale":"en"}}]}"""
                        "/notes/api/users/-/stats" -> """{"userStats":[{"name":"users/admin/stats","tagCounts":{"tag":2}}]}"""
                        else -> error("Unexpected path: ${request.url.encodedPath}")
                    }
                }
            assertEquals(
                "Comment",
                api
                    .listMemoComments("memos/one")
                    .memos!!
                    .single()
                    .content,
            )
            assertEquals(
                "en",
                api
                    .listUserSettings("users/admin")
                    .settings!!
                    .single()
                    .generalSetting!!
                    .locale,
            )
            assertEquals(
                mapOf("tag" to 2),
                api
                    .listAllUserStats()
                    .stats!!
                    .single()
                    .tagCount,
            )
        }

    @Test
    fun `older unversioned servers keep dedicated comment routes`() = runBlocking {
        for (version in listOf("26.10.0", "26.11.0", "26.11.1", "26.11.16-dev.old", "26.11.21-dev.f4c95ef")) {
            var calls = 0
            val api = unversioned(version) { request ->
                calls++
                assertEquals("/notes/api/memos/parent/comments", request.url.encodedPath)
                if (request.method == "POST") {
                    assertEquals("retry-id", request.url.queryParameter("commentId"))
                    """{"name":"memos/comment","content":"Reply","visibility":"PRIVATE"}"""
                } else {
                    """{"comments":[{"name":"memos/comment","content":"Reply","visibility":"PRIVATE"}]}"""
                }
            }
            val comment = api.createMemoComment("memos/parent", Memo(content = "Reply"), "retry-id")
            assertEquals(comment, api.listMemoComments("memos/parent").memos!!.single())
            assertEquals(2, calls)
        }
    }

    @Test
    fun `new servers use parent memos for comments and exclude them from feeds`() =
        runBlocking {
            for (version in listOf("26.11.2", "26.11.22-dev.8b61d2e", "26.12.1-dev.next", "27.1.0")) {
                val requests = mutableListOf<Request>()
                val api =
                    unversioned(version) { request ->
                        requests += request
                        assertEquals("/notes/api/memos", request.url.encodedPath)
                        when (request.method) {
                            "POST" -> {
                                val body = JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject
                                assertEquals("memos/parent", body["parent"].asString)
                                assertEquals("Reply", body["content"].asString)
                                assertEquals("retry-id", request.url.queryParameter("memoId"))
                                """{"name":"memos/comment","content":"Reply","parent":"memos/parent","visibility":"PRIVATE"}"""
                            }

                            "GET" -> {
                                """{"memos":[{"name":"memos/comment","content":"Reply","parent":"memos/parent","visibility":"PRIVATE"}],"nextPageToken":"next"}"""
                            }

                            else -> {
                                error("Unexpected method: ${request.method}")
                            }
                        }
                    }
                val comment = api.createMemoComment("memos/parent", Memo(content = "Reply"), "retry-id")
                assertEquals("memos/parent", comment.parent)
                val page = api.listMemoComments("memos/parent", pageSize = 12, pageToken = "cursor", orderBy = "create_time asc")
                assertEquals(comment, page.memos!!.single())
                assertEquals("next", page.nextPageToken)
                assertEquals("""parent == "memos/parent"""", requests.last().url.queryParameter("filter"))
                assertEquals("12", requests.last().url.queryParameter("pageSize"))
                assertEquals("cursor", requests.last().url.queryParameter("pageToken"))
                assertEquals("create_time asc", requests.last().url.queryParameter("orderBy"))
                api.listMemos(filter = "pinned == true || 'work' in tags")
                assertEquals("(pinned == true || 'work' in tags) && parent == null", requests.last().url.queryParameter("filter"))
                api.listMemos()
                assertEquals("parent == null", requests.last().url.queryParameter("filter"))
                assertEquals(4, requests.size)
            }
        }

    @Test
    fun `time filter syntax follows the detected API generation`() =
        runBlocking {
            for ((version, timestampLiterals) in listOf(
                "0.26.0" to false,
                "0.27.0" to false,
                "0.28.0" to false,
                "0.29.0" to false,
                "0.30.0" to true,
                "0.35.3" to true,
            )) {
                val api = versioned(version)
                assertEquals(version, timestampLiterals, api.constants.celTimestampLiterals)
                assertEquals(
                    version,
                    if (timestampLiterals) "timestamp(1700000000)" else "1700000000",
                    api.celTimestamp(1700000000),
                )
            }

            val unversioned = unversioned { _ -> error("No request expected") }
            assertTrue(unversioned.constants.celTimestampLiterals)
            assertEquals("timestamp(1700000000)", unversioned.celTimestamp(1700000000))
        }

    @Test
    fun `time filter formatting uses overridden constants on delegated adapters`() =
        runBlocking {
            val api = unversioned { _ -> error("No request expected") }
            val legacy =
                object : MemosApi by api {
                    override val constants = api.constants.copy(celTimestampLiterals = false)
                }
            assertEquals("1700000000", legacy.celTimestamp(1700000000))
        }

    @Test
    fun `search filters omit blank queries and escape text and tags`() =
        runBlocking {
            val api = versioned("0.30.0")
            assertNull(api.buildMemoSearchFilter())
            assertNull(api.buildMemoSearchFilter(MemoSearchFilter(query = " \t\n")))
            assertEquals("tag in [\"trip\"]", api.buildMemoSearchFilter(MemoSearchFilter(query = " ", tags = setOf("trip"))))
            assertEquals(
                """content.contains("say \"hello\"\\path\nnext") && tag in ["a\"b\\c"] && tag in ["second"]""",
                api.buildMemoSearchFilter(MemoSearchFilter(query = "say \"hello\"\\path\nnext", tags = linkedSetOf("a\"b\\c", "second"))),
            )
        }

    @Test
    fun `search date bounds support epoch zero and include the whole selected end day`() =
        runBlocking {
            for ((api, start, end) in listOf(
                Triple(versioned("0.29.0"), "0", "86400"),
                Triple(versioned("0.30.0"), "timestamp(0)", "timestamp(86400)"),
                Triple(unversioned { error("No request expected") }, "timestamp(0)", "timestamp(86400)"),
            )) {
                assertEquals("created_ts >= $start", api.buildMemoSearchFilter(MemoSearchFilter(startDateMillis = 0)))
                assertEquals("created_ts < $end", api.buildMemoSearchFilter(MemoSearchFilter(endDateMillis = 0)))
                assertEquals(
                    "content.contains(\"coffee\") && tag in [\"trip\"] && created_ts >= $start && created_ts < $end",
                    api.buildMemoSearchFilter(
                        MemoSearchFilter(query = "coffee", tags = setOf("trip"), startDateMillis = 0, endDateMillis = 0),
                    ),
                )
            }
        }

    @Test
    fun `stored search criteria use each active adapter timestamp syntax`() =
        runBlocking {
            val criteria = MemoSearchFilter(query = "coffee", startDateMillis = 0, endDateMillis = 0)
            val filters =
                listOf(
                    versioned("0.29.0"),
                    versioned("0.30.0"),
                    unversioned { error("No request expected") },
                ).map { it.buildMemoSearchFilter(criteria) }
            assertEquals(
                listOf(
                    "content.contains(\"coffee\") && created_ts >= 0 && created_ts < 86400",
                    "content.contains(\"coffee\") && created_ts >= timestamp(0) && created_ts < timestamp(86400)",
                    "content.contains(\"coffee\") && created_ts >= timestamp(0) && created_ts < timestamp(86400)",
                ),
                filters,
            )
        }

    @Test
    fun `activities degrade to an empty page on servers without the activity service`() =
        runBlocking {
            val api = unversioned { _ -> error("No request expected") }
            val response = api.listActivities(pageSize = 1000)
            assertTrue(response.activities != null)
            assertEquals(0, response.activities!!.size)
            assertEquals(null, response.nextPageToken)
        }

    @Test
    fun `streaming uploads use the discovered API root and preserve the attachment id`() =
        runBlocking {
            val file = files.newFile("upload.txt")
            for (apiPath in listOf("api", "api/v1")) {
                val client =
                    client { request ->
                        assertEquals("/notes/$apiPath/attachments", request.url.encodedPath)
                        assertEquals("upload +&", request.url.queryParameter("attachmentId"))
                        val json = JsonParser.parseString(Buffer().apply { request.body!!.writeTo(this) }.readUtf8()).asJsonObject
                        assertEquals("upload.txt", json["filename"].asString)
                        assertEquals("text/plain", json["type"].asString)
                        assertEquals("", json["content"].asString)
                        200 to """{"name":"attachments/upload"}"""
                    }
                val attachment =
                    StreamingAttachmentApi(client, "https://memos.example/notes/", apiPath)
                        .createAttachmentFromFile(file, "text/plain", "upload +&")
                assertEquals("attachments/upload", attachment?.name)
            }
        }

    @Test
    fun `token timestamps accept both legacy and current JSON names`() {
        val timestamp = "2026-10-17T00:00:00Z"
        for ((created, expires, used) in listOf(
            Triple("createdAt", "expiresAt", "lastUsedAt"),
            Triple("createTime", "expireTime", "lastUseTime"),
        )) {
            val token =
                GsonProvider.gson.fromJson(
                    """{"$created":"$timestamp","$expires":"$timestamp","$used":"$timestamp"}""",
                    PersonalAccessToken::class.java,
                )
            assertEquals(timestamp, token.createdAt)
            assertEquals(timestamp, token.expiresAt)
            assertEquals(timestamp, token.lastUsedAt)
        }
        for (expires in listOf("expiresAt", "accessTokenExpireTime")) {
            val response =
                GsonProvider.gson.fromJson(
                    """{"accessToken":"token","$expires":"$timestamp"}""",
                    RefreshTokenResponse::class.java,
                )
            assertEquals(timestamp, response.expiresAt)
        }
    }

    private suspend fun versioned(version: String): MemosApi =
        MemosApiFactory.create(
            "https://memos.example/notes/",
            client { request ->
                assertEquals("/notes/api/v1/instance/profile", request.url.encodedPath)
                200 to """{"version":"$version"}"""
            },
        )

    private suspend fun unversioned(
        version: String = "26.10.0",
        respond: (Request) -> String,
    ): MemosApi =
        MemosApiFactory.create(
            "https://memos.example/notes/",
            client { request ->
                when (request.url.encodedPath) {
                    "/notes/api/v1/instance/profile" -> 410 to "{}"
                    "/notes/api/instance/profile" -> 200 to """{"version":"$version"}"""
                    else -> 200 to respond(request)
                }
            },
        )

    private fun client(respond: (Request) -> Pair<Int, String>): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor { chain ->
                val (status, body) = respond(chain.request())
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(status)
                    .message("Test response")
                    .body(body.toResponseBody())
                    .build()
            }.build()
}
