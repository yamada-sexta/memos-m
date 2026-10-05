package org.example.memosm.data

import androidx.collection.LruCache
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.example.memosm.model.Location

class LocationNamesTest {
    private val chicago = LocationNameKey(41.881832, -87.623177, "en-US")

    @Test fun `legacy coordinate labels and failed lookup labels are named again`() {
        val location = Location(latitude = chicago.latitude, longitude = chicago.longitude)
        assertNull(location.copy(placeholder = "${chicago.latitude}, ${chicago.longitude}")
            .customLocationName("Current Location"))
        assertNull(location.copy(placeholder = "Current Location").customLocationName("Current Location"))
        assertEquals("My place", location.copy(placeholder = " My place ").customLocationName("Current Location"))
    }

    @Test fun `repeated positions reuse successful names`() = runTest {
        val cache = LruCache<LocationNameKey, String>(2)
        var calls = 0
        repeat(2) {
            assertEquals("Chicago", cachedLocationName(chicago, cache) { calls++; "Chicago" })
        }
        assertEquals(1, calls)
    }

    @Test fun `coordinates and language keep independent names`() = runTest {
        val cache = LruCache<LocationNameKey, String>(4)
        assertEquals("Chicago", cachedLocationName(chicago, cache) { "Chicago" })
        assertEquals("シカゴ", cachedLocationName(chicago.copy(locale = "ja-JP"), cache) { "シカゴ" })
        assertEquals("Nearby", cachedLocationName(chicago.copy(latitude = 41.9), cache) { "Nearby" })
        assertEquals("Elsewhere", cachedLocationName(chicago.copy(longitude = -88.0), cache) { "Elsewhere" })
        assertEquals("Chicago", cachedLocationName(chicago, cache) { error("Expected a cached name") })
    }

    @Test fun `failed and blank results can be retried`() = runTest {
        val cache = LruCache<LocationNameKey, String>(2)
        assertNull(cachedLocationName(chicago, cache) { null })
        assertNull(cachedLocationName(chicago, cache) { " " })
        assertEquals("Chicago", cachedLocationName(chicago, cache) { "Chicago" })
    }

    @Test fun `recently used positions survive cache eviction`() = runTest {
        val cache = LruCache<LocationNameKey, String>(2)
        val tokyo = LocationNameKey(35.68, 139.69, "en-US")
        val london = LocationNameKey(51.5, -0.12, "en-US")
        cachedLocationName(chicago, cache) { "Chicago" }
        cachedLocationName(tokyo, cache) { "Tokyo" }
        cachedLocationName(chicago, cache) { error("Expected a cached name") }
        cachedLocationName(london, cache) { "London" }
        assertEquals("Chicago", cache[chicago])
        assertNull(cache[tokyo])
        assertEquals("Tokyo", cachedLocationName(tokyo, cache) { "Tokyo" })
        assertEquals(2, cache.size())
    }
}
