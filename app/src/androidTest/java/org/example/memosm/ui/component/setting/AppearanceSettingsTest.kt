package org.example.memosm.ui.component.setting

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.AppFont
import org.example.memosm.model.ColorTheme
import org.example.memosm.model.ThemeMode
import org.example.memosm.ui.component.LocalNetworkPermission
import org.example.memosm.ui.component.LocalNetworkPermissionState
import org.example.memosm.ui.nav.SettingsPageScaffold
import org.example.memosm.ui.theme.ProfileTheme
import org.example.memosm.ui.theme.SavedMemosMTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class AppearanceSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "appearance-${UUID.randomUUID()}.preferences_pb")
    private val preferences = DataStoreManager(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))

    @After fun cleanup() { scope.cancel(); file.delete() }

    @Test fun scrollAndSingleChoiceSelectorsWorkWithTheMemoPreviewOnASmallScreen() {
        compose.setContent {
            SavedMemosMTheme(preferences) {
              CompositionLocalProvider(LocalNetworkPermission provides LocalNetworkPermissionState({ true }, {}, { false })) {
                ProfileTheme {
                    Box(Modifier.size(width = 360.dp, height = 400.dp)) {
                        SettingsPageScaffold(title = "Appearance", onBack = {}, collapsingHeader = false) { padding ->
                          AppearanceSettingsContent(headerScale = 1f, modifier = Modifier.padding(padding).testTag("appearance"), dataStore = preferences) {
                            AppSettingsCard(
                                pageSize = 10, onPageSizeChange = {}, headerScale = 1f, onHeaderScaleChange = {},
                                linkPreviewEnabled = true, onLinkPreviewEnabledChange = {}, category = AppSettingsCategory.APPEARANCE
                            )
                            Text("End of appearance settings")
                          }
                        }
                    }
                }
              }
            }
        }
        compose.onAllNodesWithText("Theme mode").assertCountEquals(1)
        repeat(2) { compose.onNodeWithTag("appearance").performTouchInput { swipeUp() } }
        compose.onNodeWithText("End of appearance settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Color theme").performScrollTo().performClick()
        compose.onNodeWithText("Memos").performScrollTo().performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { preferences.appearance.first().colorTheme == ColorTheme.MEMOS } }
        compose.onNodeWithText("Theme mode").performScrollTo().performClick()
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { preferences.appearance.first().themeMode == ThemeMode.DARK } }
        compose.onNodeWithText("Font").performScrollTo().performClick()
        compose.onNodeWithText("Serif").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(AppFont.SYSTEM, runBlocking { preferences.appearance.first().font })
        compose.onNodeWithText("Font").performClick()
        compose.onNodeWithText("Serif").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(timeoutMillis = 5_000) { runBlocking { preferences.appearance.first().font == AppFont.SERIF } }
        compose.onNodeWithText("Preview").performScrollTo()
        File(context.cacheDir, "appearance-settings-preview.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
