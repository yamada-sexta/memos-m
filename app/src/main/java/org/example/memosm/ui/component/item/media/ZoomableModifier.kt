package org.example.memosm.ui.component.item.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

fun Modifier.zoomable(
    enabled: Boolean,
    imageSize: IntSize = IntSize.Zero,
    doubleTapZoom: Boolean = true
): Modifier = composed {
    if (!enabled) return@composed this

    var scale by remember { mutableFloatStateOf(1f) }
    var zoomStep by remember { mutableStateOf(ViewerZoomStep.Fit) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val currentImageSize by rememberUpdatedState(imageSize)
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val gesturesBlocked = LocalViewerGesturesBlocked.current
    val zoomState = LocalViewerZoomState.current
    val toggleImmersive by rememberUpdatedState(LocalViewerToggleImmersive.current)
    val zoomToken = remember { Any() }
    DisposableEffect(zoomState) {
        onDispose { if (zoomState?.zoomedBy === zoomToken) zoomState.zoomedBy = null }
    }
    LaunchedEffect(gesturesBlocked) {
        if (gesturesBlocked) settleJob?.cancel()
    }

    fun reportZoom() {
        if (scale != 1f) zoomState?.zoomedBy = zoomToken
        else if (zoomState?.zoomedBy === zoomToken) zoomState.zoomedBy = null
    }

    fun boundedOffset(value: Offset, zoom: Float): Offset {
        val bounds = viewerPanBounds(viewSize, currentImageSize, zoom)
        return Offset(value.x.coerceIn(-bounds.x, bounds.x), value.y.coerceIn(-bounds.y, bounds.y))
    }

    suspend fun flingAxis(start: Float, velocity: Float, bound: Float, update: (Float) -> Unit) {
        if (bound <= 0f) return
        Animatable(start).apply {
            updateBounds(-bound, bound)
            animateDecay(velocity, decay) { update(value) }
        }
    }

    this
        .onSizeChanged { viewSize = it }
        .pointerInput(doubleTapZoom, gesturesBlocked, zoomState) {
            if (gesturesBlocked || (!doubleTapZoom && toggleImmersive == null)) return@pointerInput
            detectTapGestures(onTap = { toggleImmersive?.invoke() }, onDoubleTap = if (doubleTapZoom) { position ->
                settleJob?.cancel()
                val target = viewerDoubleTapTarget(zoomStep, viewSize, currentImageSize)
                zoomStep = target.step
                val startScale = scale
                val startOffset = offset
                val tap = position - Offset(viewSize.width / 2f, viewSize.height / 2f)
                val targetOffset = boundedOffset(
                    viewerZoomTransform(startScale, startOffset, tap, Offset.Zero, target.scale / startScale).offset,
                    target.scale
                )
                settleJob = scope.launch {
                    Animatable(0f).animateTo(1f) {
                        scale = startScale + (target.scale - startScale) * value
                        offset = boundedOffset(startOffset + (targetOffset - startOffset) * value, scale)
                        reportZoom()
                    }
                }
            } else null)
        }
        .pointerInput(gesturesBlocked, zoomState) {
            if (gesturesBlocked) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
                reportZoom()
                settleJob?.cancel()
                // Latch ownership so pinching back to 1x cannot become a viewer swipe mid-gesture.
                var ownsGesture = scale > 1f
                var transforming = false
                var pinched = false
                var accumulatedPan = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    val multiplePointers = event.changes.count { it.pressed } > 1
                    pinched = pinched || multiplePointers
                    if (!pinched) {
                        event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                    }
                    ownsGesture = ownsGesture || multiplePointers
                    val pan = event.calculatePan()
                    accumulatedPan += pan
                    // Leave taps unconsumed, including double-tap to return to normal zoom.
                    transforming = transforming || multiplePointers ||
                        (ownsGesture && accumulatedPan.getDistance() > viewConfiguration.touchSlop)
                    if (transforming) {
                        val zoom = event.calculateZoom()
                        if (zoom != 1f) zoomStep = ViewerZoomStep.Manual
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val relativeCentroid = if (centroid.isValid()) {
                            centroid - Offset(viewSize.width / 2f, viewSize.height / 2f)
                        } else Offset.Zero
                        val transform = viewerZoomTransform(scale, offset, relativeCentroid, pan, zoom)
                        scale = transform.scale
                        reportZoom()
                        offset = boundedOffset(transform.offset, scale)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
                val pinchDismiss = zoomState?.onPinchDismiss
                if (transforming && scale < 0.8f && pinchDismiss != null) {
                    // Transfer the release pose to the dialog's thumbnail transition in one frame.
                    pinchDismiss(ViewerZoomTransform(scale, offset))
                    scale = 1f
                    zoomStep = ViewerZoomStep.Fit
                    offset = Offset.Zero
                    reportZoom()
                } else if (transforming && scale < 1f) {
                    val releaseScale = scale
                    settleJob = scope.launch {
                        Animatable(releaseScale).animateTo(1f) {
                            scale = value
                            offset = boundedOffset(offset, scale)
                            reportZoom()
                        }
                        zoomStep = ViewerZoomStep.Fit
                    }
                } else if (transforming && scale > 1f && !pinched) {
                    val velocity = tracker.calculateVelocity()
                    val releaseOffset = offset
                    val bounds = viewerPanBounds(viewSize, currentImageSize, scale)
                    settleJob = scope.launch {
                        coroutineScope {
                            launch { flingAxis(releaseOffset.x, velocity.x, bounds.x) { offset = Offset(it, offset.y) } }
                            launch { flingAxis(releaseOffset.y, velocity.y, bounds.y) { offset = Offset(offset.x, it) } }
                        }
                    }
                }
            }
        }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
}
