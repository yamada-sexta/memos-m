package org.example.memosm.ui.component.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver as StateSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import okhttp3.OkHttpClient
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.example.memosm.model.MapPlace
import org.example.memosm.model.hasValidCoordinates
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val MEMO_SOURCE = "memosm-locations"
private const val COUNT_SOURCE = "memosm-location-counts"
private const val PIN_LAYER = "memosm-pins"
private const val COUNT_LAYER = "memosm-counts"
private const val SELECTION_SOURCE = "memosm-selection"

/** No account client, cookies or authorization headers ever reach the tile provider. */
private val tileClient by lazy { OkHttpClient.Builder().build() }

internal fun mapFeatures(memos: List<Memo>): FeatureCollection = FeatureCollection.fromFeatures(
    memos.filter { it.location.hasValidCoordinates() }.groupBy {
        it.location!!.latitude to it.location.longitude
    }.map { (coordinates, group) ->
        Feature.fromGeometry(Point.fromLngLat(coordinates.second!!, coordinates.first!!)).apply {
            addNumberProperty("count", group.size)
        }
    }
)

internal class MemoMapController {
    companion object {
        val Saver = StateSaver<MemoMapController, List<Double>>(
            save = { controller ->
                controller.savedCamera?.let { camera -> camera.target?.let {
                    listOf(it.latitude, it.longitude, camera.zoom, if (controller.fitted) 1.0 else 0.0)
                } }
                    ?: emptyList()
            },
            restore = { values -> MemoMapController().apply {
                if (values.size >= 3) {
                    savedCamera = CameraPosition.Builder().target(LatLng(values[0], values[1])).zoom(values[2]).build()
                    fitted = values.getOrNull(3)?.let { it == 1.0 } ?: true
                }
            } }
        )
    }
    var map: MapLibreMap? = null
    var memos: List<Memo> = emptyList()
    var savedCamera: CameraPosition? = null
    var fitted = false

    fun fitAll() {
        val map = map ?: return
        val points = memos.filter { it.location.hasValidCoordinates() }.map {
            LatLng(it.location!!.latitude!!, it.location.longitude!!)
        }.distinct()
        if (points.isEmpty()) return
        if (points.size == 1) map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 14.0))
        else map.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 64))
        fitted = true
    }
}

