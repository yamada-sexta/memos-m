package org.example.memosm.data.linkpreview

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.example.memosm.model.LinkMetadata
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class LinkPreviewRepositoryTest {
    private val url = "https://example.com/page"
    private val metadata = LinkMetadata(url, "Example", "Description")

    @Test
    fun `concurrent callers share one request and cached result`() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = LinkPreviewRepository(this, fetch = { calls++; started.complete(Unit); release.await(); metadata })
        val requests = List(10) { async { loader.get(url) } }
        started.await()
        release.complete(Unit)
        assertEquals(List(10) { metadata }, requests.awaitAll())
        assertEquals(metadata, loader.get(" $url "))
        assertEquals(1, calls)
    }

    @Test
    fun `success expiry and bounded eviction refetch metadata`() = runBlocking {
        var time = 0L
        var calls = 0
        val loader = LinkPreviewRepository(this, fetch = { calls++; metadata }, now = { time }, capacity = 2)
        loader.get(url)
        time = 86_399_999
        loader.get(url)
        assertEquals(1, calls)
        time++
        loader.get(url)
        assertEquals(2, calls)
        loader.get("https://example.com/2")
        loader.get("https://example.com/3")
        loader.get(url)
        assertEquals(5, calls)
    }

    @Test
    fun `transient failures retry after five minutes`() = runBlocking {
        var time = 0L
        var calls = 0
        val loader = LinkPreviewRepository(this, fetch = { if (++calls == 1) throw IOException(); metadata }, now = { time })
        assertNull(loader.get(url))
        assertNull(loader.get(url))
        assertEquals(1, calls)
        time = 300_000
        assertEquals(metadata, loader.get(url))
        assertEquals(2, calls)
    }

    @Test
    fun `unsupported endpoint stops requests for all URLs in that session`() = runBlocking {
        for (code in listOf(404, 405, 501)) {
            var calls = 0
            val loader = LinkPreviewRepository(this, fetch = {
                calls++
                throw HttpException(Response.error<LinkMetadata>(code, "".toResponseBody()))
            })
            assertNull(loader.get(url))
            assertNull(loader.get("https://example.com/other"))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `offline reads use cache and invalid schemes never fetch`() = runBlocking {
        var online = true
        var calls = 0
        val loader = LinkPreviewRepository(this, fetch = { calls++; metadata }, canFetch = { online })
        loader.get(url)
        online = false
        assertEquals(metadata, loader.get(url))
        assertNull(loader.get("https://example.com/other"))
        online = true
        listOf("mailto:a@example.com", "file:///tmp/image", "relative", "https://").forEach {
            assertNull(loader.get(it))
        }
        assertEquals(1, calls)
    }

    @Test
    fun `only four fetches run concurrently`() = runBlocking {
        var active = 0
        var maximum = 0
        val fourStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = LinkPreviewRepository(this, fetch = {
            active++
            maximum = maxOf(maximum, active)
            if (active == 4) fourStarted.complete(Unit)
            release.await()
            active--
            metadata
        })
        val requests = List(12) { async { loader.get("https://example.com/$it") } }
        fourStarted.await()
        assertEquals(4, active)
        release.complete(Unit)
        requests.awaitAll()
        assertEquals(4, maximum)
    }

    @Test
    fun `scrolling cancellation does not cancel a shared request`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val loader = LinkPreviewRepository(this, fetch = { calls++; started.complete(Unit); release.await(); metadata })
        val first = async { loader.get(url) }
        started.await()
        first.cancelAndJoin()
        val second = async { loader.get(url) }
        release.complete(Unit)
        assertEquals(metadata, second.await())
        assertEquals(1, calls)
    }

    @Test
    fun `session cancellation cancels server reads`() = runBlocking {
        val session = CoroutineScope(coroutineContext + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val loader = LinkPreviewRepository(session, fetch = {
            started.complete(Unit)
            try { CompletableDeferred<Unit>().await(); metadata }
            finally { cancelled.complete(Unit) }
        })
        val request = async { loader.get(url) }
        started.await()
        session.cancel()
        try { request.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        cancelled.await()
        assertNull(loader.get(url))
    }
}
