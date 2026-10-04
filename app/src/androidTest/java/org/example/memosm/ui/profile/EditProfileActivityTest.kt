package org.example.memosm.ui.profile

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.model.Account
import org.example.memosm.model.UserSnapshot
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class EditProfileActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun label(id: Int) = context.getString(id)

    @Before
    fun seedAccount() = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        val settings = GlobalContext.get().get<DataStoreManager>()
        settings.completeSetup()
        settings.saveAccounts(listOf(Account(
            id = "edit-profile-test",
            hostUrl = "https://example.invalid",
            accessToken = "test-token",
            name = "profile-test",
            displayName = "Profile test",
            email = "profile@example.invalid",
            isActive = true,
            user = UserSnapshot(name = "users/1", username = "profile-test", displayName = "Profile test")
        )))
    }

    @Test
    fun fullScreenFormRetainsDraftOnRecreationAndCanScrollToPassword() {
        ActivityScenario.launch(EditProfileActivity::class.java).use { scenario ->
            awaitEditor()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNode(hasText(label(R.string.profile_display_name)) and hasSetTextAction())
                .performTextReplacement("Unsaved display name")
            scenario.recreate()
            awaitEditor()
            compose.onNode(hasText(label(R.string.profile_display_name)) and hasSetTextAction())
                .assertTextContains("Unsaved display name")
            compose.onNodeWithText(label(R.string.profile_new_password)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(label(R.string.common_save)).assertIsDisplayed()
        }
    }

    @Test
    fun toolbarBackReturnsToProfile() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openEditor()
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitResumed(MainActivity::class.java)
            compose.onNodeWithContentDescription(label(R.string.profile_edit_account)).assertIsDisplayed()
        }
    }

    /** Run on an emulator with gesture navigation enabled. */
    @Test
    fun canceledBackGestureKeepsEditorAndCompletedGestureReturnsToProfile() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openEditor()
            backGesture(cancel = true)
            awaitResumed(EditProfileActivity::class.java)
            compose.onNodeWithText(label(R.string.profile_edit_account)).assertIsDisplayed()
            backGesture(cancel = false)
            awaitResumed(MainActivity::class.java)
            compose.onNodeWithContentDescription(label(R.string.profile_edit_account)).assertIsDisplayed()
        }
    }

    private fun openEditor() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(label(R.string.nav_profile)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(label(R.string.nav_profile)).performClick()
        compose.onNodeWithContentDescription(label(R.string.profile_edit_account)).performClick()
        awaitEditor()
    }

    private fun awaitEditor() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(label(R.string.profile_edit_account)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitResumed(type: Class<*>) {
        compose.waitUntil(10_000) {
            var resumed = false
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any(type::isInstance)
            }
            resumed
        }
    }

    private fun backGesture(cancel: Boolean) {
        val metrics = context.resources.displayMetrics
        val downTime = SystemClock.uptimeMillis()
        val y = metrics.heightPixels * 0.5f
        fun inject(action: Int, x: Float) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        inject(MotionEvent.ACTION_DOWN, 1f)
        for (step in 1..20) {
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_MOVE, metrics.widthPixels * 0.45f * step / 20)
        }
        inject(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, metrics.widthPixels * 0.45f)
    }
}
