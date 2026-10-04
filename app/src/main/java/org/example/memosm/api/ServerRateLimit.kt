package org.example.memosm.api

import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

class RateLimitException(val retryAfterMillis: Long) : IOException("Server rate limit; retry later")

/** Shared by all HTTP clients for the same server and credential, including sync workers. */
class ServerRateLimit(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Key(val origin: String, val token: String)
    private val deadlines = mutableMapOf<Key, Long>()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changes = events.asSharedFlow()

    private fun key(url: HttpUrl, token: String) = Key("${url.scheme}://${url.host}:${url.port}", token)

    @Synchronized
    fun remaining(url: HttpUrl, token: String): Long {
        val key = key(url, token)
        val remaining = (deadlines[key] ?: 0) - nowMillis()
        if (remaining <= 0) deadlines.remove(key)
        return remaining.coerceAtLeast(0)
    }

    fun remaining(host: String, token: String): Long = host.toHttpUrlOrNull()?.let { remaining(it, token) } ?: 0

    @Synchronized
    fun record(url: HttpUrl, token: String, delayMillis: Long) {
        val now = nowMillis()
        deadlines.entries.removeAll { it.value <= now }
        val key = key(url, token)
        deadlines[key] = maxOf(deadlines[key] ?: 0, now + delayMillis)
        events.tryEmit(Unit)
    }

    companion object {
        val shared = ServerRateLimit()

        /** Memos supplies gRPC RetryInfo/ErrorInfo even when Retry-After is absent. */
        fun retryDelay(header: String?, body: String?, wallTimeMillis: Long = System.currentTimeMillis()): Long {
            val delays = mutableListOf<Double>()
            header?.trim()?.let { value ->
                value.toDoubleOrNull()?.takeIf { it >= 0 }?.let { delays += it }
                    ?: runCatching {
                        (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - wallTimeMillis) / 1000.0
                    }.getOrNull()?.takeIf { it >= 0 }?.let { delays += it }
            }
            runCatching {
                JsonParser.parseString(body).asJsonObject.getAsJsonArray("details")?.forEach { detail ->
                    val obj = detail.asJsonObject
                    obj.getAsJsonObject("metadata")?.get("retry_after_seconds")?.asString
                        ?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { delays += it }
                    obj.get("retryDelay")?.asString?.removeSuffix("s")
                        ?.toDoubleOrNull()?.takeIf { it >= 0 }?.let { delays += it }
                }
            }
            return ceil((delays.filter { it.isFinite() }.maxOrNull() ?: 60.0) * 1000)
                .toLong().coerceIn(1_000, 86_400_000)
        }

        fun retryDelay(error: Exception): Long? = when {
            error is RateLimitException -> error.retryAfterMillis
            error is HttpException && error.code() == 429 -> retryDelay(
                error.response()?.headers()?.get("Retry-After"), error.response()?.errorBody()?.string()
            )
            else -> null
        }
    }
}
