package org.example.memosm.ui.component.item.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlin.math.abs

internal enum class ViewerGestureAxis { Undecided, Horizontal, Vertical }

internal fun viewerGestureAxis(delta: Offset, touchSlop: Float): ViewerGestureAxis = when {
    delta.getDistance() <= touchSlop -> ViewerGestureAxis.Undecided
    abs(delta.x) > abs(delta.y) -> ViewerGestureAxis.Horizontal
    else -> ViewerGestureAxis.Vertical
}

internal class ViewerZoomState {
    var zoomedBy: Any? = null
    var onPinchDismiss: ((ViewerZoomTransform) -> Unit)? = null
}
internal val LocalViewerZoomState = compositionLocalOf<ViewerZoomState?> { null }

/** Choose once before the pager sees the motion; horizontal gestures stay with the pager. */
internal suspend fun PointerInputScope.detectViewerVerticalGestures(
    canStart: () -> Boolean,
    onDragStart: (Offset) -> Unit,
    onDragCancel: () -> Unit,
    onDragEnd: () -> Unit,
    onVerticalDrag: (PointerInputChange, Float) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var axis = ViewerGestureAxis.Undecided
        var previous = down.position
        var relinquished = false
        var dragging = false
        var completed = false
        try {
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || !canStart()) {
                    relinquished = true
                    if (dragging) { onDragCancel(); dragging = false }
                }
                if (!relinquished) {
                    if (!change.pressed) {
                        completed = true
                        if (dragging) { onDragEnd(); dragging = false }
                    } else if (axis == ViewerGestureAxis.Undecided) {
                        val delta = change.position - down.position
                        axis = viewerGestureAxis(delta, viewConfiguration.touchSlop)
                        if (axis == ViewerGestureAxis.Vertical) {
                            dragging = true
                            onDragStart(down.position)
                            val overSlop = delta.y * (1f - viewConfiguration.touchSlop / delta.getDistance())
                            onVerticalDrag(change, overSlop)
                            change.consume()
                        }
                    } else if (axis == ViewerGestureAxis.Vertical) {
                        onVerticalDrag(change, change.position.y - previous.y)
                        change.consume()
                    }
                }
                previous = change.position
            } while (event.changes.any { it.pressed })
        } finally {
            if (dragging && !completed) onDragCancel()
        }
    }
}
