package org.example.memosm.ui.component.map

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MapPlace
import org.example.memosm.model.memosAtMapPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Point

/** Checks native rendered features, rather than only the Compose controls above the map. */
class NativeMemoMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun placeSheetMovesTheSelectedPinIntoTheVisibleMap() {
        val controller = MemoMapController()
        val original = memo("visible", 41.881832, -87.623177)
        val selection = mutableStateOf<MapPlace?>(null)
        val panel = mutableStateOf<Rect?>(null)
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, listOf(original), false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize().testTag("native_map"), selection = selection.value?.location, panelBounds = panel.value,
                    onPlace = { selection.value = it }, onTileError = {})
            }
        }
        awaitPins(controller, 1)
        val viewport = compose.onNodeWithTag("native_map").fetchSemanticsNode().boundsInRoot.size
        compose.runOnIdle {
            controller.map!!.cancelTransitions()
            val point = controller.map!!.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(
                original.location!!.latitude!!, original.location.longitude!!))
            panel.value = Rect(0f, point.y - 120f, viewport.width, viewport.height)
            selection.value = MapPlace(original.location)
        }
        awaitVisiblePin(controller, original) { x, y -> y < panel.value!!.top - 20f }
        compose.runOnIdle {
            val point = controller.map!!.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(
                original.location!!.latitude!!, original.location.longitude!!))
            panel.value = Rect(point.x - 120f, 0f, viewport.width, viewport.height)
        }
        awaitVisiblePin(controller, original) { x, y -> x < panel.value!!.left - 20f }
    }

    private fun awaitVisiblePin(controller: MemoMapController, memo: Memo, visible: (Float, Float) -> Boolean) {
        compose.waitUntil(15_000) {
            var found = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val point = controller.map!!.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(
                    memo.location!!.latitude!!, memo.location.longitude!!))
                found = visible(point.x, point.y)
            }
            found
        }
    }

    @Test fun tappedPinAndSelectionRingUseTheOriginalMemoCoordinates() {
        val controller = MemoMapController()
        val original = memo("precise", 41.881832, -87.623177)
        val selection = mutableStateOf<MapPlace?>(null)
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, listOf(original), false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize().testTag("native_map"), selection = selection.value?.location,
                    onPlace = { selection.value = it }, onTileError = {})
            }
        }
        awaitPins(controller, 1)
        var point = Offset.Zero
        compose.runOnIdle {
            val screen = controller.map!!.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(
                original.location!!.latitude!!, original.location.longitude!!))
            point = Offset(screen.x, screen.y)
        }
        compose.onNodeWithTag("native_map").performTouchInput { click(point) }
        compose.waitUntil(10_000) { selection.value != null }
        compose.runOnIdle {
            assertEquals(original.location!!.latitude, selection.value!!.location.latitude)
            assertEquals(original.location.longitude, selection.value!!.location.longitude)
            assertEquals(listOf(original), memosAtMapPlace(listOf(original), selection.value!!))
        }
        compose.waitUntil(15_000) {
            var aligned = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val map = controller.map!!
                val rectangle = RectF(0f, 0f, 2000f, 3000f)
                val pin = map.queryRenderedFeatures(rectangle, "memosm-pins").firstOrNull()?.geometry() as? Point
                val ring = map.queryRenderedFeatures(rectangle, "memosm-selected-pin").firstOrNull()?.geometry() as? Point
                if (pin != null && ring != null) {
                    val center = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(pin.latitude(), pin.longitude()))
                    val selected = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(ring.latitude(), ring.longitude()))
                    aligned = kotlin.math.abs(center.x - selected.x) < 1f && kotlin.math.abs(center.y - selected.y) < 1f
                }
            }
            aligned
        }
    }

    @Test fun clusterTapOpensAllItsMemosWithoutZooming() {
        val controller = MemoMapController()
        val memos = listOf(memo("first", 41.88, -87.63), memo("coincident", 41.88, -87.63),
            memo("nearby", 41.8801, -87.6301), memo("elsewhere", 42.1, -88.1))
        var selected: MapPlace? = null
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, memos, false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize().testTag("native_map"), onPlace = { selected = it }, onTileError = {})
            }
        }
        awaitPins(controller, 4)
        var point = Offset.Zero
        var zoom = 0.0
        compose.waitUntil(45_000) {
            var found = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val map = controller.map
                val cluster = map?.queryRenderedFeatures(RectF(0f, 0f, 2000f, 3000f), "memosm-pins")
                    ?.firstOrNull { it.hasProperty("cluster_id") && it.getNumberProperty("count").toInt() == 3 }
                val geometry = cluster?.geometry() as? Point
                if (map != null && geometry != null) {
                    val screen = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(geometry.latitude(), geometry.longitude()))
                    point = Offset(screen.x, screen.y); zoom = map.cameraPosition.zoom; found = true
                }
            }
            found
        }
        compose.onNodeWithTag("native_map").performTouchInput { click(point) }
        compose.waitUntil(10_000) { selected != null }
        compose.runOnIdle {
            assertEquals(setOf("memos/first", "memos/coincident", "memos/nearby"), memosAtMapPlace(memos, selected!!).map { it.name }.toSet())
            assertEquals(zoom, controller.map!!.cameraPosition.zoom, 0.01)
        }
    }

    @Test fun memoPinsRenderAndUpdateAfterTheBasemapLoads() {
        val controller = MemoMapController()
        val memos = mutableStateOf(listOf(memo("first", 41.88, -87.63)))
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, memos.value, false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize(), onPlace = {}, onTileError = {})
            }
        }
        awaitPins(controller, 1)
        compose.runOnIdle {
            memos.value = listOf(memo("first", 41.88, -87.63), memo("second", 41.89, -87.62))
        }
        awaitPins(controller, 2)
        compose.runOnIdle {
            assertEquals(2, controller.memos.size)
            assertTrue(controller.fitted)
        }
    }

    @Test fun initialCameraIncludesMemosFromLaterPages() {
        val controller = MemoMapController()
        val memos = mutableStateOf(listOf(memo("cached", 41.88, -87.63)))
        val complete = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, memos.value, false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize(), initialFitReady = complete.value,
                    onPlace = {}, onTileError = {})
            }
        }
        compose.waitUntil(45_000) {
            var loaded = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                loaded = controller.map?.style?.getLayer("memosm-pins") != null
            }
            loaded
        }
        compose.runOnIdle {
            assertTrue("A partial history must not lock the camera to its first page", !controller.fitted)
            memos.value = memos.value + memo("later", 35.68, 139.69)
            complete.value = true
        }
        awaitPins(controller, 2)
        compose.runOnIdle { assertTrue(controller.fitted) }
    }

    @Test fun missingCountGlyphsDoNotHideMemoPins() {
        val controller = MemoMapController()
        val memos = mutableStateOf(listOf(memo("first", 41.88, -87.63)))
        compose.setContent {
            MaterialTheme {
                NativeMemoMap(controller, memos.value, false, MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxSize(), onPlace = {}, onTileError = {})
            }
        }
        awaitPins(controller, 1)
        compose.runOnIdle {
            controller.map!!.style!!.getLayerAs<SymbolLayer>("memosm-counts")!!
                .setProperties(textFont(arrayOf("Unavailable memo count font")))
            memos.value = memos.value + memo("second", 41.89, -87.62)
        }
        awaitPins(controller, 2)
    }

    private fun awaitPins(controller: MemoMapController, count: Int) {
        var diagnostic = ""
        try {
            compose.waitUntil(45_000) {
                var actual = 0
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val map = controller.map
                    actual = map?.queryRenderedFeatures(RectF(0f, 0f, 2000f, 3000f), "memosm-pins")
                        ?.distinctBy { it.geometry()?.toJson() }
                        ?.sumOf { it.getNumberProperty("count")?.toInt() ?: 0 } ?: 0
                    diagnostic = "camera=${map?.cameraPosition}, fitted=${controller.fitted}, " +
                        "memos=${controller.memos.size}, source=" +
                        map?.style?.getSourceAs<GeoJsonSource>("memosm-locations")?.querySourceFeatures(null)
                            ?.map { it.toJson() }
                }
                actual >= count
            }
        } catch (error: Exception) {
            throw AssertionError("Expected $count rendered memos; $diagnostic", error)
        }
    }

    private fun memo(name: String, latitude: Double, longitude: Double) = Memo(
        name = "memos/$name", content = name, location = Location(latitude = latitude, longitude = longitude))
}
