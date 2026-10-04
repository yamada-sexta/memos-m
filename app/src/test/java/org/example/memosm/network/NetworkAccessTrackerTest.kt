package org.example.memosm.network

import org.example.memosm.data.network.NetworkAccessTracker
import org.junit.Assert.*
import org.junit.Test

class NetworkAccessTrackerTest {
    @Test fun `blocking and unblocking a connected network changes access without losing capabilities`() {
        val tracker = NetworkAccessTracker<String>()
        tracker.capabilities("wifi", internet = true, validated = true, wifi = true)
        assertTrue(tracker.online)
        tracker.blocked("wifi", true)
        assertFalse(tracker.online)
        assertFalse(tracker.wifi)
        assertTrue(tracker.blocked)
        tracker.capabilities("wifi", internet = true, validated = true, wifi = true)
        assertTrue(tracker.blocked)
        tracker.blocked("wifi", false)
        assertTrue(tracker.online)
        assertTrue(tracker.wifi)
        assertFalse(tracker.blocked)
        tracker.lost("wifi")
        assertFalse(tracker.online)
        assertFalse(tracker.blocked)
    }

    @Test fun `unvalidated LAN still exposes policy unblock for server probing`() {
        val tracker = NetworkAccessTracker<String>()
        tracker.capabilities("lan", internet = true, validated = false, wifi = true)
        tracker.blocked("lan", true)
        assertTrue(tracker.blocked)
        tracker.blocked("lan", false)
        assertFalse(tracker.online)
        assertFalse(tracker.blocked)
        assertTrue(tracker.wifi)
    }

    @Test fun `a blocked new default network cannot inherit the old network's access`() {
        val tracker = NetworkAccessTracker<String>()
        tracker.capabilities("wifi", internet = true, validated = true, wifi = true)
        tracker.available("cellular")
        tracker.capabilities("cellular", internet = true, validated = true, wifi = false)
        tracker.blocked("cellular", true)
        assertFalse(tracker.online)
        assertTrue(tracker.blocked)
        tracker.lost("wifi")
        assertTrue(tracker.blocked)
    }
}
