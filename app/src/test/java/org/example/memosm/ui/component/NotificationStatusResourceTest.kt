package org.example.memosm.ui.component

import org.example.memosm.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationStatusResourceTest {
    @Test
    fun mapsKnownStatuses() {
        assertEquals(R.string.notification_status_unread, notificationStatusResource("UNREAD"))
        assertEquals(R.string.notification_status_read, notificationStatusResource("READ"))
        assertEquals(R.string.notification_status_archived, notificationStatusResource("ARCHIVED"))
    }

    @Test
    fun omitsUnspecifiedAndUnknownStatuses() {
        assertNull(notificationStatusResource("STATUS_UNSPECIFIED"))
        assertNull(notificationStatusResource("UNSPECIFIED"))
        assertNull(notificationStatusResource(null))
        assertNull(notificationStatusResource("UNKNOWN_FUTURE_VALUE"))
    }
}
