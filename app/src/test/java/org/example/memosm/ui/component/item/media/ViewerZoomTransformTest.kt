package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerZoomTransformTest {
    @Test
    fun zoomOutKeepsImagePointUnderMovingFingerCentroid() {
        val scale = 3f
        val offset = Offset(40f, -20f)
        val centroid = Offset(120f, 80f)
        val pan = Offset(-10f, 15f)
        val imagePoint = (centroid - offset) / scale

        val result = viewerZoomTransform(scale, offset, centroid, pan, zoom = 0.5f)

        assertEquals(1.5f, result.scale, 0.001f)
        assertOffsetEquals(centroid + pan, imagePoint * result.scale + result.offset)
    }

    @Test
    fun zoomOutCanShrinkBelowFittedViewForDismissal() {
        val result = viewerZoomTransform(
            2f, Offset(90f, -50f), Offset(100f, 20f), Offset(10f, 5f), zoom = 0.35f
        )

        assertEquals(0.7f, result.scale, 0.001f)
        assertOffsetEquals(Offset.Zero, result.offset)
    }

    @Test
    fun continuedPinchLimitsShrinkageAndKeepsMediaCentered() {
        val result = viewerZoomTransform(
            1f, Offset.Zero, Offset(80f, 60f), Offset(30f, 20f), zoom = 0.2f
        )

        assertEquals(0.5f, result.scale, 0f)
        assertOffsetEquals(Offset.Zero, result.offset)
    }

    @Test
    fun maximumZoomUsesClampedScaleToKeepCentroidAnchored() {
        val centroid = Offset(80f, 40f)
        val result = viewerZoomTransform(4f, Offset.Zero, centroid, Offset.Zero, zoom = 2f)

        assertEquals(5f, result.scale, 0f)
        assertOffsetEquals(centroid, centroid / 4f * result.scale + result.offset)
    }

    @Test
    fun landscapeMediaCannotPanVerticallyUntilItFillsViewportHeight() {
        val bounds = viewerPanBounds(IntSize(400, 800), IntSize(800, 400), 2f)
        assertOffsetEquals(Offset(200f, 0f), bounds)
    }

    @Test
    fun fittedOrShrunkenMediaHasNoPanOverflow() {
        for (scale in listOf(1f, 0.7f)) {
            assertOffsetEquals(Offset.Zero, viewerPanBounds(IntSize(400, 800), IntSize(400, 800), scale))
        }
    }

    @Test
    fun unknownMediaSizeUsesViewportAndUnmeasuredViewportHasNoOverflow() {
        assertOffsetEquals(Offset(200f, 400f), viewerPanBounds(IntSize(400, 800), IntSize.Zero, 2f))
        assertOffsetEquals(Offset.Zero, viewerPanBounds(IntSize.Zero, IntSize.Zero, 2f))
    }

    private fun assertOffsetEquals(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }
}
