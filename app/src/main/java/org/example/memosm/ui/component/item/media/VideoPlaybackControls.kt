package org.example.memosm.ui.component.item.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import org.example.memosm.R

/** Video gestures transform the picture; sibling playback controls stay fixed to the viewport. */
@Composable
internal fun VideoPlaybackControls(
    state: VideoPlaybackSnapshot,
    onTogglePlayback: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onChangeSpeed: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    mediaSize: IntSize = IntSize.Zero,
    videoContent: @Composable () -> Unit
) {
    val immersive = LocalViewerImmersive.current
    val blocked = LocalViewerGesturesBlocked.current
    val active = LocalViewerMediaActive.current
    val setImmersive by rememberUpdatedState(LocalViewerSetImmersive.current)
    val toggleImmersive by rememberUpdatedState(LocalViewerToggleImmersive.current)
    var scrubPosition by remember { mutableStateOf<Long?>(null) }
    var speedMenuOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var seekFeedback by remember { mutableStateOf<VideoSeekFeedback?>(null) }
    var feedbackGeneration by remember { mutableIntStateOf(0) }
    val visible = !immersive && !blocked && active
    val displayedPosition = scrubPosition ?: state.position
    val canSeek = state.canSeek && !blocked && active
    val currentState by rememberUpdatedState(state)
    val currentTogglePlayback by rememberUpdatedState(onTogglePlayback)
    val currentSeekBy by rememberUpdatedState(onSeekBy)
    val interact: (() -> Unit) -> Unit = { action -> interaction++; action() }
    val seek: (VideoTapAction, Offset?) -> Unit = { direction, position ->
        if (currentState.canSeek) {
            currentSeekBy(if (direction == VideoTapAction.Rewind) -10_000L else 10_000L)
            feedbackGeneration++
            seekFeedback = VideoSeekFeedback(direction, position, feedbackGeneration)
        }
    }

    LaunchedEffect(state.playWhenReady, state.playbackState, state.hasError, active) {
        if (active && (!state.playWhenReady || state.playbackState == Player.STATE_ENDED || state.hasError)) {
            setImmersive?.invoke(false)
        }
    }
    LaunchedEffect(visible, state.isPlaying, scrubPosition != null, speedMenuOpen, interaction) {
        if (visible && state.isPlaying && scrubPosition == null && !speedMenuOpen) {
            delay(3000)
            setImmersive?.invoke(true)
        }
    }
    LaunchedEffect(feedbackGeneration) {
        delay(800)
        seekFeedback = null
    }
    LaunchedEffect(state.canSeek, blocked, active, immersive) {
        if (!canSeek || !visible) scrubPosition = null
        if (!visible) speedMenuOpen = false
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .pointerInput(blocked, active) {
                    if (blocked || !active) return@pointerInput
                    detectTapGestures(
                        onTap = { interact { toggleImmersive?.invoke() } },
                        onDoubleTap = { position ->
                            interact {
                                when (val action = videoTapAction(position.x, size.width.toFloat())) {
                                    VideoTapAction.PlayPause -> currentTogglePlayback()
                                    else -> seek(action, position)
                                }
                            }
                        }
                    )
                }
                .zoomable(true, mediaSize, doubleTapZoom = false, handleTapGestures = false)
        ) { videoContent() }

        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            // Background taps pass through to the video; only the actual controls consume input.
            Box(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    Row(
                        Modifier.align(Alignment.Center)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(50))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        IconButton(onClick = { interact { seek(VideoTapAction.Rewind, null) } }, enabled = canSeek && visible) {
                            Icon(Icons.Filled.Replay10, stringResource(R.string.video_rewind_10), Modifier.size(32.dp))
                        }
                        PlayPauseButton(
                            isPlaying = state.playWhenReady && state.playbackState != Player.STATE_ENDED,
                            isPrepared = state.canPlayPause && visible, onToggle = { interact(onTogglePlayback) },
                            modifier = Modifier.size(64.dp), iconSize = 48.dp,
                            tint = MaterialTheme.colorScheme.onSurface, filledIcons = true
                        )
                        IconButton(onClick = { interact { seek(VideoTapAction.Forward, null) } }, enabled = canSeek && visible) {
                            Icon(Icons.Filled.Forward10, stringResource(R.string.video_forward_10), Modifier.size(32.dp))
                        }
                    }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), MaterialTheme.shapes.extraLarge)
                        .padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val progress = playbackProgress(displayedPosition, state.duration)
                        val sliderState = remember { SliderState(value = progress) }
                        LaunchedEffect(progress) { sliderState.value = progress }
                        val seekLabel = stringResource(R.string.video_seek)
                        val durationText = if (state.duration > 0L) formatMediaTime(state.duration) else "--:--"
                        Slider(
                            state = sliderState, enabled = canSeek && visible,
                            onValueChange = { value ->
                                interaction++
                                scrubPosition = playbackSeekPosition((value * state.duration).toLong(), state.duration)
                            },
                            onValueChangeFinished = {
                                scrubPosition?.let(onSeekTo)
                                scrubPosition = null
                                interaction++
                            },
                            modifier = Modifier.fillMaxWidth().semantics {
                                contentDescription = seekLabel
                                stateDescription = "${formatMediaTime(displayedPosition)} / $durationText"
                            }
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatMediaTime(displayedPosition)} / $durationText",
                                style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)
                            )
                            Box {
                                val speedLabel = stringResource(R.string.video_playback_speed)
                                TextButton(
                                    onClick = { interact { speedMenuOpen = true } }, enabled = state.canChangeSpeed && visible,
                                    modifier = Modifier.semantics { contentDescription = speedLabel }
                                ) {
                                    Text(stringResource(R.string.video_speed_value, state.speed.toString().removeSuffix(".0")), color = LocalContentColor.current)
                                }
                                DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false; interaction++ }) {
                                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.video_speed_value, speed.toString().removeSuffix(".0"))) },
                                            onClick = { interact { onChangeSpeed(speed); speedMenuOpen = false } }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { interact(onToggleMute) }, enabled = state.canChangeVolume && visible) {
                                Icon(
                                    if (state.volume > 0f) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                    stringResource(if (state.volume > 0f) R.string.video_mute else R.string.video_unmute)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (active && !blocked && state.playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).padding(top = 100.dp).size(32.dp))
        }
        if (active && !blocked && state.hasError) {
            Column(
                Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.medium).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.video_playback_error))
                TextButton(onClick = { interact(onRetry) }, enabled = state.canRetry) {
                    Text(stringResource(R.string.profile_retry))
                }
            }
        }
        seekFeedback?.takeIf { active && !blocked }?.let { feedback ->
            VideoSeekRipple(feedback)
        }
    }
}
