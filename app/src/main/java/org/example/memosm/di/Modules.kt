package org.example.memosm.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import okhttp3.OkHttpClient
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.MemoCacheDatabase
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.data.audit.AuditDatabase
import org.example.memosm.data.audit.LocalRecoveryService
import org.example.memosm.data.audit.SyncAuditLogger
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.data.media.AttachmentUploadDao
import org.example.memosm.data.media.AttachmentUploadQueue
import org.example.memosm.data.media.CachedAttachmentDao
import org.example.memosm.data.network.ConnectivityObserver
import org.example.memosm.data.offline.AttachmentCacheStore
import org.example.memosm.data.offline.NotificationCacheStore
import org.example.memosm.data.offline.NotificationsSnapshotData
import org.example.memosm.data.offline.SessionCacheStore
import org.example.memosm.data.offline.SessionSnapshotData
import org.example.memosm.data.store.DataStoreSnapshotStore
import org.example.memosm.data.store.RoomAttachmentMetaStore
import org.example.memosm.data.store.SnapshotStore
import org.example.memosm.data.sync.SyncRepository
import org.example.memosm.data.sync.SyncWorkScheduler
import org.example.memosm.data.sync.WorkManagerSyncWorkScheduler
import org.example.memosm.viewmodel.MemosViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.util.concurrent.TimeUnit

val appModule = module {
    // DataStore
    single<DataStore<Preferences>> {
        PreferenceDataStoreFactory.create(
            produceFile = { androidContext().preferencesDataStoreFile("settings") }
        )
    }

    single { DataStoreManager(get()) }

    single { DraftManager(androidContext()) }

    // Room
    single { MemoCacheDatabase.getInstance(androidContext()) }
    single { get<MemoCacheDatabase>().memoDao() }
    single { get<MemoCacheDatabase>().pendingOpDao() }
    single { get<MemoCacheDatabase>().cachedAttachmentDao() }
    single { get<MemoCacheDatabase>().attachmentUploadDao() }
    single { get<MemoCacheDatabase>().cachedAttachmentMetaDao() }
    // The audit log lives in its own database so it survives cache-DB corruption.
    single { AuditDatabase.getInstance(androidContext()) }
    single { get<AuditDatabase>().syncAuditDao() }
    single { MemoCacheRepository(get()) }
    single { SyncRepository(get()) }
    single { SyncAuditLogger(get()) }
    single { AttachmentUploadQueue(androidContext(), get(), get()) }
    single { LocalRecoveryService(androidContext(), get(), get(), get()) }
    single<SyncWorkScheduler> { WorkManagerSyncWorkScheduler(androidContext()) }

    // Network state monitoring
    single { ConnectivityObserver(androidContext()) }

    // Offline attachment downloads
    single {
        AttachmentCacheManager(
            context = androidContext(),
            dao = get<CachedAttachmentDao>(),
            okHttpClient = get(),
            dataStoreManager = get(),
            isWifiProvider = { get<ConnectivityObserver>().isWifi.value }
        )
    }

    // --- Offline-first cache layers ---
    // Storage SPI adapters (data/store/); business code consumes the
    // facades below, never these adapters directly.
    single { RoomAttachmentMetaStore(get()) }
    single<SnapshotStore<SessionSnapshotData>> {
        DataStoreSnapshotStore(
            dataStoreManager = get(),
            domain = "session",
            type = SessionSnapshotData::class.java
        )
    }
    single<SnapshotStore<NotificationsSnapshotData>> {
        DataStoreSnapshotStore(
            dataStoreManager = get(),
            domain = "notifications",
            type = NotificationsSnapshotData::class.java
        )
    }

    // Cache-abstraction facades (data/offline/)
    single { SessionCacheStore(get()) }
    single { NotificationCacheStore(get()) }
    single { AttachmentCacheStore(get(), get(), get()) }
}

val networkModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

val viewModelModule = module {
    viewModel {
        MemosViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}
