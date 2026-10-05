package org.example.memosm.ui.component.item.media

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class VideoPlaybackStateTest {
    @Test
    fun tenSecondSeeksClampToBoundsAndPreservePause() {
        val fake = VideoPlayerStub()
        val state = VideoPlaybackState(fake.player)
        state.seekBy(-10_000L)
        assertEquals(50_000L, state.snapshot.position)
        state.seekBy(10_000L)
        assertEquals(60_000L, state.snapshot.position)
        state.seekTo(5_000L)
        state.seekBy(-10_000L)
        assertEquals(0L, state.snapshot.position)
        state.seekTo(115_000L)
        state.seekBy(10_000L)
        assertEquals(120_000L, state.snapshot.position)
        assertFalse(fake.playWhenReady)
        assertEquals(6, fake.seekCount)
    }

    @Test
    fun seekingDuringPlaybackDoesNotPause() {
        val fake = VideoPlayerStub().apply { playWhenReady = true }
        val state = VideoPlaybackState(fake.player)
        state.seekBy(10_000L)
        assertTrue(state.snapshot.isPlaying)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun unknownDurationAndUnseekableVideosIgnoreSeeking() {
        val fake = VideoPlayerStub().apply { duration = C.TIME_UNSET }
        val state = VideoPlaybackState(fake.player)
        assertFalse(state.snapshot.canSeek)
        state.seekBy(10_000L)
        fake.duration = 120_000L
        fake.seekable = false
        state.refresh()
        state.seekTo(30_000L)
        assertEquals(0, fake.seekCount)
    }

    @Test
    fun bufferingPlaybackCanBePausedAndResumed() {
        val fake = VideoPlayerStub().apply {
            playbackState = Player.STATE_BUFFERING
            playWhenReady = true
        }
        val state = VideoPlaybackState(fake.player)
        assertFalse(state.snapshot.isPlaying)
        state.togglePlayback()
        assertFalse(state.snapshot.playWhenReady)
        state.togglePlayback()
        assertTrue(state.snapshot.playWhenReady)
    }

    @Test
    fun playingAnEndedVideoRestartsIt() {
        val fake = VideoPlayerStub().apply {
            playbackState = Player.STATE_ENDED
            position = duration
            playWhenReady = true
        }
        val state = VideoPlaybackState(fake.player)
        state.togglePlayback()
        assertEquals(0L, state.snapshot.position)
        assertTrue(state.snapshot.playWhenReady)
    }

    @Test
    fun muteRestoresTheLastAudibleVolumeAndSpeedUpdatesImmediately() {
        val fake = VideoPlayerStub().apply { volume = 0.6f }
        val state = VideoPlaybackState(fake.player)
        state.toggleMute()
        assertEquals(0f, state.snapshot.volume, 0f)
        state.toggleMute()
        assertEquals(0.6f, state.snapshot.volume, 0f)
        state.changeSpeed(1.5f)
        assertEquals(1.5f, state.snapshot.speed, 0f)
    }

    @Test
    fun unavailableCommandsDisableTheirActions() {
        val fake = VideoPlayerStub().apply { commandsAvailable = false }
        val state = VideoPlaybackState(fake.player)
        state.seekBy(10_000L)
        state.togglePlayback()
        state.toggleMute()
        state.changeSpeed(2f)
        state.retry()
        assertFalse(state.snapshot.canSeek)
        assertFalse(state.snapshot.canPlayPause)
        assertEquals(60_000L, fake.position)
        assertFalse(fake.playWhenReady)
        assertEquals(1f, fake.volume, 0f)
        assertEquals(1f, fake.speed, 0f)
        assertEquals(0, fake.prepareCount)
    }

    @Test
    fun retryPreservesThePositionAndPlaybackIntent() {
        val fake = VideoPlayerStub().apply {
            playWhenReady = true
            error = PlaybackException("Test error", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        val state = VideoPlaybackState(fake.player)
        assertTrue(state.snapshot.hasError)
        assertFalse(state.snapshot.canPlayPause)
        state.retry()
        assertFalse(state.snapshot.hasError)
        assertEquals(60_000L, state.snapshot.position)
        assertTrue(state.snapshot.playWhenReady)
        assertEquals(1, fake.prepareCount)
    }

    /** Only the Player contract used by the controls; unexpected calls fail the test. */
    private class VideoPlayerStub {
        var position = 60_000L
        var duration = 120_000L
        var playbackState = Player.STATE_READY
        var playWhenReady = false
        var seekable = true
        var commandsAvailable = true
        var speed = 1f
        var volume = 1f
        var error: PlaybackException? = null
        var seekCount = 0
        var prepareCount = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCurrentPosition" -> position
                "getDuration" -> duration
                "getPlaybackState" -> playbackState
                "getPlayWhenReady" -> playWhenReady
                "isPlaying" -> playWhenReady && playbackState == Player.STATE_READY
                "isCurrentMediaItemSeekable" -> seekable
                "isCommandAvailable" -> commandsAvailable
                "getPlaybackParameters" -> PlaybackParameters(speed)
                "getVolume" -> volume
                "getPlayerError" -> error
                "play" -> { playWhenReady = true; null }
                "pause" -> { playWhenReady = false; null }
                "seekTo" -> { position = args!![0] as Long; seekCount++; null }
                "setPlaybackSpeed" -> { speed = args!![0] as Float; null }
                "setVolume" -> { volume = args!![0] as Float; null }
                "prepare" -> { error = null; playbackState = Player.STATE_READY; prepareCount++; null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "VideoPlayerStub"
                else -> error("Unexpected Player call: ${method.name}")
            }
        } as Player
    }
}
