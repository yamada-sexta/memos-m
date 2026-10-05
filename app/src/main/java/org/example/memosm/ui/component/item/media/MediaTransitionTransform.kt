package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

internal data class MediaTransitionTransform(val scale: Float, val translation: Offset, val clip: Rect?)

/** Uniformly scales the media and morphs its crop into the thumbnail without stretching it. */
internal fun mediaTransitionTransform(
    viewport: Size,
    media: Size,
    origin: Rect?,
    startScale: Float,
    startTranslation: Offset,
    progress: Float,
    fallbackOffset: Offset = Offset.Zero
): MediaTransitionTransform {
    val target = origin?.takeIf {
        !it.isEmpty && it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite()
    }
    val targetScale = target?.let { max(it.width / media.width, it.height / media.height) } ?: startScale * 0.8f
    val scale = startScale + (targetScale - startScale) * progress
    val targetTranslation = target?.let { it.center - Offset(viewport.width / 2f, viewport.height / 2f) }
        ?: (startTranslation + fallbackOffset)
    val translation = startTranslation + (targetTranslation - startTranslation) * progress
    val clip = target?.let {
        val frameWidth = viewport.width * startScale + (it.width - viewport.width * startScale) * progress
        val frameHeight = viewport.height * startScale + (it.height - viewport.height * startScale) * progress
        val halfWidth = frameWidth / scale / 2f
        val halfHeight = frameHeight / scale / 2f
        Rect(viewport.width / 2f - halfWidth, viewport.height / 2f - halfHeight,
            viewport.width / 2f + halfWidth, viewport.height / 2f + halfHeight)
    }
    return MediaTransitionTransform(scale, translation, clip)
}
