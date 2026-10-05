package org.example.memosm.ui.component.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.RectF
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Rect
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
private const val PIN_OUTLINE_LAYER = "memosm-pin-outlines"
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
            // Rendered feature geometry comes from vector tiles and may be rounded.
            // Keep the original coordinates for selection and exact-place matching.
            addNumberProperty("latitude", coordinates.first!!)
            addNumberProperty("longitude", coordinates.second!!)
        }
    }
)

internal fun mapPinLabelColor(fill: Color, preferred: Color? = null): Color {
    fun contrast(other: Color): Float {
        val first = fill.luminance(); val second = other.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
    if (preferred != null && contrast(preferred) >= 4.5f) return preferred
    return if (contrast(Color.Black) >= contrast(Color.White)) Color.Black else Color.White
}

private fun Feature.originalMapLocation(): Location? {
    if (!hasProperty("latitude") || !hasProperty("longitude")) return null
    return Location(latitude = getNumberProperty("latitude").toDouble(), longitude = getNumberProperty("longitude").toDouble())
        .takeIf { it.hasValidCoordinates() }
}

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
    labelColor: Color = mapPinLabelColor(color),
    initialFitReady: Boolean = true,
    selection: Location? = null,
    panelBounds: Rect? = null,
    onPlace: (MapPlace) -> Unit,
    onTileError: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val placeCallback by rememberUpdatedState(onPlace)
    val errorCallback by rememberUpdatedState(onTileError)
    val currentMemos by rememberUpdatedState(memos)
    val currentColor by rememberUpdatedState(color)
    val currentLabelColor by rememberUpdatedState(labelColor)
    val currentSelection by rememberUpdatedState(selection)
    var ready by remember { mutableStateOf(false) }
    var loadedStyle by remember { mutableIntStateOf(0) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val styleGeneration = remember { longArrayOf(0L) }
    var fallback by remember(retry, dark) { mutableStateOf(false) }
    val view = remember(controller) {
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(tileClient)
        MapView(context, MapLibreMapOptions.createFromAttributes(context).textureMode(true).logoEnabled(false)
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
            @Deprecated("Android no longer dispatches onLowMemory; use onTrimMemory")
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
            fun selectPlace(location: LatLng) {
                val screen = map.projection.toScreenLocation(location)
                val tolerance = 8f * context.resources.displayMetrics.density
                val features = map.queryRenderedFeatures(RectF(screen.x - tolerance, screen.y - tolerance,
                    screen.x + tolerance, screen.y + tolerance), PIN_LAYER)
                // Include the visible halo in the hit area and choose the closest
                // circle if a finger overlaps more than one rendered feature.
                val feature = features.minByOrNull {
                    val point = it.geometry() as? Point
                    if (point == null) Double.POSITIVE_INFINITY else {
                        val center = map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                        val dx = center.x - screen.x; val dy = center.y - screen.y
                        (dx * dx + dy * dy).toDouble()
                    }
                }
                when {
                    feature == null -> placeCallback(MapPlace(Location(latitude = location.latitude, longitude = location.longitude)))
                    feature.hasProperty("cluster_id") -> {
                        val source = map.style?.getSourceAs<GeoJsonSource>(MEMO_SOURCE)
                        val point = feature.geometry() as? Point
                        if (point != null && source != null) {
                            val leaves = source.getClusterLeaves(feature, currentMemos.size.toLong(), 0)
                                .features().orEmpty().mapNotNull { it.originalMapLocation() }
                            placeCallback(MapPlace(Location(latitude = point.latitude(), longitude = point.longitude()), leaves))
                        }
                    }
                    else -> {
                        feature.originalMapLocation()?.let { placeCallback(MapPlace(it)) }
                    }
                }
            }
            map.addOnMapClickListener { location -> selectPlace(location); true }
            map.addOnMapLongClickListener { location ->
                selectPlace(location); true
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
            // A white halo with a dark outer edge remains visible on both basemaps.
            loaded.addLayer(CircleLayer(PIN_OUTLINE_LAYER, MEMO_SOURCE).withProperties(
                circleRadius(18f), circleColor(android.graphics.Color.WHITE), circleStrokeWidth(1f), circleStrokeColor(android.graphics.Color.BLACK)))
            loaded.addLayer(CircleLayer(PIN_LAYER, MEMO_SOURCE).withProperties(
                circleRadius(16f), circleColor(currentColor.toArgb())))
            // A missing glyph delays every layer in its source, including circles. Keep
            // labels separate so pins remain visible when fonts are unavailable offline.
            loaded.addSource(GeoJsonSource(COUNT_SOURCE, features, options))
            loaded.addLayer(SymbolLayer(COUNT_LAYER, COUNT_SOURCE).withProperties(
                textField(org.maplibre.android.style.expressions.Expression.toString(get("count"))),
                textFont(arrayOf("Noto Sans Regular")), textSize(12f), textColor(currentLabelColor.toArgb()), textAllowOverlap(true)))
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
    LaunchedEffect(memos, color, labelColor, ready, loadedStyle) {
        controller.memos = memos
        val features = mapFeatures(memos)
        controller.map?.style?.getSourceAs<GeoJsonSource>(MEMO_SOURCE)?.setGeoJson(features)
        controller.map?.style?.getSourceAs<GeoJsonSource>(COUNT_SOURCE)?.setGeoJson(features)
        controller.map?.style?.getLayerAs<CircleLayer>(PIN_LAYER)?.setProperties(circleColor(color.toArgb()))
        controller.map?.style?.getLayerAs<SymbolLayer>(COUNT_LAYER)?.setProperties(textColor(labelColor.toArgb()))
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
    LaunchedEffect(selection, panelBounds, ready, loadedStyle, viewportSize) {
        val map = controller.map ?: return@LaunchedEffect
        map.style?.getSourceAs<GeoJsonSource>(SELECTION_SOURCE)?.setGeoJson(selectionFeatures(selection))
        if (selection.hasValidCoordinates() && panelBounds != null && panelBounds.height > 0f) {
            val point = map.projection.toScreenLocation(LatLng(selection!!.latitude!!, selection.longitude!!))
            // Both rectangles use pixels relative to this MapView. Tablet rails and
            // adaptive panes can offset it from the window; window dimensions cannot
            // be used to decide whether the actual place sheet covers a pin.
            val bounds = panelBounds.inflate(32f)
            if (point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom) {
                if (panelBounds.width >= view.width * 0.75f) {
                    map.scrollBy(0f, bounds.top.coerceAtLeast(32f) - point.y, 200)
                } else {
                    val target = if (bounds.left > 32f) bounds.left else bounds.right.coerceAtMost(view.width - 32f)
                    map.scrollBy(target - point.x, 0f, 200)
                }
            }
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
