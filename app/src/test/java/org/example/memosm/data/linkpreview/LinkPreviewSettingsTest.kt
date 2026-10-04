package org.example.memosm.data.linkpreview

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.DataStoreManager
import org.junit.Assert.*
import org.junit.Test

class LinkPreviewSettingsTest {
    @Test
    fun `previews default on and the choice survives reopening the settings store`() = runBlocking {
        val directory = Files.createTempDirectory("link-preview-settings").toFile()
        val file = directory.resolve("settings.preferences_pb")
        suspend fun checkAndSave(expected: Boolean, newValue: Boolean) {
            val job = SupervisorJob()
            try {
                val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }
                val manager = DataStoreManager(store)
                assertEquals(expected, manager.linkPreviewEnabled.first())
                manager.saveLinkPreviewEnabled(newValue)
                assertEquals(newValue, manager.linkPreviewEnabled.first())
            } finally { job.cancelAndJoin() }
        }
        try {
            checkAndSave(true, false)
            checkAndSave(false, true)
            checkAndSave(true, true)
        } finally { directory.deleteRecursively() }
    }
}