/** Native MapView is owned by this composition and forwards the complete Android lifecycle. */
@Composable
internal fun NativeMemoMap(
    controller: MemoMapController,
    memos: List<Memo>,
    dark: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    retry: Int = 0,
    initialFitReady: Boolean = true,
    selection: Location? = null,
    panelSize: IntSize = IntSize.Zero,
    desktop: Boolean = false,
    onPlace: (MapPlace) -> Unit,
    onTileError: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val placeCallback by rememberUpdatedState(onPlace)
    val errorCallback by rememberUpdatedState(onTileError)
    val currentMemos by rememberUpdatedState(memos)
    val currentColor by rememberUpdatedState(color)
    val currentSelection by rememberUpdatedState(selection)
    var ready by remember { mutableStateOf(false) }
    var loadedStyle by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val styleGeneration = remember { longArrayOf(0L) }
    var fallback by remember(retry, dark) { mutableStateOf(false) }
    val view = remember(controller) {
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(tileClient)
        MapView(context, MapLibreMapOptions().textureMode(true).logoEnabled(false)
            .attributionGravity(Gravity.BOTTOM or Gravity.END).attributionMargins(intArrayOf(0, 0, 12, 12))
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false).maxZoomPreference(19.0)).apply { onCreate(Bundle()) }
    }
    DisposableEffect(view, lifecycle) {
        var disposed = false
        var started = false
        var resumed = false
        fun syncLifecycle() {
            val nextStarted = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val nextResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed && !nextResumed) { view.onPause(); resumed = false }
            if (started && !nextStarted) { view.onStop(); started = false }
            if (!started && nextStarted) { view.onStart(); started = true }
            if (!resumed && nextResumed) { view.onResume(); resumed = true }
        }
        val observer = LifecycleEventObserver { _, _ -> syncLifecycle() }
        lifecycle.addObserver(observer)
        syncLifecycle()
        val memoryCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) { view.onLowMemory() }
            override fun onLowMemory() { view.onLowMemory() }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.applicationContext.registerComponentCallbacks(memoryCallback)
        val layoutListener = android.view.View.OnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            viewportSize = IntSize(right - left, bottom - top)
        }
        view.addOnLayoutChangeListener(layoutListener)
        view.getMapAsync { map ->
            if (disposed) return@getMapAsync
            controller.map = map
            map.cameraPosition = controller.savedCamera ?: CameraPosition.Builder().target(LatLng(0.0, 0.0)).zoom(1.0).build()
            map.addOnCameraIdleListener { controller.savedCamera = map.cameraPosition }
            map.addOnMapClickListener { location ->
                val features = map.queryRenderedFeatures(map.projection.toScreenLocation(location), PIN_LAYER)
                val feature = features.firstOrNull()
                when {
                    feature == null -> placeCallback(MapPlace(Location(latitude = location.latitude, longitude = location.longitude)))
                    feature.hasProperty("cluster_id") -> {
                        val source = map.style?.getSourceAs<GeoJsonSource>(MEMO_SOURCE)
                        val point = feature.geometry() as? Point
                        if (point != null && source != null) {
                            val leaves = source.getClusterLeaves(feature, currentMemos.size.toLong(), 0)
                                .features().orEmpty().mapNotNull { leaf -> (leaf.geometry() as? Point)?.let {
                                    Location(latitude = it.latitude(), longitude = it.longitude())
                                } }
                            placeCallback(MapPlace(Location(latitude = point.latitude(), longitude = point.longitude()), leaves))
                        }
                    }
                    else -> {
                        val point = feature.geometry() as? Point
                        if (point != null) placeCallback(MapPlace(Location(latitude = point.latitude(), longitude = point.longitude())))
                    }
                }
                true
            }
            map.addOnMapLongClickListener { location ->
                placeCallback(MapPlace(Location(latitude = location.latitude, longitude = location.longitude))); true
            }
            ready = true
        }
        onDispose {
            disposed = true
            ready = false
            controller.savedCamera = controller.map?.cameraPosition
            controller.map = null
            lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallback)
            view.removeOnLayoutChangeListener(layoutListener)
            if (resumed) view.onPause()
            if (started) view.onStop()
            view.onDestroy()
        }
    }
    LaunchedEffect(ready, dark, retry, fallback) {
        if (!ready) return@LaunchedEffect
        val map = controller.map ?: return@LaunchedEffect
        val generation = ++styleGeneration[0]
        errorCallback(false)
        val handler = Handler(Looper.getMainLooper())
        val timeout = Runnable { if (!fallback) fallback = true else errorCallback(true) }
        val failure = MapView.OnDidFailLoadingMapListener {
            if (!fallback) fallback = true else errorCallback(true)
        }
        view.addOnDidFailLoadingMapListener(failure)
        handler.postDelayed(timeout, 15_000)
        val style = if (fallback) Style.Builder().fromJson(RASTER_FALLBACK)
        else Style.Builder().fromUri("https://tiles.openfreemap.org/styles/${if (dark) "dark" else "positron"}")
        map.setStyle(style) { loaded ->
            if (styleGeneration[0] != generation || controller.map !== map || !ready) return@setStyle
            handler.removeCallbacks(timeout)
            errorCallback(false)
            val features = mapFeatures(currentMemos)
            val options = GeoJsonOptions()
                .withCluster(true).withClusterRadius(44).withClusterMaxZoom(17)
                .withClusterProperty("count", literal("+"), get("count"))
            loaded.addSource(GeoJsonSource(MEMO_SOURCE, features, options))
            loaded.addLayer(CircleLayer(PIN_LAYER, MEMO_SOURCE).withProperties(
                circleRadius(16f), circleColor(currentColor.toArgb()), circleStrokeWidth(2f), circleStrokeColor(android.graphics.Color.WHITE)))
            // A missing glyph delays every layer in its source, including circles. Keep
            // labels separate so pins remain visible when fonts are unavailable offline.
            loaded.addSource(GeoJsonSource(COUNT_SOURCE, features, options))
            loaded.addLayer(SymbolLayer(COUNT_LAYER, COUNT_SOURCE).withProperties(
                textField(org.maplibre.android.style.expressions.Expression.toString(get("count"))),
                textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor(android.graphics.Color.WHITE), textAllowOverlap(true)))
            loaded.addSource(GeoJsonSource(SELECTION_SOURCE, selectionFeatures(currentSelection)))
            loaded.addLayer(CircleLayer("memosm-selected-pin", SELECTION_SOURCE).withProperties(
                circleRadius(21f), circleColor(android.graphics.Color.TRANSPARENT),
                circleStrokeWidth(3f), circleStrokeColor(currentColor.toArgb())))
            loadedStyle++
        }
        try { kotlinx.coroutines.awaitCancellation() }
        finally {
            if (styleGeneration[0] == generation) styleGeneration[0]++
            handler.removeCallbacks(timeout)
            view.removeOnDidFailLoadingMapListener(failure)
        }
    }
    LaunchedEffect(memos, color, ready, loadedStyle) {
        controller.memos = memos
        val features = mapFeatures(memos)
        controller.map?.style?.getSourceAs<GeoJsonSource>(MEMO_SOURCE)?.setGeoJson(features)
        controller.map?.style?.getSourceAs<GeoJsonSource>(COUNT_SOURCE)?.setGeoJson(features)
        controller.map?.style?.getLayerAs<CircleLayer>(PIN_LAYER)?.setProperties(circleColor(color.toArgb()))
    }
    // Fit the complete history after both the style and the native viewport exist. Fitting
    // the first cached page hides locations loaded later and can save an unfitted camera.
    LaunchedEffect(memos, initialFitReady, ready, loadedStyle, viewportSize) {
        if (ready && loadedStyle > 0 && viewportSize.width > 0 && viewportSize.height > 0 &&
            initialFitReady && !controller.fitted && memos.isNotEmpty()) {
            controller.memos = memos
            controller.fitAll()
        }
    }
    LaunchedEffect(selection, panelSize, ready, loadedStyle) {
        val map = controller.map ?: return@LaunchedEffect
        map.style?.getSourceAs<GeoJsonSource>(SELECTION_SOURCE)?.setGeoJson(selectionFeatures(selection))
        if (selection.hasValidCoordinates() && panelSize != IntSize.Zero) {
            val point = map.projection.toScreenLocation(LatLng(selection!!.latitude!!, selection.longitude!!))
            val right = (view.width - if (desktop) panelSize.width + 40 else 32).coerceAtLeast(48).toFloat()
            val bottom = (view.height - if (desktop) 32 else panelSize.height + 32).coerceAtLeast(48).toFloat()
            val dx = point.x - point.x.coerceIn(32f, right)
            val dy = point.y - point.y.coerceIn(32f, bottom)
            if (dx != 0f || dy != 0f) map.scrollBy(dx, dy, 200)
        }
    }
    AndroidView(factory = { view }, modifier = modifier)
}

private fun selectionFeatures(location: Location?): FeatureCollection = FeatureCollection.fromFeatures(
    if (location.hasValidCoordinates()) listOf(Feature.fromGeometry(Point.fromLngLat(location!!.longitude!!, location.latitude!!)))
    else emptyList()
)

private const val RASTER_FALLBACK = """{
  "version":8,
  "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,
    "attribution":"© <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap contributors</a>"}},
  "layers":[{"id":"osm","type":"raster","source":"osm"}]
}"""
