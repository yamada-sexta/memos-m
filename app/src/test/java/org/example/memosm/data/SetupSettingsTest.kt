package org.example.memosm.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.backup.BackupCategory
import org.example.memosm.data.backup.BackupManifest
import org.junit.Assert.*
import org.junit.Test

class SetupSettingsTest {
    @Test
    fun `fresh install requires setup and completion survives reopening`() = runBlocking {
        val directory = Files.createTempDirectory("setup-settings").toFile()
        val file = directory.resolve("settings.preferences_pb")
        suspend fun open(check: suspend (DataStoreManager) -> Unit) {
            val job = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
                check(DataStoreManager(store))
            } finally { job.cancelAndJoin() }
        }
        try {
            open {
                assertFalse(it.setupCompleted.first())
                it.completeSetup()
            }
            open { assertTrue(it.setupCompleted.first()) }
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `adding an account completes setup and removing accounts does not reset it`() = withSettings { settings ->
        settings.addAccount("https://example.invalid/", "test-token")
        assertTrue(settings.setupCompleted.first())
        assertEquals(1, settings.getAccounts().size)
        settings.saveAccounts(emptyList())
        assertTrue(settings.setupCompleted.first())
        assertFalse(settings.backupSettings().has(DataStoreManager.SETUP_COMPLETED.name))
    }

    @Test
    fun `restoring only settings completes setup without requiring an account`() = withSettings { settings ->
        val manifest = BackupManifest(
            createdAt = 0L,
            categories = setOf(BackupCategory.SETTINGS),
            accounts = emptyList(),
            settings = settings.backupSettings()
        )
        settings.applyBackup(manifest)
        assertTrue(settings.setupCompleted.first())
        assertTrue(settings.getAccounts().isEmpty())
        assertFalse(settings.backupSettings().has(DataStoreManager.SETUP_COMPLETED.name))
    }

    private fun withSettings(check: suspend (DataStoreManager) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("setup-settings").toFile()
        val job = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) {
                directory.resolve("settings.preferences_pb")
            }
            check(DataStoreManager(store))
        } finally {
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
