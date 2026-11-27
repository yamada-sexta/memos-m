package org.example.memosm.api

import android.util.Log
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object MemosApiFactory {

    private fun retrofit(baseUrl: String, client: OkHttpClient, apiPath: String = "api/v1"): Retrofit {
        val normalizedBaseUrl = requireNotNull(normalizeLoginServerUrl(baseUrl)) { "Invalid server URL" }
        return Retrofit.Builder().baseUrl("$normalizedBaseUrl$apiPath/").client(client)
            .addConverterFactory(GsonConverterFactory.create(GsonProvider.gson)).build()
    }

    /** Bind local state immediately; version discovery can run as a cancellable session read. */
    fun createLatest(baseUrl: String, client: OkHttpClient): MemosApi =
        MemosApiV0300Impl(retrofit(baseUrl, client).create(MemosApiV0300::class.java))

    suspend fun create(baseUrl: String, client: OkHttpClient): MemosApi {
        val retrofit = retrofit(baseUrl, client)

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
            val profile = try {
                v0353Api.getInstanceProfile()
            } catch (e: HttpException) {
                // New servers removed v1; probe the replacement with a read before
                // choosing its schema. Never retry authentication or writes.
                if (e.code() != 404 && e.code() != 410) throw e
                val unversioned = retrofit(baseUrl, client, "api").create(MemosApiUnversioned::class.java)
                val serverVersion = unversioned.getInstanceProfile().version.orEmpty().removePrefix("v")
                val version = serverVersion.substringBefore('-').split('.')
                val year = version.getOrNull(0)?.toIntOrNull() ?: 0
                val month = version.getOrNull(1)?.toIntOrNull() ?: 0
                val patch = version.getOrNull(2)?.toIntOrNull() ?: 0
                // Official canaries use the UTC day, while releases use a patch ordinal.
                val firstParentMemoPatch = if (serverVersion.contains("-dev")) 22 else 2
                val commentsAsMemos = year > 26 || (year == 26 && (month > 11 || (month == 11 && patch >= firstParentMemoPatch)))
                return MemosApiUnversionedImpl(unversioned, commentsAsMemos = commentsAsMemos)
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            latestApiFallback("Failed to probe Memos server version", e)
        }
    }
}
