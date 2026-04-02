package org.example.memosm

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import java.util.concurrent.TimeUnit

class MemosApplication : Application(), SingletonImageLoader.Factory {

    lateinit var memoCacheRepository: MemoCacheRepository
        private set
    lateinit var dataStoreManager: DataStoreManager
        private set
    lateinit var draftManager: DraftManager
        private set
    lateinit var okHttpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        val database = MemoCacheDatabase.getInstance(this)
        memoCacheRepository = MemoCacheRepository(database.memoDao())

        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            produceFile = { preferencesDataStoreFile("settings") }
        )
        dataStoreManager = DataStoreManager(dataStore)
        draftManager = DraftManager(this)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val dispatcher = Dispatcher().apply {
            maxRequests = 5
            maxRequestsPerHost = 5
        }

        val okHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                add(SvgDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }

    companion object {
        lateinit var instance: MemosApplication
            private set
    }
}
