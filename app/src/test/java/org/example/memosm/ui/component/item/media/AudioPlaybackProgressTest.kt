package org.example.memosm.ui.component.item.media

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackProgressTest {
    @Test
    fun rewindAndForwardStopAtTheTrackBoundaries() {
        assertEquals(0L, playbackSeekPosition(12_000L - 30_000L, 200_000L))
        assertEquals(200_000L, playbackSeekPosition(195_000L + 30_000L, 200_000L))
        assertEquals(90_000L, playbackSeekPosition(60_000L + 30_000L, 200_000L))
    }

    @Test
    fun unknownDurationHasNoProgressOrSeekTarget() {
        listOf(0L, C.TIME_UNSET).forEach { duration ->
            assertEquals(0L, playbackSeekPosition(30_000L, duration))
            assertEquals(0f, playbackProgress(30_000L, duration), 0f)
            assertEquals("00:00", formatMediaTime(duration))
        }
    }

    @Test
    fun progressHandlesSeekingAndTheEndOfPlayback() {
        assertEquals(0.25f, playbackProgress(30_000L, 120_000L), 0f)
        assertEquals(0f, playbackProgress(-1L, 120_000L), 0f)
        assertEquals(1f, playbackProgress(120_000L, 120_000L), 0f)
        assertEquals(1f, playbackProgress(121_000L, 120_000L), 0f)
    }

    @Test
    fun timesIncludeHoursForLongRecordings() {
        assertEquals("00:09", formatMediaTime(9_999L))
        assertEquals("59:59", formatMediaTime(3_599_000L))
        assertEquals("1:00:00", formatMediaTime(3_600_000L))
        assertEquals("2:03:04", formatMediaTime(7_384_000L))
    }
}
