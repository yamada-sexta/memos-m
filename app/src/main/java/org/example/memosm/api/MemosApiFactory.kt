package org.example.memosm.api

import android.util.Log
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object MemosApiFactory {

    suspend fun create(baseUrl: String, client: OkHttpClient): MemosApi {
        var normalizedBaseUrl = baseUrl.trimEnd('/') + "/"
        if (normalizedBaseUrl.endsWith("/api/v1/")) {
            normalizedBaseUrl = normalizedBaseUrl.removeSuffix("api/v1/")
        }

        val retrofit = Retrofit.Builder().baseUrl(normalizedBaseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonProvider.gson)).build()

        // Create the v0.35.3 API used for version probing.
        val v0353Api = retrofit.create(MemosApiV0353::class.java)
        fun v0280ApiImplementation(): MemosApi {
            val v0280Api = retrofit.create(MemosApiV0280::class.java)
            return MemosApiV0280Impl(v0280Api)
        }

        fun latestApiImplementation(): MemosApi {
            val v0300Api = retrofit.create(MemosApiV0300::class.java)
            return MemosApiV0300Impl(v0300Api)
        }

        fun latestApiFallback(reason: String, exception: Exception? = null): MemosApi {
            Log.w(
                "MemosApiFactory",
                "$reason, falling back to latest v0.30.0 API implementation",
                exception
            )
            return latestApiImplementation()
        }

        // Probe for version
        return try {
            val profile = v0353Api.getInstanceProfile()
            val version = profile.version ?: "Unknown"
            Log.i("MemosApiFactory", "Detected Memos server version: $version")

            if (version.startsWith("0.26")) {
                Log.i("MemosApiFactory", "Using v0.26.0 API implementation")
                val v0260Api = retrofit.create(MemosApiV0260::class.java)
                MemosApiV0260Impl(v0260Api)
            } else if (version.startsWith("0.27")) {
                Log.i("MemosApiFactory", "Using v0.27.0 API implementation")
                val v0270Api = retrofit.create(MemosApiV0270::class.java)
                MemosApiV0270Impl(v0270Api)
            } else if (version.startsWith("0.28") || version.startsWith("0.29")) {
                Log.i("MemosApiFactory", "Using v0.28.0 API implementation")
                v0280ApiImplementation()
            } else if (version.startsWith("0.30")) {
                Log.i("MemosApiFactory", "Using v0.30.0 API implementation")
                latestApiImplementation()
            } else {
                latestApiFallback("Unsupported or unknown Memos server version: $version")
            }
        } catch (e: Exception) {
            latestApiFallback("Failed to probe Memos server version", e)
        }
    }
}
