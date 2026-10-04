package org.example.memosm.ui.component.setting

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.ui.theme.MemosMTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

class MemoCatchTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tenthTapOpensGameAndClosingRequiresTenMoreTaps() {
        compose.setContent {
            MemosMTheme(dynamicColor = false) {
                AboutAppCard(onOpenLicenses = {}, onOpenLogs = {})
            }
        }
        repeat(9) { compose.onNodeWithText("App Version").performClick() }
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
        compose.onNodeWithText("App Version").performClick()
        compose.onNodeWithText("You found a little distraction!").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
        repeat(9) { compose.onNodeWithText("App Version").performClick() }
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
        compose.onNodeWithText("App Version").performClick()
        compose.onNodeWithText("Play").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
    }

    @Test
    fun longPressStillCopiesVersionAndDoesNotCountAsATap() {
        compose.setContent {
            MemosMTheme { AboutAppCard(onOpenLicenses = {}, onOpenLogs = {}) }
        }
        repeat(9) { compose.onNodeWithText("App Version").performClick() }
        compose.onNodeWithText("App Version").performTouchInput { longClick() }
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.runOnIdle {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
            assertEquals(version, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithText("App Version").performClick()
        compose.onNodeWithText("Play").assertIsDisplayed()
    }

    @Test
    fun playMovementPauseFinishAndReplay() {
        val dark = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MemosMTheme(darkTheme = dark.value, dynamicColor = false) {
                    if (visible.value) MemoCatchDialog(onDismiss = { visible.value = false })
                }
            }
        }
        capture("welcome-light")
        compose.onNodeWithText("Play").performClick()
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Play").assertDoesNotExist()
        compose.onNodeWithTag("memo_catch_playfield").performTouchInput {
            click(Offset(width * 0.9f, height * 0.5f))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("memo_catch_playfield").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Tray position: 88 percent")
        )
        val actions = compose.onNodeWithTag("memo_catch_playfield")
            .fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle {
            assertEquals(2, actions.size)
            actions.first().action()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("memo_catch_playfield").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Tray position: 78 percent")
        )
        compose.onNodeWithTag("memo_catch_playfield").performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.5f), Offset(width * 0.5f, height * 0.5f))
        }
        compose.mainClock.advanceTimeBy(2_000)
        capture("playing-light")
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        val pausedTime = compose.onNodeWithText("s left", substring = true)
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithText(pausedTime).assertTextEquals(pausedTime)
        compose.runOnIdle {
            dark.value = true
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Resume").assertIsDisplayed()
        capture("paused-dark")
        compose.onNodeWithText("Resume").performClick()
        compose.mainClock.advanceTimeBy(31_000)
        compose.onNodeWithText("Nice catch!").assertIsDisplayed()
        compose.onNodeWithText("0 s left").assertIsDisplayed()
        capture("finished-dark")
        compose.onNodeWithText("Play again").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Score: 0").assertIsDisplayed()
        compose.onNodeWithText("30 s left").assertIsDisplayed()
        Espresso.pressBack()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("Memo Catch").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("memo_catch_playfield").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Capture the dialog surface, including the header and overlay controls.
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(context.cacheDir, "memo-catch-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
