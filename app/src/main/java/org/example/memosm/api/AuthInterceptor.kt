package org.example.memosm.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(private var token: String, private val rateLimit: ServerRateLimit = ServerRateLimit.shared) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        if (token.isNotEmpty()) {
            builder.header("Authorization", "Bearer $token")
        }

        val request = builder.build()
        val remaining = rateLimit.remaining(request.url, token)
        if (remaining > 0) throw RateLimitException(remaining)
        Log.d("MemosApi", "--> ${request.method} ${request.url}")
        if (token.isNotEmpty()) {
            Log.d("MemosApi", "Authorization: Bearer ${token.take(10)}...")
        }

        val startTime = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: Exception) {
            Log.e("MemosApi", "<-- HTTP FAILED: $e")
            throw e
        }
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1e6

        Log.d("MemosApi", "<-- ${response.code} ${request.url} (${durationMs.toInt()}ms)")

        if (response.code == 429) {
            // Peek rather than consume: Retrofit and callers still receive the original error.
            val body = runCatching { response.peekBody(65_536).string() }.getOrNull()
            rateLimit.record(request.url, token, ServerRateLimit.retryDelay(response.header("Retry-After"), body))
        }

        return response
    }
}
