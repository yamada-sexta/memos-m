package org.example.memosm.ui.component.item.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import kotlinx.coroutines.delay

internal fun mediaSeekPosition(position: Long, duration: Long): Long =
    position.coerceIn(0L, duration.coerceAtLeast(0L))

internal fun mediaProgress(position: Long, duration: Long): Float =
    if (duration > 0L) mediaSeekPosition(position, duration).toFloat() / duration else 0f

internal fun toggleMediaPlayback(player: Player) {
    when {
        player.playbackState == Player.STATE_ENDED -> {
            if (player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(0L)
            player.play()
        }
        player.playWhenReady -> player.pause()
        else -> player.play()
    }
}

internal enum class VideoTapAction { Rewind, PlayPause, Forward }

internal fun videoTapAction(x: Float, width: Float): VideoTapAction = when {
    x < width * 0.4f -> VideoTapAction.Rewind
    x > width * 0.6f -> VideoTapAction.Forward
    else -> VideoTapAction.PlayPause
}

internal data class VideoPlaybackSnapshot(
    val position: Long = 0L,
    val duration: Long = 0L,
    val playbackState: Int = Player.STATE_IDLE,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val canPlayPause: Boolean = false,
    val canSeek: Boolean = false,
    val canChangeSpeed: Boolean = false,
    val canChangeVolume: Boolean = false,
    val canRetry: Boolean = false,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val hasError: Boolean = false
)

@Stable
internal class VideoPlaybackState(private val player: Player) {
    var snapshot by mutableStateOf(VideoPlaybackSnapshot())
        private set
    private var audibleVolume = 1f

    init { refresh() }

    fun refresh() {
        if (player.volume > 0f) audibleVolume = player.volume
        val duration = player.duration.coerceAtLeast(0L)
        val hasError = player.playerError != null
        snapshot = VideoPlaybackSnapshot(
            position = player.currentPosition.coerceAtLeast(0L),
            duration = duration,
            playbackState = player.playbackState,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            canPlayPause = player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE) &&
                player.playbackState != Player.STATE_IDLE && !hasError,
            canSeek = duration > 0L && player.isCurrentMediaItemSeekable &&
                player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) && !hasError,
            canChangeSpeed = player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH),
            canChangeVolume = player.isCommandAvailable(Player.COMMAND_SET_VOLUME),
            canRetry = player.isCommandAvailable(Player.COMMAND_PREPARE),
            speed = player.playbackParameters.speed,
            volume = player.volume,
            hasError = hasError
        )
    }

    fun togglePlayback() {
        if (!snapshot.canPlayPause) return
        toggleMediaPlayback(player)
        refresh()
    }

    fun seekTo(position: Long) {
        if (!snapshot.canSeek) return
        player.seekTo(mediaSeekPosition(position, player.duration))
        refresh()
    }

    fun seekBy(offset: Long) = seekTo(player.currentPosition + offset)

    fun changeSpeed(speed: Float) {
        if (!snapshot.canChangeSpeed) return
        player.setPlaybackSpeed(speed)
        refresh()
    }

    fun toggleMute() {
        if (!snapshot.canChangeVolume) return
        player.volume = if (player.volume > 0f) 0f else audibleVolume
        refresh()
    }

    fun retry() {
        if (!snapshot.canRetry) return
        player.prepare()
        refresh()
    }
}

@Composable
internal fun rememberVideoPlaybackState(player: Player, pollPosition: Boolean): VideoPlaybackState {
    val state = remember(player) { VideoPlaybackState(player) }
    DisposableEffect(player, state) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = state.refresh()
        }
        player.addListener(listener)
        state.refresh()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, pollPosition) {
        while (pollPosition) {
            state.refresh()
            delay(250)
        }
    }
    return state
}
