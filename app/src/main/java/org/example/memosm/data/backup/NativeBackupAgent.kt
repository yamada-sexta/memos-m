package org.example.memosm.data.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.MemoCacheDatabase

/** Full backup runs with a base Application and without app providers: never use Koin here. */
class NativeBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) {
        val directory = File(filesDir, DIRECTORY)
        if (File(directory, RESTORE_MARKER).exists() || File(noBackupFilesDir, "backup_restore/transaction.mmbackup").exists()) return
        directory.deleteRecursively()
        val flags = if (Build.VERSION.SDK_INT >= 28) data.transportFlags else 0
        val transfer = flags and FLAG_DEVICE_TO_DEVICE_TRANSFER != 0
        val encrypted = flags and FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED != 0
        if (!transfer && !encrypted) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            runBlocking(Dispatchers.IO) {
                val store = DataStoreManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { preferencesDataStoreFile("settings") }))
                val service = BackupService(this@NativeBackupAgent, store, MemoCacheDatabase.getInstance(this@NativeBackupAgent), DraftManager(this@NativeBackupAgent))
                val ids = store.getAccounts().map { it.id }.toSet()
                val categories = if (transfer) BackupCategory.entries.toSet() - BackupCategory.QUEUED_EDITS else setOf(BackupCategory.ACCOUNTS)
                val file = service.export(BackupSelection(ids, categories)).getOrThrow()
                try {
                    require(data.quota < 0 || file.length() + 4096 < data.quota) { "Android backup quota exceeded" }
                    directory.mkdirs()
                    check(file.renameTo(File(directory, if (transfer) DEVICE_FILE else CLOUD_FILE))) { "Could not prepare Android backup" }
                } finally { file.delete() }
            }
            super.onFullBackup(data)
        } finally {
            scope.cancel()
            // Source-device snapshots must never be mistaken for an incoming restore.
            directory.deleteRecursively()
        }
    }

    override fun onRestoreFinished() {
        val directory = File(filesDir, DIRECTORY)
        if (File(directory, CLOUD_FILE).isFile || File(directory, DEVICE_FILE).isFile) {
            File(directory, RESTORE_MARKER).outputStream().use { it.write(1); it.fd.sync() }
        }
        // The normal app applies this validated snapshot before opening sessions or scheduling workers.
    }

    companion object {
        const val DIRECTORY = "native_backup"
        const val CLOUD_FILE = "accounts.mmbackup"
        const val DEVICE_FILE = "device.mmbackup"
        const val RESTORE_MARKER = "restore_pending"
    }
}
