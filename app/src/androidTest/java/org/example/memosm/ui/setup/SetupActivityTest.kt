package org.example.memosm.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.backup.BackupCategory
import org.example.memosm.data.backup.BackupManifest
import org.example.memosm.model.Account
import org.example.memosm.ui.nav.SettingsSection
import org.example.memosm.ui.profile.SettingsActivity
import org.example.memosm.ui.backup.BackupTransferActivity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class SetupActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val settings get() = GlobalContext.get().get<DataStoreManager>()
    private fun label(id: Int) = context.getString(id)

    @Before
    fun resetSetup() {
        runBlocking {
            BackupCoordinator.awaitStartupRecovery()
            GlobalContext.get().get<DataStore<Preferences>>().edit {
                it.remove(DataStoreManager.SETUP_COMPLETED)
                it.remove(DataStoreManager.ACCOUNTS_JSON)
            }
        }
    }

    @Test
    fun firstLaunchReturnsFromLoginAndRetriesAfterExiting() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitWelcome()
            compose.onNodeWithContentDescription(label(R.string.common_close)).assertDoesNotExist()
            compose.onNodeWithText(label(R.string.login_button)).performClick()
            compose.onNodeWithText(label(R.string.login_title)).assertIsDisplayed()
            assertActivityInStage(SetupLoginActivity::class.java, Stage.RESUMED)
            Espresso.pressBack()
            awaitWelcome()
            assertFalse(runBlocking { settings.setupCompleted.first() })
            Espresso.pressBackUnconditionally()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitWelcome()
            Espresso.pressBackUnconditionally()
        }
    }

    @Test
    fun welcomeSurvivesRecreation() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.createIntent(context, fromSettings = true)).use { scenario ->
            awaitWelcome()
            scenario.recreate()
            awaitWelcome()
            compose.onNodeWithContentDescription(label(R.string.common_close)).assertIsDisplayed()
        }
    }

    @Test
    fun cancelingTheImportPickerReturnsToSetupWithoutCompletingIt() {
        ActivityScenario.launch<SetupActivity>(SetupActivity.createIntent(context)).use {
            awaitWelcome()
            compose.onNodeWithText(label(R.string.recovery_import)).performClick()
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            compose.waitUntil(10_000) {
                val packageName = automation.rootInActiveWindow?.packageName?.toString()
                packageName != null && packageName != context.packageName
            }
            compose.waitUntil(10_000) {
                var stopped = false
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    stopped = (ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED) +
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.PAUSED))
                        .any { activity -> activity is BackupTransferActivity }
                }
                stopped
            }
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            awaitWelcome()
            assertFalse(runBlocking { settings.setupCompleted.first() })
        }
    }

    @Test
    fun settingsReplayCanCloseLoginOrGoBackToAbout() {
        runBlocking { settings.addAccount("https://example.invalid/", "test-token") }
        val account = runBlocking { settings.getAccounts().single() }
        ActivityScenario.launch<SettingsActivity>(SettingsActivity.createIntent(context, SettingsSection.ABOUT)).use {
            compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.setup_show)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label(R.string.setup_show)).performClick()
            awaitWelcome()
            compose.onNodeWithText(label(R.string.login_button)).performClick()
            compose.onNodeWithContentDescription(label(R.string.common_close)).performClick()
            compose.onNodeWithText(label(R.string.setup_show)).assertIsDisplayed()

            compose.onNodeWithText(label(R.string.setup_show)).performClick()
            awaitWelcome()
            compose.onNodeWithText(label(R.string.login_button)).performClick()
            Espresso.pressBack()
            awaitWelcome()
            Espresso.pressBack()
            compose.onNodeWithText(label(R.string.setup_show)).assertIsDisplayed()
            assertTrue(runBlocking { settings.setupCompleted.first() })
            assertEquals(account, runBlocking { settings.getAccounts().single() })
        }
    }

    @Test
    fun existingAccountsSkipSetupAndLogoutDoesNotRetriggerIt() {
        runBlocking { settings.saveAccounts(listOf(Account(hostUrl = "https://example.invalid/", accessToken = "test-token", isActive = true))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(10_000) { runBlocking { settings.setupCompleted.first() } }
            compose.onNodeWithText(label(R.string.recovery_import)).assertDoesNotExist()
            runBlocking { settings.saveAccounts(emptyList()) }
            compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.login_title)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(label(R.string.login_title)).assertIsDisplayed()
            assertTrue(runBlocking { settings.setupCompleted.first() })
        }
    }

    @Test
    fun loginScreenSurvivesRecreationAndFailureDoesNotCompleteSetup() {
        ActivityScenario.launch<SetupLoginActivity>(SetupLoginActivity.createIntent(context, fromSettings = true)).use { scenario ->
            scenario.recreate()
            compose.onNodeWithText(label(R.string.login_title)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.login_host_url)).performTextInput("https://")
            compose.onNodeWithText(label(R.string.login_button)).performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.login_error_invalid_url)).assertIsDisplayed()
            assertFalse(runBlocking { settings.setupCompleted.first() })
        }
    }

    @Test
    fun startupWaitsForRecoveryAndSkipsSetupAfterSettingsRestoration() {
        BackupCoordinator.beginStartupRecovery()
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText(label(R.string.recovery_import)).assertDoesNotExist()
                runBlocking {
                    BackupCoordinator.restore {
                        settings.applyBackup(BackupManifest(0L, setOf(BackupCategory.SETTINGS), emptyList(), settings.backupSettings()))
                    }
                }
                BackupCoordinator.finishStartupRecovery()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.login_title)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label(R.string.setup_get_started), substring = true).assertDoesNotExist()
                assertTrue(runBlocking { settings.setupCompleted.first() })
            }
        } finally { BackupCoordinator.finishStartupRecovery() }
    }

    private fun awaitWelcome() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.recovery_import)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(label(R.string.setup_get_started)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.app_name)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.login_button)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.recovery_import)).assertIsDisplayed()
    }

    private fun assertActivityInStage(type: Class<*>, stage: Stage) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).any { type.isInstance(it) })
        }
    }
}
