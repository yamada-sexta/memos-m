package org.example.memosm.account

import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.example.memosm.api.GsonProvider
import org.example.memosm.api.MemosApi
import org.example.memosm.data.sync.*
import org.example.memosm.model.Memo
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import kotlin.time.Instant

class SyncRetryTest {
    private fun http(code: Int) = HttpException(Response.error<Unit>(code, "error".toResponseBody()))

    @Test fun `transient and auth failures preserve retryability`() {
        for (error in listOf(IOException("offline"), http(401), http(403), http(408), http(429), http(503))) {
            assertFalse(isRejectedMutation(error))
        }
        assertTrue(isRejectedMutation(http(400)))
        assertTrue(isRejectedMutation(http(413)))
    }

    @Test fun `missing deleted resource is acknowledged idempotently`() =
        runTest {
            val api =
                stub<MemosApi> { method, _ ->
                    check(method == "deleteMemo")
                    throw http(404)
                }
            val op = PendingOp.new("A", PendingOpType.DELETE, "local-1")
            assertNull(sendMutation(api, op, "memos/server-1", null, null).serverMemo)
        }

    @Test fun `comment retry uses same client id and returns actual created resource`() =
        runTest {
            val ids = mutableListOf<String?>()
            val created = Memo(name = "memos/server-assigned", content = "reply", parent = "memos/parent")
            val api =
                stub<MemosApi> { method, args ->
                    check(method == "createMemoComment")
                    assertEquals("memos/parent", args[0])
                    ids += args[2] as String?
                    created
                }
            val op = PendingOp.new("A", PendingOpType.COMMENT_CREATE, "offline-comment", "parent", id = "client-id")
            repeat(2) { assertEquals(created, sendMutation(api, op, null, "memos/parent", Memo(content = "reply")).serverMemo) }
            assertEquals(listOf("client-id", "client-id"), ids)
        }

    @Test fun `conflicting server edit returns durable conflict input without updating`() =
        runTest {
            val remote = Memo(name = "memos/1", content = "remote", updateTime = Instant.parse("2026-11-18T12:00:00Z"))
            val api =
                stub<MemosApi> { method, _ ->
                    check(method == "getMemo")
                    remote
                }
            val op = PendingOp.new("A", PendingOpType.UPDATE, "local-1", baseUpdateTime = "2026-11-17T12:00:00Z")
            val result = sendMutation(api, op, "memos/1", null, Memo(content = "local"))
            assertTrue(result.conflict)
            assertEquals(remote, result.serverMemo)
        }
}
