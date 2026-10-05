package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

internal data class ViewerZoomTransform(val scale: Float, val offset: Offset)
internal enum class ViewerZoomStep { Fit, Cover, Original, Manual }
internal data class ViewerZoomTarget(val step: ViewerZoomStep, val scale: Float)

/** Immich's PhotoView cycle: contained -> covered -> original pixels -> contained. */
internal fun viewerDoubleTapTarget(step: ViewerZoomStep, viewport: IntSize, media: IntSize): ViewerZoomTarget {
    if (step == ViewerZoomStep.Manual || step == ViewerZoomStep.Original) {
        return ViewerZoomTarget(ViewerZoomStep.Fit, 1f)
    }
    if (viewport.width <= 0 || viewport.height <= 0 || media.width <= 0 || media.height <= 0) {
        return if (step == ViewerZoomStep.Fit) ViewerZoomTarget(ViewerZoomStep.Cover, 2.5f)
            else ViewerZoomTarget(ViewerZoomStep.Fit, 1f)
    }
    val widthScale = viewport.width.toFloat() / media.width
    val heightScale = viewport.height.toFloat() / media.height
    val fit = minOf(widthScale, heightScale)
    val cover = (maxOf(widthScale, heightScale) / fit).coerceIn(1f, 5f)
    val original = (1f / fit).coerceIn(1f, 5f)
    if (step == ViewerZoomStep.Fit && cover > 1f) return ViewerZoomTarget(ViewerZoomStep.Cover, cover)
    if (original > 1f && (step == ViewerZoomStep.Fit || original != cover)) {
        return ViewerZoomTarget(ViewerZoomStep.Original, original)
    }
    return ViewerZoomTarget(ViewerZoomStep.Fit, 1f)
}

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
