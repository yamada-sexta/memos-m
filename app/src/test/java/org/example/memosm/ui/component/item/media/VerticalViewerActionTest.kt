package org.example.memosm.ui.component.item.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalViewerActionTest {
    @Test
    fun shortUpwardDragRestoresAndLongerDragRevealsDetails() {
        assertEquals(0, detailsSnapTarget(20, 400, 30f, -100f, 700f))
        assertEquals(400, detailsSnapTarget(100, 400, 30f, -100f, 700f))
    }

    @Test
    fun flingDirectionOpensOrClosesDetails() {
        assertEquals(400, detailsSnapTarget(20, 400, 30f, -800f, 700f))
        assertEquals(0, detailsSnapTarget(300, 400, 30f, 800f, 700f))
    }

    @Test
    fun downwardFlingFromScrolledDetailsRestoresTheirSnapPosition() {
        assertEquals(400, detailsSnapTarget(500, 400, 30f, 800f, 700f))
    }
}
