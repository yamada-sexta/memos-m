package org.example.memosm.ui.component.item.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

fun Modifier.zoomable(
    enabled: Boolean,
    onDismiss: (() -> Unit)? = null,
    imageSize: IntSize = IntSize.Zero,
    doubleTapZoom: Boolean = false
): Modifier = composed {
    if (!enabled) return@composed this

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentImageSize by rememberUpdatedState(imageSize)
    val scope = rememberCoroutineScope()
    val gesturesBlocked = LocalViewerGesturesBlocked.current

    fun boundedOffset(value: Offset, zoom: Float): Offset {
        val scaled = if (currentImageSize.width > 0 && currentImageSize.height > 0) {
            calculateScaledSizes(
                viewSize.width.toFloat(), viewSize.height.toFloat(),
                currentImageSize.width.toFloat(), currentImageSize.height.toFloat(), zoom
            )
        } else ScaledInfo(viewSize.width * zoom, viewSize.height * zoom)
        val maxX = ((scaled.scaledWidth - viewSize.width) / 2f).coerceAtLeast(0f)
        val maxY = ((scaled.scaledHeight - viewSize.height) / 2f).coerceAtLeast(0f)
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }

    this
        .onSizeChanged { viewSize = it }
        .pointerInput(doubleTapZoom, gesturesBlocked) {
            if (!doubleTapZoom || gesturesBlocked) return@pointerInput
            detectTapGestures(onDoubleTap = {
                settleJob?.cancel()
                settleJob = scope.launch {
                    val target = if (scale > 1.5f) 1f else 2.5f
                    Animatable(scale).animateTo(target) { scale = value }
                    Animatable(offset, Offset.VectorConverter).animateTo(Offset.Zero) { offset = value }
                }
            })
        }
        .pointerInput(gesturesBlocked) {
            if (gesturesBlocked) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                settleJob?.cancel()
                // Latch ownership so pinching back to 1x cannot become a viewer swipe mid-gesture.
                var ownsGesture = scale > 1f
                var transforming = false
                var accumulatedPan = Offset.Zero
                do {
                    val event = awaitPointerEvent()
                    val multiplePointers = event.changes.count { it.pressed } > 1
                    ownsGesture = ownsGesture || multiplePointers
                    val pan = event.calculatePan()
                    accumulatedPan += pan
                    // Leave taps unconsumed, including double-tap to return to normal zoom.
                    transforming = transforming || multiplePointers ||
                        (ownsGesture && accumulatedPan.getDistance() > viewConfiguration.touchSlop)
                    if (transforming) {
                        val zoom = event.calculateZoom()
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        offset = if (scale > 1f) boundedOffset(offset + pan, scale) else Offset.Zero
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (transforming) {
                    val shouldDismiss = scale < 0.8f
                    if (shouldDismiss) currentDismiss?.invoke()
                    settleJob = scope.launch {
                        if (scale < 1f) Animatable(scale).animateTo(1f) { scale = value }
                        val target = boundedOffset(offset, scale)
                        Animatable(offset, Offset.VectorConverter).animateTo(target) { offset = value }
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
