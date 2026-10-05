package org.example.memosm.ui.component.item.media

import org.junit.Assert.*
import org.junit.Test

class AudioMetadataTextTest {
    @Test
    fun `untagged audio displays filename and known file details`() {
        assertEquals(
            AudioMetadataText("recording.m4a", null, "audio/mp4 • 2.4 MB"),
            audioMetadataText("recording.m4a", AudioTrackMetadata(), "audio/mp4", "2.4 MB")
        )
    }

    @Test
    fun `embedded tags and duration supplement the filename`() {
        assertEquals(
            AudioMetadataText("track.mp3", "Track Title • Artist • Album", "03:05 • audio/mpeg • 4 MB"),
            audioMetadataText("track.mp3", AudioTrackMetadata("Track Title", "Artist", "Album", 185_000), "audio/mpeg", "4 MB")
        )
    }

    @Test
    fun `missing tags and invalid durations are omitted without placeholders`() {
        for (duration in listOf(null, 0L, -1L, Long.MIN_VALUE + 1)) {
            assertEquals(
                AudioMetadataText("voice.ogg", null, null),
                audioMetadataText("voice.ogg", AudioTrackMetadata(" ", " ", "", duration), " ", null)
            )
        }
    }

    @Test
    fun `title alone preserves filename and adds track title as supporting metadata`() {
        assertEquals(AudioMetadataText("recording.wav", "My recording", "00:42"),
            audioMetadataText("recording.wav", AudioTrackMetadata(title = "My recording", durationMs = 42_000), null, null))
    }

    @Test
    fun `individual tags trim whitespace and omit missing fields`() {
        assertEquals(AudioMetadataText("track.mp3", "Track • Artist", "audio/mpeg"),
            audioMetadataText("track.mp3", AudioTrackMetadata(title = " Track ", artist = " Artist "), " audio/mpeg ", ""))
        assertEquals(AudioMetadataText("track.mp3", "Album", null),
            audioMetadataText("track.mp3", AudioTrackMetadata(album = "Album"), null, null))
    }

    @Test
    fun `playback time handles long recordings and unknown positions`() {
        assertEquals("120:05", formatAudioTime(7_205_000))
        assertEquals("00:00", formatAudioTime(-1))
    }
}
