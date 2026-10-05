package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class ViewerZoomTransform(val scale: Float, val offset: Offset)

/** Positions are relative to the viewport center, matching graphicsLayer's transform origin. */
internal fun viewerZoomTransform(
    scale: Float,
    offset: Offset,
    centroid: Offset,
    pan: Offset,
    zoom: Float
): ViewerZoomTransform {
    val newScale = (scale * zoom).coerceIn(0.5f, 5f)
    val newOffset = if (newScale <= 1f) Offset.Zero
        else (offset - centroid) * (newScale / scale) + centroid + pan
    return ViewerZoomTransform(newScale, newOffset)
}

internal fun viewerPanBounds(viewport: IntSize, media: IntSize, scale: Float): Offset {
    val source = media.takeIf { it.width > 0 && it.height > 0 } ?: viewport
    if (source.width == 0 || source.height == 0) return Offset.Zero
    val fit = minOf(viewport.width.toFloat() / source.width, viewport.height.toFloat() / source.height)
    return Offset(
        ((source.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f),
        ((source.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
    )
}
