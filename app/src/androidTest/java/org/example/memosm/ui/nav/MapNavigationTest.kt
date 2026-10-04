package org.example.memosm.ui.nav

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.example.memosm.MainActivity
import org.example.memosm.R
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.BackupCoordinator
import org.example.memosm.data.cache.CacheListType
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Real navigation and native map gestures against a previously verified offline instance. */
class MapNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountId = "map-navigation-test"
    private val settings get() = GlobalContext.get().get<DataStoreManager>()
    private fun label(id: Int) = context.getString(id)

    @Before fun seedMap(): Unit = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        val koin = GlobalContext.get()
        koin.get<DraftManager>().clearDrafts(accountId)
        koin.get<MemoCacheRepository>().clearCache(accountId)
        koin.get<MemoCacheRepository>().cacheMemos(accountId, CacheListType.USER, listOf(
            Memo(name = "memos/own", creator = "users/1", content = "My mapped memo", state = MemoState.NORMAL,
                location = Location(placeholder = "My place", latitude = 0.0, longitude = 0.0), tags = listOf("place"))
        ))
        koin.get<MemoCacheRepository>().cacheMemos(accountId, CacheListType.EXPLORE, listOf(
            Memo(name = "memos/public", creator = "users/2", content = "Public mapped memo", state = MemoState.NORMAL,
                visibility = Visibility.PUBLIC, location = Location(latitude = 40.0, longitude = 10.0))
        ))
        settings.saveSnapshotJson("map_support", accountId,
            GsonProvider.gson.toJson(MapSupport("https://example.invalid", null, MapCapability.SUPPORTED)))
        settings.completeSetup()
        settings.saveAccounts(listOf(Account(id = accountId, hostUrl = "https://example.invalid", accessToken = "token",
            isActive = true, user = UserSnapshot(name = "users/1", username = "map-test"))))
    }

    @Test fun unknownInstanceHasNoMapDestination() {
        runBlocking { settings.removeSnapshot("map_support", accountId) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitMemos()
            compose.onNodeWithText(label(R.string.nav_map)).assertDoesNotExist()
        }
    }

    @Test fun explicitlyUnsupportedInstanceHasNoMapDestination() {
        runBlocking { settings.saveSnapshotJson("map_support", accountId,
            GsonProvider.gson.toJson(MapSupport("https://example.invalid", null, MapCapability.UNSUPPORTED))) }
        ActivityScenario.launch(MainActivity::class.java).use {
            awaitMemos()
            compose.onNodeWithText(label(R.string.nav_map)).assertDoesNotExist()
        }
    }

    @Test fun verifiedOfflineMapSupportsScopesFiltersAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openMap()
            compose.onNodeWithTag("map_fit_all").assertIsEnabled()
            compose.onNodeWithText(label(R.string.nav_explore)).performClick()
            compose.onNodeWithTag("map_fit_all").assertIsEnabled()
            compose.onNodeWithText(label(R.string.map_filters)).performClick()
            compose.onNodeWithText(label(R.string.map_search)).performTextInput("no matching content")
            compose.onNodeWithTag("map_fit_all").assertIsNotEnabled()
            scenario.recreate()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("memo_map").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("memo_map_canvas").assertIsDisplayed()
        }
    }

    @Test fun longPressOpensPlaceAndStartsNativeComposer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openMap()
            compose.waitForIdle()
            compose.onNodeWithTag("memo_map_canvas").performTouchInput { longClick(center) }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("map_place_panel").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("map_new_here").performClick()
            compose.onNodeWithTag("map_composer").assertIsDisplayed()
            compose.onNodeWithTag("memo_map_canvas").assertExists()
        }
    }

    private fun awaitMemos() {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("memo_feed_memos").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openMap() {
        awaitMemos()
        compose.onNodeWithText(label(R.string.nav_map)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("memo_map_canvas").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag("map_fit_all") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
}
