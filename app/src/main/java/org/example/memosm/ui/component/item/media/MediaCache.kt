package org.example.memosm.ui.component.item.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import org.example.memosm.data.DataStoreManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(UnstableApi::class)
object MediaCache {
    private val aspectRatios = ConcurrentHashMap<String, Float>()
    private var simpleCache: SimpleCache? = null

    /**
     * User-configurable media cache limit (MB). Written by [updateCacheLimit]
     * (fed from the DataStore flow by AppSettingsDelegate) - never read with
     * runBlocking on the calling thread.
     */
    @Volatile
    private var cacheLimitMb: Int = DataStoreManager.DEFAULT_THEME_CACHE_MAX_MB

    /** The limit [simpleCache] was built with; -1 until first construction. */
    private var builtWithLimitMb: Int = -1

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .build()
    }

    fun updateCacheLimit(mb: Int) {
        cacheLimitMb = mb
    }

    fun getAspectRatio(key: String?): Float? {
        if (key.isNullOrBlank()) return null
        return aspectRatios[key]
    }

    fun setAspectRatio(key: String?, ratio: Float) {
        if (key.isNullOrBlank()) return
        aspectRatios[key] = ratio
    }

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        // Rebuild when the configured limit changed since construction: the
        // LRU evictor size is fixed at SimpleCache creation time.
        val cache = simpleCache
        if (cache != null && builtWithLimitMb != cacheLimitMb) {
            try {
                cache.release()
            } catch (_: Exception) {
            }
            simpleCache = null
        }
        if (simpleCache == null) {
            val cacheDir = File(context.cacheDir, "media_cache")
            // 0 (or negative) = unlimited: NoOpCacheEvictor never evicts.
            val evictor = if (cacheLimitMb <= 0) {
                NoOpCacheEvictor()
            } else {
                LeastRecentlyUsedCacheEvictor(cacheLimitMb * 1024L * 1024L)
            }
            val databaseProvider = StandaloneDatabaseProvider(context)
            simpleCache = SimpleCache(cacheDir, evictor, databaseProvider)
            builtWithLimitMb = cacheLimitMb
        }
        return simpleCache!!
    }

    /**
     * Total bytes currently stored in the coil media cache directory.
     * Runs on the calling thread - wrap in Dispatchers.IO.
     */
    fun sizeBytes(context: Context): Long {
        val dir = File(context.cacheDir, "media_cache")
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Delete the entire coil media cache (files + index DB).
     */
    @Synchronized
    fun clear(context: Context) {
        try {
            simpleCache?.release()
        } catch (_: Exception) {
        }
        simpleCache = null
        builtWithLimitMb = -1
        val dir = File(context.cacheDir, "media_cache")
        if (dir.exists()) {
            try {
                dir.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    fun createDataSourceFactory(context: Context, token: String?): DataSource.Factory {
        val httpDataSourceFactory: HttpDataSource.Factory = if (token != null) {
            OkHttpDataSource.Factory(okHttpClient)
                .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))
        } else {
            OkHttpDataSource.Factory(okHttpClient)
        }

        val defaultDataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        return CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            // No setCacheWriteDataSinkFactory(null): the default write sink
            // persists streamed video/audio into the SimpleCache, so replaying
            // media is served from disk (offline-capable up to the cache cap).
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
