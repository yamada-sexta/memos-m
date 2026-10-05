package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaTransitionTransformTest {
    private val viewport = Size(400f, 800f)
    private val media = Size(400f, 400f)
    private val thumbnail = Rect(30f, 100f, 130f, 200f)

    @Test
    fun exitStartsAtTheReleasedPoseRatherThanResettingToFullScreen() {
        val releasedTranslation = Offset(12f, 140f)
        val transform = mediaTransitionTransform(viewport, media, thumbnail, 0.75f, releasedTranslation, 0f)
        assertEquals(0.75f, transform.scale, 0f)
        assertEquals(releasedTranslation, transform.translation)
        assertEquals(Rect(0f, 0f, 400f, 800f), transform.clip)
    }

    @Test
    fun enterAndExitShareTheSameThumbnailEndpointWithoutStretchingMedia() {
        val transform = mediaTransitionTransform(viewport, media, thumbnail, 1f, Offset.Zero, 1f)
        val clip = transform.clip!!
        val viewportCenter = Offset(viewport.width / 2f, viewport.height / 2f)
        fun toScreen(position: Offset) = (position - viewportCenter) * transform.scale + viewportCenter + transform.translation
        assertEquals(thumbnail.topLeft, toScreen(clip.topLeft))
        assertEquals(thumbnail.bottomRight, toScreen(clip.bottomRight))
        assertEquals(0.25f, transform.scale, 0f)
    }

    @Test
    fun missingThumbnailUsesTheReleasedPoseAndFallbackMotion() {
        val releasedTranslation = Offset(0f, 140f)
        val first = mediaTransitionTransform(viewport, media, null, 0.75f, releasedTranslation, 0f)
        assertEquals(releasedTranslation, first.translation)
        assertEquals(0.75f, first.scale, 0f)
        val last = mediaTransitionTransform(viewport, media, null, 0.75f, releasedTranslation, 1f, Offset(0f, 200f))
        assertEquals(Offset(0f, 340f), last.translation)
        assertEquals(0.6f, last.scale, 0.0001f)
        assertNull(last.clip)
    }

    @Test
    fun enteringFinishesAtTheUntransformedFullScreenPose() {
        val transform = mediaTransitionTransform(viewport, media, thumbnail, 1f, Offset.Zero, 0f)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(Offset.Zero, transform.translation)
    }
}
