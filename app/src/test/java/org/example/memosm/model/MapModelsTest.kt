package org.example.memosm.model

import org.junit.Assert.*
import org.junit.Test

class MapModelsTest {
    @Test fun `place selection includes coincident memos and all cluster locations`() {
        val point = Location(latitude = 41.0, longitude = -87.0)
        val nearby = Location(latitude = 41.0001, longitude = -87.0001)
        val memos = listOf(
            Memo(name = "memos/one", content = "one", location = point),
            Memo(name = "memos/two", content = "two", location = point),
            Memo(name = "memos/nearby", content = "nearby", location = nearby),
            Memo(name = "memos/elsewhere", content = "elsewhere", location = Location(latitude = 0.0, longitude = 0.0))
        )
        assertEquals(listOf("memos/one", "memos/two"), memosAtMapPlace(memos, MapPlace(point)).map { it.name })
        assertEquals(3, memosAtMapPlace(memos, MapPlace(point, listOf(point, nearby))).size)
        assertEquals(1, memosAtMapPlace(memos.drop(1), MapPlace(point)).size)
        assertTrue(memosAtMapPlace(memos, MapPlace(Location(latitude = 10.0, longitude = 20.0))).isEmpty())
    }

    @Test fun `pins group coincident locations and count every memo`() {
        val origin = Memo(name = "memos/1", content = "one", location = Location(latitude = 0.0, longitude = 0.0))
        val features = org.example.memosm.ui.component.map.mapFeatures(listOf(
            origin, origin.copy(name = "memos/2"), origin.copy(name = "memos/invalid", location = Location(latitude = 100.0, longitude = 0.0))
        )).features()!!
        assertEquals(1, features.size)
        assertEquals(2, features.single().getNumberProperty("count").toInt())
        assertEquals(0.0, (features.single().geometry() as org.maplibre.geojson.Point).latitude(), 0.0)
    }
    @Test fun `coordinate validation accepts origin and boundaries and rejects invalid numbers`() {
        assertTrue(Location(latitude = 0.0, longitude = 0.0).hasValidCoordinates())
        assertTrue(Location(latitude = -90.0, longitude = 180.0).hasValidCoordinates())
        assertFalse(Location(latitude = 90.001, longitude = 0.0).hasValidCoordinates())
        assertFalse(Location(latitude = 0.0, longitude = -180.001).hasValidCoordinates())
        assertFalse(Location(latitude = Double.NaN, longitude = 0.0).hasValidCoordinates())
        assertFalse(Location(latitude = 0.0, longitude = Double.POSITIVE_INFINITY).hasValidCoordinates())
        assertFalse(Location(latitude = 0.0).hasValidCoordinates())
        assertFalse(null.hasValidCoordinates())
    }

    @Test fun `new memo place labels come only from the current author`() {
        val point = Location(latitude = 0.0, longitude = 0.0)
        val other = Memo(name = "memos/other", content = "other", creator = "users/2", location = point.copy(placeholder = "Their place"))
        val own = Memo(name = "memos/own", content = "own", creator = "users/1", location = point.copy(placeholder = "My place"))
        assertEquals("0.0, 0.0", ownMapLocation(point, listOf(other), "users/1").placeholder)
        assertEquals("My place", ownMapLocation(point, listOf(other, own), "users/1").placeholder)
        assertNull(sharedMapLabel(listOf(other, own)))
        assertEquals("My place", sharedMapLabel(listOf(own)))
    }

    @Test fun `map scope excludes comments archived memos and other users private memos`() {
        val memo = Memo(name = "memos/1", content = "memo", creator = "users/1", location = Location(latitude = 0.0, longitude = 0.0))
        assertTrue(memo.belongsOnMap(MapScope.MEMOS, "users/1"))
        assertFalse(memo.belongsOnMap(MapScope.MEMOS, "users/2"))
        assertFalse(memo.belongsOnMap(MapScope.EXPLORE, "users/1"))
        assertTrue(memo.copy(visibility = Visibility.PUBLIC).belongsOnMap(MapScope.EXPLORE, "users/1"))
        assertFalse(memo.copy(parent = "memos/parent").belongsOnMap(MapScope.MEMOS, "users/1"))
        assertFalse(memo.copy(state = MemoState.ARCHIVED).belongsOnMap(MapScope.MEMOS, "users/1"))
        assertTrue(memo.belongsOnMap(MapScope.ALL, "users/1"))
        assertTrue(memo.copy(creator = "users/2", visibility = Visibility.PUBLIC).belongsOnMap(MapScope.ALL, "users/1"))
        assertFalse(memo.copy(creator = "users/2").belongsOnMap(MapScope.ALL, "users/1"))
    }

    @Test fun `server URL normalization preserves installation path`() {
        assertEquals("https://example.test/memos", normalizeMapHost(" https://example.test/memos/api/v1/ "))
    }
}
