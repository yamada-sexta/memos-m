package org.example.memosm.ui.component.composer

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.model.Draft
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.Visibility
import org.example.memosm.ui.profile.DraftsActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

class MemoEditorActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val accountId = "editor-activity-test"
    private val draftId = "editor-test-draft"
    private fun label(id: Int) = context.getString(id)
    private fun input() = compose.onNode(hasSetTextAction())
    private fun drafts() = runBlocking { GlobalContext.get().get<DraftManager>().getDrafts(accountId) }
    private fun intent(request: EditorRequest) = runBlocking { MemoEditorActivity.createIntent(context, request) }
    private fun request(draft: Draft? = null) = EditorRequest(accountId,
        titleRes = R.string.memo_composer_fab_new_memo, draft = draft, draftId = draft?.id ?: draftId)

    @Before
    fun seedAccount() = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        val koin = GlobalContext.get()
        koin.get<DraftManager>().clearDrafts(accountId)
        koin.get<MemoCacheRepository>().cacheMemos(accountId, CacheListType.USER, listOf(
            Memo(name = "memos/editor-test", creator = "users/1", content = "Cached editor memo",
                state = MemoState.NORMAL)
        ))
        koin.get<DataStoreManager>().apply {
            completeSetup()
            saveAccounts(listOf(Account(id = accountId, hostUrl = "https://example.invalid",
                accessToken = "test-token", name = "editor-test", isActive = true,
                user = UserSnapshot(name = "users/1", username = "editor-test", displayName = "Editor test"))))
        }
    }

    @Test
    fun autosavePreservesSelectionAndSubsequentTypingInTheMiddle() {
        ActivityScenario.launch<MemoEditorActivity>(intent(request(Draft(id = draftId, content = "alpha omega")))).use {
            awaitInput()
            input().performTextInputSelection(TextRange(6))
            input().performTextInput("new ")
            compose.waitUntil(10_000) { drafts().any { it.content == "alpha new omega" } }
            input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(10)))
            input().performTextInput("next ")
            compose.waitUntil(10_000) { drafts().any { it.content == "alpha new next omega" } }
            input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(15)))
            input().performTextInputSelection(TextRange(2, 5))
            compose.waitForIdle()
            SystemClock.sleep(700)
            input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        }
    }

    @Test
    fun markdownContinuationKeepsCursorAfterInsertedPrefix() {
        ActivityScenario.launch<MemoEditorActivity>(intent(request(Draft(id = draftId, content = "- First")))).use {
            awaitInput()
            input().performTextInput("\n")
            input().performTextInput("Second")
            compose.waitUntil(10_000) { drafts().any { it.content == "- First\n- Second" } }
            input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(16)))
        }
    }

    @Test
    fun largeAttachmentStaysOutOfIntentAndRestoresFromSessionStorage() {
        val attachment = Attachment(filename = "large.txt", type = "text/plain", content = "a".repeat(1_200_000))
        val launch = intent(request(Draft(id = draftId, content = "Large attachment", attachments = listOf(attachment))))
        assertEquals(setOf("editor_session", "editor_account"), launch.extras!!.keySet())
        val snapshot = runBlocking { EditorSessionStore.read(launch.getStringExtra("editor_session")!!).snapshot }
        assertEquals(attachment.content, snapshot.attachments.single().attachment!!.content)
        ActivityScenario.launch<MemoEditorActivity>(launch).use {
            awaitInput()
            input().assertTextEquals("Large attachment")
        }
    }

    @Test
    fun recreationAndStoredSnapshotRetainTextSelectionAndMetadata() {
        val attachment = Attachment(filename = "note.txt", type = "text/plain", content = "aGVsbG8=")
        val draft = Draft(id = draftId, content = "Original", visibility = Visibility.PUBLIC,
            location = Location(placeholder = "Test place", latitude = 41.0, longitude = -87.0),
            attachments = listOf(attachment))
        val launch = intent(request(draft))
        ActivityScenario.launch<MemoEditorActivity>(launch).use { scenario ->
            awaitInput()
            input().performTextReplacement("Changed text")
            input().performTextInputSelection(TextRange(3))
            scenario.recreate()
            awaitInput()
            input().assertTextEquals("Changed text")
            input().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            scenario.moveToState(Lifecycle.State.CREATED)
            val snapshot = runBlocking {
                val id = launch.getStringExtra("editor_session")!!
                EditorSessionStore.awaitWrites(id)
                EditorSessionStore.read(id).snapshot
            }
            assertEquals("Changed text", snapshot.text)
            assertEquals(3, snapshot.selectionStart)
            assertEquals(Visibility.PUBLIC, snapshot.visibility)
            assertEquals(draft.location, snapshot.location)
            assertEquals(attachment, snapshot.attachments.single().attachment)
            // A fresh state holder follows the same path as process recreation.
            val restored = ComposerState(snapshot)
            assertEquals(TextRange(3), restored.content.value.selection)
        }
    }

    @Test
    fun toolbarBackFlushesTypingBeforeDebounce() {
        val launch = intent(request())
        ActivityScenario.launch<MemoEditorActivity>(launch).use { scenario ->
            awaitInput()
            input().performTextInput("Saved immediately on Back")
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            runBlocking { EditorSessionStore.awaitWrites(launch.getStringExtra("editor_session")) }
            assertEquals("Saved immediately on Back", drafts().single().content)
        }
    }

    @Test
    fun offlinePublishClosesEditorAndDoesNotResurrectDraft() {
        val launch = intent(request(Draft(id = draftId, content = "Publish offline")))
        ActivityScenario.launch<MemoEditorActivity>(launch).use { scenario ->
            awaitInput()
            compose.onNodeWithText(label(R.string.memo_publish)).performClick()
            compose.waitUntil(30_000) { scenario.state == Lifecycle.State.DESTROYED }
            runBlocking { EditorSessionStore.awaitWrites(launch.getStringExtra("editor_session")) }
            SystemClock.sleep(700)
            assertTrue(drafts().none { it.id == draftId })
            val cached = runBlocking { GlobalContext.get().get<MemoCacheRepository>()
                .getCachedMemos(accountId, CacheListType.USER) }
            assertTrue(cached.any { it.content == "Publish offline" })
        }
    }

    @Test
    fun draftsAndEditorAreActivitiesAndBackReturnsThroughBoth() {
        runBlocking { GlobalContext.get().get<DraftManager>().saveDraft(accountId,
            Draft(id = draftId, content = "Draft activity navigation")) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(20_000) {
                compose.onAllNodesWithText(label(R.string.drafts_card_message)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(label(R.string.drafts_card_message)).performClick()
            awaitResumed(DraftsActivity::class.java)
            compose.onNodeWithText("Draft activity navigation").performClick()
            awaitInput()
            awaitResumed(MemoEditorActivity::class.java)
            input().performTextReplacement("Changed in separate activity")
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitResumed(DraftsActivity::class.java)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Changed in separate activity").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitResumed(MainActivity::class.java)
            compose.onNodeWithTag("memo_feed_tabs").assertIsDisplayed()
        }
    }

    @Test
    fun accountChangeClosesEditorAndKeepsDraftBoundToOriginalAccount() {
        val launch = intent(request())
        ActivityScenario.launch<MemoEditorActivity>(launch).use { scenario ->
            awaitInput()
            input().performTextInput("Account specific text")
            runBlocking { GlobalContext.get().get<DataStoreManager>().saveAccounts(listOf(
                Account(id = "other-editor-account", hostUrl = "https://example.invalid", accessToken = "other",
                    name = "other", isActive = true)
            )) }
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
            runBlocking { EditorSessionStore.awaitWrites(launch.getStringExtra("editor_session")) }
            assertEquals("Account specific text", drafts().single().content)
            assertTrue(runBlocking { GlobalContext.get().get<DraftManager>().getDrafts("other-editor-account") }.isEmpty())
        }
    }

    @Test
    fun canceledPredictiveGestureKeepsEditorAndCompletedGestureReturnsToDrafts() {
        assumeTrue(Build.VERSION.SDK_INT >= 35)
        assumeTrue(Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0) == 2)
        runBlocking { GlobalContext.get().get<DraftManager>().saveDraft(accountId,
            Draft(id = draftId, content = "Predictive draft")) }
        ActivityScenario.launch(DraftsActivity::class.java).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithText("Predictive draft").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Predictive draft").performClick()
            awaitInput()
            Espresso.closeSoftKeyboard()
            backGesture(cancel = true)
            awaitResumed(MemoEditorActivity::class.java)
            input().assertTextEquals("Predictive draft")
            backGesture(cancel = false)
            awaitResumed(DraftsActivity::class.java)
        }
    }

    private fun awaitInput() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
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
        compose.waitForIdle()
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
