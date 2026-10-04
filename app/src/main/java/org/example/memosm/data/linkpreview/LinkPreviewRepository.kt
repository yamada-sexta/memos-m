package org.example.memosm.data.linkpreview

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.example.memosm.model.LinkMetadata
import retrofit2.HttpException

/** One account activation's bounded cache and shared, cancellable server reads. */
class LinkPreviewRepository(
    private val scope: CoroutineScope,
    private val fetch: suspend (String) -> LinkMetadata,
    private val canFetch: () -> Boolean = { true },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = 256
) {
    private data class Entry(val request: Deferred<LinkMetadata?>, var expiresAt: Long = Long.MAX_VALUE)
    private val lock = Any()
    private val cache = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val permits = Semaphore(4)
    private var unsupported = false

    suspend fun get(input: String): LinkMetadata? {
        val url = input.trim()
        if (url.toHttpUrlOrNull() == null || !scope.isActive) return null
        val request = synchronized(lock) {
            val cached = cache[url]
            if (cached != null && cached.expiresAt > now()) return@synchronized cached.request
            cache.remove(url)
            if (unsupported || !canFetch()) return null
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    permits.withPermit {
                        if (!canFetch() || synchronized(lock) { unsupported }) {
                            synchronized(lock) { cache.remove(url) }
                            return@withPermit null
                        }
                        val metadata = try {
                            fetch(url)
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            if (exception is HttpException && exception.code() in listOf(404, 405, 501)) {
                                synchronized(lock) { unsupported = true }
                            }
                            null
                        }
                        scope.coroutineContext.ensureActive()
                        synchronized(lock) {
                            cache[url]?.expiresAt = now() + if (metadata == null) 300_000 else 86_400_000
                            // Active requests stay shared until completion, even at the cache limit.
                            while (cache.size > capacity) {
                                val oldest = cache.entries.firstOrNull { it.value.expiresAt != Long.MAX_VALUE } ?: break
                                cache.remove(oldest.key)
                            }
                        }
                        metadata
                    }
                } catch (exception: CancellationException) {
                    synchronized(lock) { cache.remove(url) }
                    throw exception
                }
            }.also { cache[url] = Entry(it) }
        }
        request.start()
        return request.await().takeIf { scope.isActive }
    }
}
