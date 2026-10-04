package org.example.memosm.ui.backup

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.backup.*
import org.example.memosm.model.Account
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class BackupTransferActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun label(id: Int) = context.getString(id)
    private fun seed() = runBlocking {
        // This suite runs in the disposable backupvalidation package.
        GlobalContext.get().get<DataStoreManager>().saveAccounts(listOf(
            Account(id = "form-test", hostUrl = "https://example.invalid", accessToken = "fake-form-token", displayName = "Form account", isActive = true)))
    }

    @Test fun exportUsesFullScreenTogglesWithQueuedEditsOffByDefault() {
        seed()
        ActivityScenario.launch<BackupTransferActivity>(BackupTransferActivity.exportIntent(context)).use {
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithText("Form account").assertIsOn()
            listOf(R.string.backup_category_accounts, R.string.backup_category_settings, R.string.backup_category_cache,
                R.string.backup_category_media, R.string.backup_category_drafts).forEach { id -> compose.onNodeWithText(label(id)).assertIsOn() }
            compose.onNodeWithText(label(R.string.backup_category_queued)).performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithText(label(R.string.backup_category_queued)).performClick().assertIsOff()
            compose.onNodeWithText(label(R.string.backup_password_protection)).performScrollTo().assertIsOff().performClick()
            compose.onNodeWithText(label(R.string.backup_repeat_password)).assertExists()
            compose.onNodeWithText(label(R.string.recovery_export)).assertIsNotEnabled()
        }
    }

    @Test fun importUsesFullScreenSelectionAndDefaultsQueuedEditsOff() {
        seed()
        val service = GlobalContext.get().get<BackupService>()
        val archive = runBlocking { service.export(BackupSelection(setOf("form-test"), BackupCategory.entries.toSet())).getOrThrow() }
        try {
            ActivityScenario.launch<BackupTransferActivity>(BackupTransferActivity.importIntent(context, archive.absolutePath)).use {
                compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.backup_contents_heading)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(isDialog()).assertDoesNotExist()
                compose.onNodeWithText(label(R.string.backup_category_accounts)).assertIsOn()
                compose.onNodeWithText(label(R.string.backup_category_queued)).performScrollTo().assertIsOff()
                compose.onNodeWithText(label(R.string.recovery_import)).assertIsEnabled()
                compose.onNodeWithText(label(R.string.backup_password_protection)).assertDoesNotExist()
            }
            assertEquals("fake-form-token", runBlocking { GlobalContext.get().get<DataStoreManager>().getAccounts().single().accessToken })
        } finally { archive.delete() }
    }

    @Test fun resumedTransferActivityWaitsForStartupRecovery() {
        seed()
        BackupCoordinator.beginStartupRecovery()
        try {
            ActivityScenario.launch<BackupTransferActivity>(BackupTransferActivity.exportIntent(context)).use {
                compose.onNodeWithText(label(R.string.backup_contents_heading)).assertDoesNotExist()
                BackupCoordinator.finishStartupRecovery()
                compose.waitUntil(10_000) { compose.onAllNodesWithText(label(R.string.backup_contents_heading)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(label(R.string.backup_category_queued)).performScrollTo().assertIsOff()
            }
        } finally { BackupCoordinator.finishStartupRecovery() }
    }
}
