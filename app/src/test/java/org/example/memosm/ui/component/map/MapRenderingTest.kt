package org.example.memosm.ui.component.map

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.example.memosm.model.Location
import org.example.memosm.model.Memo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class MapRenderingTest {
    @Test fun `pins preserve original coordinates for selection`() {
        val location = Location(latitude = 41.881832, longitude = -87.623177)
        val feature = mapFeatures(listOf(Memo(name = "memos/precise", content = "Precise place", location = location))).features()!!.single()
        assertEquals(location.latitude!!, feature.getNumberProperty("latitude").toDouble(), 0.0)
        assertEquals(location.longitude!!, feature.getNumberProperty("longitude").toDouble(), 0.0)
        val point = feature.geometry() as Point
        assertEquals(location.longitude!!, point.longitude(), 0.0)
        assertEquals(location.latitude!!, point.latitude(), 0.0)
    }

    @Test fun `count labels meet contrast on both dark and light pin colors`() {
        for (fill in listOf(Color(0xffd0bcff), Color(0xff6750a4), Color(0xff808080), Color.White, Color.Black)) {
            val label = mapPinLabelColor(fill, Color.White)
            val ratio = (maxOf(fill.luminance(), label.luminance()) + 0.05f) /
                (minOf(fill.luminance(), label.luminance()) + 0.05f)
            assertTrue("Pin labels must meet 4.5:1 contrast", ratio >= 4.5f)
        }
        assertEquals(Color.Black, mapPinLabelColor(Color(0xffd0bcff), Color.White))
    }
}
