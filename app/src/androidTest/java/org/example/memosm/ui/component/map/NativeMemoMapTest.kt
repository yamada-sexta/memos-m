package org.example.memosm.ui.component.map

import android.graphics.RectF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
