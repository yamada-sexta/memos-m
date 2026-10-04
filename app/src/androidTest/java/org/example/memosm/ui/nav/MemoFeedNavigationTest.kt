package org.example.memosm.ui.nav

import android.graphics.Bitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Account
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.Visibility
import org.example.memosm.widget.DraftWidget
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.File

/** Uses real navigation, cached feeds, search, and draft storage without a live server. */
class MemoFeedNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val accountId = "memo-feed-navigation-test"
    private fun label(id: Int) = context.getString(id)

    @Before
    fun seedCachedFeeds(): Unit = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        val koin = GlobalContext.get()
        koin.get<DraftManager>().clearDrafts(accountId)
        val cache = koin.get<MemoCacheRepository>()
        cache.cacheMemos(accountId, CacheListType.USER, List(24) { index ->
            Memo(name = "memos/mine-$index", creator = "users/1", content = "My memo $index",
                state = MemoState.NORMAL, visibility = Visibility.PRIVATE)
        })
        cache.cacheMemos(accountId, CacheListType.EXPLORE, List(24) { index ->
            Memo(name = "memos/explore-$index", creator = "users/2", content = "Explore memo $index",
                state = MemoState.NORMAL, visibility = Visibility.PUBLIC)
        })
        koin.get<DataStoreManager>().apply {
            completeSetup()
            saveAccounts(listOf(Account(
                id = accountId, hostUrl = "https://example.invalid", accessToken = "test-token",
                name = "feed-test", displayName = "Feed test", isActive = true,
                user = UserSnapshot(name = "users/1", username = "feed-test", displayName = "Feed test")
            )))
        }
    }

    @Test
    fun tapAndSwipeSwitchFeedsAndComposerIsAvailableOnBoth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithText("My memo 0").assertIsDisplayed()
            // Explore appears only in the top header, never as a separate navigation item.
            compose.onAllNodesWithText(label(R.string.nav_explore)).assertCountEquals(1)
            openAndCloseComposer()
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 0").assertIsDisplayed()
            screenshot("memo-feed-explore.png")
            openAndCloseComposer()
            compose.onNodeWithTag("memo_feed_pager").performTouchInput { swipeRight() }
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithTag("memo_feed_pager").performTouchInput { swipeLeft() }
            awaitFeed(MemoFeed.EXPLORE)
        }
    }

    @Test
    fun scrollPositionsAndSelectedFeedSurviveNavigationAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitFeed(MemoFeed.MEMOS)
            list(MemoFeed.MEMOS).performScrollToIndex(12)
            compose.onNodeWithText("My memo 12").assertIsDisplayed()
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            list(MemoFeed.EXPLORE).performScrollToIndex(8)
            compose.onNodeWithText("Explore memo 8").assertIsDisplayed()
            tab(MemoFeed.MEMOS).performClick()
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithText("My memo 12").assertIsDisplayed()
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 8").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.nav_attachments)).performClick()
            compose.onNodeWithText(label(R.string.nav_memos)).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 8").assertIsDisplayed()
            scenario.recreate()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 8").assertIsDisplayed()
            tab(MemoFeed.MEMOS).performClick()
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithText("My memo 12").assertIsDisplayed()
        }
    }

    @Test
    fun scrollingHidesTheHeaderAndSearchAndScrollingUpRestoresThem() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitFeed(MemoFeed.MEMOS)
            list(MemoFeed.MEMOS).performTouchInput {
                swipeUp(startY = height * 0.7f, endY = height * 0.3f)
            }
            compose.onNodeWithTag("memo_feed_tabs").assertDoesNotExist()
            compose.onNodeWithTag("memo_search_bar").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.nav_attachments)).assertDoesNotExist()
            list(MemoFeed.MEMOS).performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.7f)
            }
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithTag("memo_search_bar").assertIsDisplayed()
            compose.onNodeWithText(label(R.string.nav_attachments)).assertIsDisplayed()
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            list(MemoFeed.EXPLORE).performTouchInput {
                swipeUp(startY = height * 0.7f, endY = height * 0.3f)
            }
            compose.onNodeWithTag("memo_feed_tabs").assertDoesNotExist()
            list(MemoFeed.EXPLORE).performTouchInput {
                swipeDown(startY = height * 0.3f, endY = height * 0.7f)
            }
            awaitFeed(MemoFeed.EXPLORE)
        }
    }

    @Test
    fun exploreSearchExcludesPrivateMemosAndBackRestoresTabs() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitFeed(MemoFeed.MEMOS)
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText(label(R.string.memo_search_explore_placeholder)).performClick()
            compose.onNodeWithTag("memo_feed_tabs").assertDoesNotExist()
            val searchBounds = compose.onNodeWithTag("memo_search_bar").getUnclippedBoundsInRoot()
            val feedBounds = compose.onNodeWithTag("memo_feed_pager").getUnclippedBoundsInRoot()
            assertEquals(feedBounds.left.value, searchBounds.left.value, 1f)
            assertEquals(feedBounds.right.value, searchBounds.right.value, 1f)
            assertEquals(feedBounds.top.value, searchBounds.top.value, 1f)
            assertEquals(feedBounds.bottom.value, searchBounds.bottom.value, 1f)
            screenshot("memo-feed-search-expanded.png")
            compose.onNode(hasSetTextAction()).performTextInput("memo 0")
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("Explore memo 0").fetchSemanticsNodes().size >= 2
            }
            compose.onNodeWithText("My memo 0").assertDoesNotExist()
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            tab(MemoFeed.MEMOS).performClick()
            awaitFeed(MemoFeed.MEMOS)
            compose.onNodeWithText(label(R.string.memo_search_placeholder)).assertIsDisplayed()
        }
    }

    @Test
    fun detailBackReturnsToTheSelectedFeed() {
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitFeed(MemoFeed.MEMOS)
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 0").performClick()
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).assertIsDisplayed()
            compose.onNodeWithTag("memo_feed_tabs").assertDoesNotExist()
            Espresso.pressBack()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithText("Explore memo 0").assertIsDisplayed()
        }
    }

    @Test
    fun draftsCanBeContinuedFromExploreAndWidgetSelectsMemos() {
        runBlocking {
            GlobalContext.get().get<DraftManager>().saveDraft(accountId, Draft(content = "Unfinished feed draft"))
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitFeed(MemoFeed.MEMOS)
            tab(MemoFeed.EXPLORE).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            compose.onNodeWithTag("memo_feed_compose").performClick()
            compose.onNodeWithText(label(R.string.drafts_prompt_continue)).performClick()
            compose.onNode(hasSetTextAction() and hasText("Unfinished feed draft")).assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitFeed(MemoFeed.EXPLORE)
            scenario.onActivity { activity ->
                val launchIntent = activity.intent
                instrumentation.callActivityOnNewIntent(activity, android.content.Intent(context, MainActivity::class.java).apply {
                    action = DraftWidget.ACTION_OPEN_COMPOSER
                })
                // ActivityScenario matches lifecycle events against its original launch intent.
                activity.intent = launchIntent
            }
            compose.onNodeWithText(label(R.string.drafts_prompt_start_fresh)).performClick()
            compose.onNode(hasSetTextAction() and hasText("Unfinished feed draft")).assertDoesNotExist()
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            awaitFeed(MemoFeed.MEMOS)
        }
    }

    private fun tab(feed: MemoFeed) = compose.onNode(
        hasText(label(feed.labelRes)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnyAncestor(hasTestTag("memo_feed_tabs"))
    )

    private fun list(feed: MemoFeed) = compose.onNode(
        hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("memo_feed_${feed.name.lowercase()}"))
    )

    private fun awaitFeed(feed: MemoFeed) {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(label(feed.labelRes)) and
                SemanticsMatcher.expectValue(SemanticsProperties.Selected, true) and
                hasAnyAncestor(hasTestTag("memo_feed_tabs"))).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText(
                    if (feed == MemoFeed.MEMOS) "My memo " else "Explore memo ", substring = true
                ) and hasAnyAncestor(hasTestTag("memo_feed_${feed.name.lowercase()}")))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun openAndCloseComposer() {
        compose.onNodeWithTag("memo_feed_compose").performClick()
        compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
        compose.onNodeWithTag("memo_feed_tabs").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        File(context.cacheDir, name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
