package org.example.memosm.ui.component.setting

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.ui.component.ProfileBackButton
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.ui.theme.ProfileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class OfflineSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cacheLimitRequiresSaveAndSwitchRowChangesState() {
        val limit = mutableStateOf(100)
        val download = mutableStateOf(true)
        compose.setContent {
            MemosMTheme(darkTheme = true) {
                ProfileTheme {
                    Scaffold(topBar = {
                        LargeTopAppBar(
                            title = { Text("Offline Cache") },
                            navigationIcon = { ProfileBackButton(onClick = {}) }
                        )
                    }) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
                            OfflineSettingsCard(
                                preDownloadText = download.value, onPreDownloadTextChange = { download.value = it },
                                preDownloadAttachments = true, onPreDownloadAttachmentsChange = {},
                                preDownloadWifiOnly = true, onPreDownloadWifiOnlyChange = {},
                                preDownloadExplore = false, onPreDownloadExploreChange = {},
                                textCacheMaxMb = limit.value, onTextCacheMaxMbChange = { limit.value = it },
                                attachmentCacheMaxMb = 250, onAttachmentCacheMaxMbChange = {},
                                themeCacheMaxMb = 200, onThemeCacheMaxMbChange = {},
                                textCacheCount = 12, attachmentCacheUsage = AttachmentCacheManager.Usage(bytes = 25L * 1024 * 1024, count = 3),
                                onClearTextCache = {}, onClearAttachmentCache = {}
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "offline-settings-preview.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Cache memo text").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle { assertEquals(false, download.value) }
        compose.onNodeWithText("Text cache limit").performScrollTo().performClick()
        compose.onNodeWithText("100").performTextReplacement("512")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(100, limit.value) }
        compose.onNodeWithText("Text cache limit").performClick()
        compose.onNodeWithText("100").performTextReplacement("512")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(512, limit.value) }
    }
}
