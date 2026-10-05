package org.example.memosm.ui.component.item.media

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerGestureAxisTest {
    @Test
    fun smallMotionDoesNotStartNavigation() {
        assertEquals(ViewerGestureAxis.Undecided, viewerGestureAxis(Offset(3f, 4f), 8f))
    }

    @Test
    fun dominantVerticalMotionIgnoresHorizontalDrift() {
        assertEquals(ViewerGestureAxis.Vertical, viewerGestureAxis(Offset(8f, 30f), 8f))
        assertEquals(ViewerGestureAxis.Vertical, viewerGestureAxis(Offset(-8f, -30f), 8f))
    }

    @Test
    fun dominantHorizontalMotionIgnoresVerticalDrift() {
        assertEquals(ViewerGestureAxis.Horizontal, viewerGestureAxis(Offset(30f, 8f), 8f))
        assertEquals(ViewerGestureAxis.Horizontal, viewerGestureAxis(Offset(-30f, -8f), 8f))
    }
}
