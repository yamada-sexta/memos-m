package org.example.memosm.ui.nav

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.espresso.Espresso
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
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Point

/** Real navigation and native map gestures against a previously verified offline instance. */
class MapNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val accountId = "map-navigation-test"
    private val latitude = 41.881832
    private val longitude = -87.623177
    private val settings get() = GlobalContext.get().get<DataStoreManager>()
    private fun label(id: Int) = context.getString(id)

    @Before fun seedMap(): Unit = runBlocking {
        BackupCoordinator.awaitStartupRecovery()
        val koin = GlobalContext.get()
        koin.get<DraftManager>().clearDrafts(accountId)
        koin.get<MemoCacheRepository>().clearCache(accountId)
        koin.get<MemoCacheRepository>().cacheMemos(accountId, CacheListType.USER, listOf(
            Memo(name = "memos/own", creator = "users/1", content = "My mapped memo", state = MemoState.NORMAL,
                location = Location(placeholder = "My place", latitude = latitude, longitude = longitude), tags = listOf("place")),
            Memo(name = "memos/unlocated", creator = "users/1", content = "Unlocated memo", state = MemoState.NORMAL,
                tags = listOf("unmapped"))
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
            val searchBounds = compose.onNodeWithTag("memo_search_bar").fetchSemanticsNode().boundsInRoot
            val scopeBounds = compose.onNodeWithTag("map_scopes").fetchSemanticsNode().boundsInRoot
            assertTrue("Scope pills belong directly below search", scopeBounds.top >= searchBounds.bottom && scopeBounds.top - searchBounds.bottom < 80f)
            compose.onNodeWithTag("memo_search_bar").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("map_scopes").fetchSemanticsNodes().isEmpty() }
            val expandedBounds = compose.onNodeWithTag("memo_search_bar").fetchSemanticsNode().boundsInRoot
            val mapBounds = compose.onNodeWithTag("memo_map_canvas").fetchSemanticsNode().boundsInRoot
            assertTrue("Expanded search fills the map viewport", expandedBounds.height >= mapBounds.height * 0.85f)
            compose.onNodeWithText("#place").assertExists()
            compose.onNodeWithText("#unmapped").assertDoesNotExist()
            compose.onNodeWithText(label(R.string.map_show_results)).performScrollTo().performClick()
            compose.onNodeWithTag("map_scope_explore").performClick()
            compose.onNodeWithTag("map_fit_all").assertIsEnabled()
            compose.onNodeWithTag("map_scope_all").performClick()
            compose.onNodeWithTag("memo_search_bar").performClick()
            compose.onNode(hasSetTextAction()).performTextInput("no matching content")
            compose.onNodeWithText(label(R.string.map_show_results)).performScrollTo().performClick()
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
            awaitEditor()
            returnToMap()
        }
    }

    @Test fun tappingAPinShowsEveryMemoThereAndSavesALocatedDraft() {
        runBlocking {
            GlobalContext.get().get<MemoCacheRepository>().cacheMemos(accountId, CacheListType.USER, listOf(
                Memo(name = "memos/second", creator = "users/1", content = "Another memo at my place", state = MemoState.NORMAL,
                    location = Location(placeholder = "My place", latitude = latitude, longitude = longitude))
            ) + (1..3).map { index ->
                Memo(name = "memos/extra-$index", creator = "users/1", content = "Extra memo $index", state = MemoState.NORMAL,
                    location = Location(placeholder = "My place", latitude = latitude, longitude = longitude))
            }, replace = false)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            openMap()
            tapRenderedPin()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("map_place_panel").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val collapsedTop = compose.onNodeWithTag("map_place_panel").fetchSemanticsNode().boundsInRoot.top
            compose.onNodeWithTag("map_place_drag_handle").performTouchInput {
                swipe(start = center, end = Offset(center.x, center.y - 600f), durationMillis = 500)
            }
            compose.waitForIdle()
            val expandedTop = compose.onNodeWithTag("map_place_panel").fetchSemanticsNode().boundsInRoot.top
            assertTrue("The standard sheet handle expands the place panel", expandedTop < collapsedTop - 80f)
            compose.onNodeWithTag("map_place_memos").performScrollToNode(hasText("My mapped memo"))
            compose.onNodeWithText("My mapped memo").assertIsDisplayed()
            compose.onNodeWithTag("map_place_memos").performScrollToNode(hasText("Another memo at my place"))
            compose.onNodeWithText("Another memo at my place").assertIsDisplayed()
            compose.onAllNodes(hasContentDescription(label(R.string.memo_action_more)) and
                hasAnyAncestor(hasTestTag("map_place_memos")))[0].performScrollTo().performClick()
            compose.onNodeWithText(label(R.string.memo_action_edit)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.memo_action_pin)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.memo_action_archive)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.memo_action_delete)).assertIsDisplayed()
            Espresso.pressBack()
            compose.onNodeWithTag("map_new_here").performClick()
            awaitEditor()
            compose.onNode(hasSetTextAction()).performTextInput("A draft at this place")
            compose.waitUntil(15_000) {
                runBlocking { GlobalContext.get().get<DraftManager>().getDrafts(accountId) }
                    .any { it.content == "A draft at this place" && it.location?.latitude == latitude && it.location.longitude == longitude && it.location.placeholder == "My place" }
            }
            returnToMap()
            compose.onNodeWithTag("map_place_memos").performScrollToNode(hasText("My mapped memo"))
            compose.onNodeWithText("My mapped memo").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription(label(R.string.memo_detail_back))
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("map_place_panel")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
        }
    }

    private fun awaitEditor() {
        compose.waitUntil(20_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes(atLeastOneRootRequired = false).size == 1 }
        compose.onNodeWithText(label(R.string.map_draft_here)).assertIsDisplayed()
    }

    private fun returnToMap() {
        compose.onNodeWithContentDescription(label(R.string.memo_detail_back)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("memo_map_canvas").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
    }

    private fun tapRenderedPin() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var map: MapLibreMap? = null
        fun findMap(view: View): MapView? = when (view) {
            is MapView -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findMap(view.getChildAt(it)) }
            else -> null
        }
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single()
            findMap(activity.window.decorView)!!.getMapAsync { map = it }
        }
        var point = Offset.Zero
        compose.waitUntil(45_000) {
            var found = false
            instrumentation.runOnMainSync {
                val nativeMap = map
                val geometry = nativeMap?.queryRenderedFeatures(RectF(0f, 0f, 2000f, 3000f), "memosm-pins")
                    ?.firstOrNull { it.getNumberProperty("count").toInt() == 5 }?.geometry() as? Point
                if (nativeMap != null && geometry != null) {
                    val screen = nativeMap.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(geometry.latitude(), geometry.longitude()))
                    point = Offset(screen.x, screen.y); found = true
                }
            }
            found
        }
        compose.onNodeWithTag("memo_map_canvas").performTouchInput { click(point) }
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
