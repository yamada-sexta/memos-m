package org.example.memosm.ui.component

import android.graphics.Bitmap
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.R
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.ui.nav.SettingsSection
import org.example.memosm.ui.profile.SettingsActivity
import org.example.memosm.ui.theme.MemosMTheme
import org.example.memosm.viewmodel.MemosUiState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class SyncStatusPanelTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    @Test fun cacheActionsShareALargeRowAndManageCacheTargetsSettings() {
        var texts = 0
        var attachments = 0
        var manage = 0
        compose.setContent {
            MemosMTheme {
                SyncStatusPanel(
                    uiState = MemosUiState(
                        isOnline = true,
                        lastSyncTime = System.currentTimeMillis() - 5 * 60_000L
                    ),
                    onDismiss = {}, onPreDownloadText = { texts++ },
                    onPreDownloadAttachments = { attachments++ }, onDeleteOp = {},
                    onManageCache = { manage++ }
                )
            }
        }
        listOf(
            R.string.sync_panel_title, R.string.offline_sync_now,
            R.string.cache_cleanup_title, R.string.offline_settings_cache_analysis_title,
            R.string.sync_panel_predownload_idle
        ).forEach { compose.onNodeWithText(label(it)).assertDoesNotExist() }

        val attachmentButton = compose.onNodeWithText(label(R.string.offline_predownload_attachments_button))
        val textButton = compose.onNodeWithText(label(R.string.offline_predownload_text_button))
        attachmentButton.performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        textButton.assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        val left = attachmentButton.fetchSemanticsNode().boundsInRoot
        val right = textButton.fetchSemanticsNode().boundsInRoot
        assertEquals(left.top, right.top, 1f)
        assertEquals(left.height, right.height, 1f)
        assertEquals(left.width, right.width, 1f)
        assertTrue(left.right < right.left)
        attachmentButton.performClick()
        textButton.performClick()
        compose.onNodeWithText(label(R.string.sync_panel_manage_cache)).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, attachments)
            assertEquals(1, texts)
            assertEquals(1, manage)
        }

        val intent = SettingsActivity.createIntent(context, SettingsSection.OFFLINE)
        assertEquals(SettingsActivity::class.java.name, intent.component?.className)
        assertEquals(SettingsSection.OFFLINE.name, intent.getStringExtra("settings_section"))
        saveScreenshot("sync-status-panel.png")
    }

    @Test fun largeTextFitsAndCacheSettingsRemainAvailableOffline() {
        compose.setContent {
            MemosMTheme {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.6f)) {
                    SyncStatusPanel(
                        uiState = MemosUiState(
                            isOnline = false,
                            preDownloadState = PreDownloadState.Done(timestamp = 1, textCount = 500)
                        ),
                        onDismiss = {}, onPreDownloadText = {},
                        onPreDownloadAttachments = {}, onDeleteOp = {}, onManageCache = {}
                    )
                }
            }
        }
        compose.onNodeWithText(label(R.string.offline_predownload_attachments_button))
            .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.offline_predownload_text_button))
            .assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.sync_panel_manage_cache))
            .performScrollTo().assertIsDisplayed().performClick()
        saveScreenshot("sync-status-panel-large-text.png")
    }

    private fun saveScreenshot(name: String) {
        File(context.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
