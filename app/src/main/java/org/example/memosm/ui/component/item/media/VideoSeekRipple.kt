package org.example.memosm.ui.component.item.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class VideoSeekFeedback(val direction: VideoTapAction, val position: Offset?, val generation: Int)

/** Aniyomi-style side highlight, Material ripple at the tap, and sequential directional arrows. */
@Composable
internal fun VideoSeekRipple(feedback: VideoSeekFeedback, modifier: Modifier = Modifier) {
    val forward = feedback.direction == VideoTapAction.Forward
    val source = remember { MutableInteractionSource() }
    val highlight = remember { Animatable(0f) }
    val phase = remember { Animatable(0f) }
    val inset = with(LocalDensity.current) { 12.dp.toPx() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var regionSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(feedback, inset) {
        // Let the indication attach before emitting the synthetic seek press.
        withFrameNanos { }
        val position = feedback.position?.let {
            Offset(
                (it.x - (if (forward) viewportSize.width * 0.6f else 0f) - inset).coerceIn(0f, regionSize.width.toFloat()),
                (it.y - inset).coerceIn(0f, regionSize.height.toFloat())
            )
        } ?: Offset(regionSize.width / 2f, regionSize.height / 2f)
        val press = PressInteraction.Press(position)
        source.emit(press)
        var released = false
        try {
            coroutineScope {
                launch {
                    phase.snapTo(0f)
                    phase.animateTo(6f, tween(750, easing = LinearEasing))
                }
                launch {
                    highlight.snapTo(0f)
                    highlight.animateTo(0.15f, tween(80))
                    delay(500)
                    highlight.animateTo(0f, tween(170))
                }
                delay(100)
                source.emit(PressInteraction.Release(press))
                released = true
            }
        } finally {
            if (!released) source.tryEmit(PressInteraction.Cancel(press))
        }
    }
    Box(modifier.fillMaxSize().onSizeChanged { viewportSize = it },
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(0.4f).padding(12.dp).onSizeChanged { regionSize = it },
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color.White.copy(alpha = highlight.value))
                .indication(source, ripple(color = Color.White)))
            Row(Modifier.rotate(if (forward) 0f else 180f), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    val opacity = (phase.value - index).coerceIn(0f, 1f) -
                        (phase.value - index - 3f).coerceIn(0f, 1f)
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White,
                        modifier = Modifier.size(24.dp).alpha(opacity))
                }
            }
        }
    }
}
