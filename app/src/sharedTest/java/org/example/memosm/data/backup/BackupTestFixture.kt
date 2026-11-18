package org.example.memosm.data.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.MemoCacheDatabase
import java.io.Closeable
import java.io.File
import java.util.UUID

class BackupTestFixture : Closeable {
    private val base: Context = ApplicationProvider.getApplicationContext()
    val root = File(base.cacheDir, "backup-test-${UUID.randomUUID()}").apply { mkdirs() }
    val context =
        object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this

            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }

            override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
        }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    var failPreferences = false
    private val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "settings.preferences_pb") })
    private val failingStore =
        object : DataStore<Preferences> {
            override val data = store.data

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failPreferences) error("Injected preferences failure")
                return store.updateData(transform)
            }
        }
    val settings = DataStoreManager(failingStore)
    val database = Room.inMemoryDatabaseBuilder(context, MemoCacheDatabase::class.java).build()
    val drafts = DraftManager(context)
    val service = BackupService(context, settings, database, drafts)

    override fun close() {
        scope.cancel()
        database.close()
        root.deleteRecursively()
        BackupCoordinator.recoveryError.value = null
    }
}
