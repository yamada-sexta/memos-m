package org.example.memosm

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import kotlinx.coroutines.launch
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.di.appModule
import org.example.memosm.di.networkModule
import org.example.memosm.di.viewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class MemosApplication : Application(), SingletonImageLoader.Factory {

    lateinit var memoCacheRepository: MemoCacheRepository
        private set

    /**
     * Offline attachment cache manager, resolved lazily from Koin
     * (usable from deep composables without DI plumbing).
     */
    val attachmentCacheManager: org.example.memosm.data.media.AttachmentCacheManager
        get() = org.koin.core.context.GlobalContext.get()
            .get<org.example.memosm.data.media.AttachmentCacheManager>()

    override fun onCreate() {
        super.onCreate()
        instance = this
        org.example.memosm.data.backup.BackupCoordinator.beginStartupRecovery()

        startKoin {
            androidLogger()
            androidContext(this@MemosApplication)
            modules(appModule, networkModule, viewModelModule)
        }

        // Initialize Room database and cache repository
        val database = MemoCacheDatabase.getInstance(this)
        memoCacheRepository = MemoCacheRepository(org.example.memosm.data.backup.GuardedMemoDao(database.memoDao()))

        // Complete incoming or interrupted restores before creating sessions or scheduling work.
        val backup = org.koin.core.context.GlobalContext.get().get<org.example.memosm.data.backup.BackupService>()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                backup.recoverInterruptedRestore().onFailure {
                    org.example.memosm.data.backup.BackupCoordinator.recoveryError.value = "Restore was interrupted. Retry to finish restoring your data."
                }
                if (org.example.memosm.data.backup.BackupCoordinator.recoveryError.value == null) {
                    backup.restoreNativeIfPresent().onFailure {
                        org.example.memosm.data.backup.BackupCoordinator.recoveryError.value = getString(R.string.backup_native_recovery_failed)
                    }
                }
            } finally { org.example.memosm.data.backup.BackupCoordinator.finishStartupRecovery() }

            // Re-arm durable outbox work only after startup recovery finishes.
            if (org.example.memosm.data.backup.BackupCoordinator.recoveryError.value != null) return@launch
            val koin = org.koin.core.context.GlobalContext.get()
            val scheduler = koin.get<SyncWorkScheduler>()
            koin.get<DataStoreManager>().getAccounts().forEach { scheduler.schedule(it.id) }
        }
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
