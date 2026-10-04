package org.example.memosm.api

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

class ServerRateLimitTest {
    private val error = """{"code":8,"details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","metadata":{"retry_after_seconds":"16"}},{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"16s"}]}"""

    @Test fun `all account clients stop network requests during cooldown and resume afterwards`() {
        var clock = 0L
        var calls = 0
        val gate = ServerRateLimit { clock }
        fun client(token: String) = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(token, gate))
            .addInterceptor { chain ->
                calls++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(if (calls == 1) 429 else 200).message("test")
                    .body((if (calls == 1) error else "ok").toResponseBody()).build()
            }.build()
        val first = client("A")
        val second = client("A")
        fun request(client: OkHttpClient, host: String = "https://example.com", path: String = "/api/v1/instance/profile", method: String = "GET") {
            val builder = Request.Builder().url(host + path)
            if (method == "POST") builder.post("{}".toRequestBody())
            client.newCall(builder.build()).execute().use { response ->
                if (response.code == 429) assertEquals(error, response.body!!.string())
            }
        }
        request(first)
        clock = 1_000
        for (path in listOf("/api/v1/memos", "/api/v1/users/nannoda", "/api/v1/instance/profile")) {
            try { request(second, path = path); fail("Cooldown must block the request") }
            catch (e: RateLimitException) { assertEquals(15_000, e.retryAfterMillis) }
        }
        try { request(second, path = "/api/v1/memos", method = "POST"); fail("Must defer writes too") }
        catch (_: RateLimitException) { }
        assertEquals(1, calls)
        request(client("B"))
        request(second, host = "https://other.example.com")
        assertEquals(3, calls)
        clock = 16_000
        request(second)
        assertEquals(4, calls)
        assertEquals(0, gate.remaining("https://example.com", "A"))
    }

    @Test fun `cooldown honors headers dates grpc metadata and fractional durations`() {
        assertEquals(16_000, ServerRateLimit.retryDelay(null, error))
        assertEquals(20_000, ServerRateLimit.retryDelay("20", error))
        assertEquals(1_250, ServerRateLimit.retryDelay(null, """{"details":[{"retryDelay":"1.25s"}]}"""))
        assertEquals(20_000, ServerRateLimit.retryDelay("Thu, 01 Jan 1970 00:00:20 GMT", null, 0))
        assertEquals(60_000, ServerRateLimit.retryDelay("invalid", "invalid"))
        assertEquals(1_000, ServerRateLimit.retryDelay("0", null))
    }

    @Test fun `late concurrent 429 cannot shorten a cooldown`() {
        var clock = 0L
        val gate = ServerRateLimit { clock }
        val url = Request.Builder().url("https://example.com/api/v1/memos").build().url
        gate.record(url, "A", 16_000)
        clock = 1_000
        gate.record(url, "A", 4_000)
        assertEquals(15_000, gate.remaining(url, "A"))
    }
    @Test fun `real HTTP 429 pauses requests and monitor resumes without another refresh`() = kotlinx.coroutines.runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val recovered = java.util.concurrent.atomic.AtomicInteger()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/instance/profile") { exchange ->
            val attempt = calls.incrementAndGet()
            val body = if (attempt == 1) """{"details":[{"retryDelay":"1s"}]}""" else """{"version":"0.30.0"}"""
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (attempt == 1) 429 else 200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1))
        try {
            val gate = ServerRateLimit()
            val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor("test-only", gate)).build()
            val api = MemosApiFactory.createLatest("http://127.0.0.1:${server.address.port}/", client)
            val monitor = org.example.memosm.viewmodel.manager.ServerReachabilityMonitor(scope, { api }, { "A" })
            scope.async {
                monitor.start { recovered.incrementAndGet() }
                monitor.checkNow()?.join()
            }.await()
            assertEquals(org.example.memosm.viewmodel.ConnectionState.RATE_LIMITED, monitor.state.value.connectionState)
            assertTrue(monitor.state.value.isOnline)
            repeat(3) {
                try { api.getInstanceProfile(); fail("Must not contact the server during cooldown") }
                catch (_: RateLimitException) { }
            }
            assertEquals(1, calls.get())
            kotlinx.coroutines.withTimeout(5_000) {
                monitor.state.first { it.connectionState == org.example.memosm.viewmodel.ConnectionState.ONLINE }
            }
            // State publication and its callback run sequentially on the owning dispatcher.
            scope.async { assertEquals(1, recovered.get()); monitor.stop() }.await()
            assertEquals(2, calls.get())
        } finally {
            scope.cancel()
            server.stop(0)
        }
    }

}
