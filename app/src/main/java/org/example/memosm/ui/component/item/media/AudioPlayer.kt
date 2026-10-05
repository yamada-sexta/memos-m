package org.example.memosm.ui.component.item.media

import org.example.memosm.ui.theme.flatCardElevation
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Replay30
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.automirrored.outlined.VolumeMute
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.memosm.R

enum class AudioPlayerMode {
    WIDE, NORMAL, COMPACT, FULL_SCREEN
}

@OptIn(UnstableApi::class)
@Composable
fun AudioPlayer(
    url: String,
    filename: String,
    token: String?,
    modifier: Modifier = Modifier,
    mode: AudioPlayerMode = AudioPlayerMode.NORMAL,
    showContainer: Boolean = true,
    onPlayingStateChanged: (Boolean) -> Unit = {}
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    val exoPlayer = remember(url, token, accountIdentity) {
        val dataSourceFactory = MediaCache.createDataSourceFactory(context, token, accountId)
        ExoPlayer.Builder(context).setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        ).build()
    }
    var isPlaying by remember(exoPlayer) { mutableStateOf(false) }
    var playWhenReady by remember(exoPlayer) { mutableStateOf(false) }
    var duration by remember(exoPlayer) { mutableLongStateOf(0L) }
    var currentPosition by remember(exoPlayer) { mutableLongStateOf(0L) }
    var scrubPosition by remember(exoPlayer) { mutableStateOf<Long?>(null) }
    var isPrepared by remember(exoPlayer) { mutableStateOf(false) }
    var isSeekable by remember(exoPlayer) { mutableStateOf(false) }
    var isBuffering by remember(exoPlayer) { mutableStateOf(false) }
    var volume by remember(exoPlayer) { mutableFloatStateOf(exoPlayer.volume) }
    var audibleVolume by remember(exoPlayer) { mutableFloatStateOf(1f) }
    val onPlayingChanged by rememberUpdatedState(onPlayingStateChanged)
    val controlsEnabled = !LocalViewerGesturesBlocked.current
    val displayedPosition = scrubPosition ?: currentPosition
    val progress = audioProgress(displayedPosition, duration)
    val canSeek = isPrepared && isSeekable && duration > 0 && controlsEnabled

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                duration = player.duration.coerceAtLeast(0L)
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                isPrepared = player.playbackState != Player.STATE_IDLE
                playWhenReady = player.playWhenReady && player.playbackState != Player.STATE_ENDED
                isSeekable = player.isCurrentMediaItemSeekable
                isBuffering = player.playbackState == Player.STATE_BUFFERING
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPlayingChanged(playing)
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            onPlayingChanged(false)
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0L)
            delay(250)
        }
    }

    val togglePlayback = {
        when {
            exoPlayer.playbackState == Player.STATE_ENDED -> {
                exoPlayer.seekTo(0L)
                exoPlayer.play()
            }
            exoPlayer.playWhenReady -> exoPlayer.pause()
            else -> exoPlayer.play()
        }
    }
    val seekTo: (Long) -> Unit = { position ->
        val target = audioSeekPosition(position, duration)
        exoPlayer.seekTo(target)
        currentPosition = target
    }
    val changeVolume: (Float) -> Unit = { requested ->
        volume = requested.coerceIn(0f, 1f)
        if (volume > 0f) audibleVolume = volume
        exoPlayer.volume = volume
    }
    val seekLabel = stringResource(R.string.audio_seek)
    val playbackLabel = stringResource(if (playWhenReady) R.string.memo_action_pause else R.string.memo_action_play)
    val seekDescription = "${formatMediaTime(displayedPosition)} / ${formatMediaTime(duration)}"
    val seekSlider: @Composable (Modifier) -> Unit = { sliderModifier ->
        val sliderState = remember(exoPlayer) { SliderState(value = progress) }
        LaunchedEffect(progress) { sliderState.value = progress }
        Slider(
            state = sliderState,
            enabled = canSeek,
            onValueChange = { scrubPosition = (it * duration).toLong() },
            onValueChangeFinished = {
                scrubPosition?.let(seekTo)
                scrubPosition = null
            },
            modifier = sliderModifier.semantics {
                contentDescription = seekLabel
                stateDescription = seekDescription
            }
        )
    }

    val content = @Composable {
        when (mode) {
            AudioPlayerMode.FULL_SCREEN -> Box(
                modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = filename, style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(formatMediaTime(displayedPosition), style = MaterialTheme.typography.labelMedium)
                        seekSlider(Modifier.weight(1f))
                        Text(formatMediaTime(duration), style = MaterialTheme.typography.labelMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val skipColors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        FilledTonalIconButton(
                            onClick = { seekTo(currentPosition - 30_000L) }, enabled = canSeek,
                            modifier = Modifier.weight(1f).height(80.dp),
                            shape = MaterialTheme.shapes.extraLarge, colors = skipColors
                        ) {
                            Icon(Icons.Outlined.Replay30, stringResource(R.string.audio_rewind_30))
                        }
                        FilledIconButton(
                            onClick = togglePlayback, enabled = isPrepared && controlsEnabled,
                            modifier = Modifier.weight(1.3f).height(96.dp)
                                .semantics { contentDescription = playbackLabel },
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            if (isBuffering) CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            ) else Icon(
                                if (playWhenReady) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { seekTo(currentPosition + 30_000L) }, enabled = canSeek,
                            modifier = Modifier.weight(1f).height(80.dp),
                            shape = MaterialTheme.shapes.extraLarge, colors = skipColors
                        ) {
                            Icon(Icons.Outlined.Forward30, stringResource(R.string.audio_forward_30))
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val volumeDescription = stringResource(R.string.audio_volume, (volume * 100).toInt())
                        FilledTonalIconButton(
                            onClick = { changeVolume(if (volume > 0f) 0f else audibleVolume) },
                            enabled = controlsEnabled,
                            modifier = Modifier.size(48.dp).semantics { stateDescription = volumeDescription }
                        ) {
                            Icon(
                                if (volume > 0f) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeMute,
                                stringResource(if (volume > 0f) R.string.audio_mute else R.string.audio_unmute)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { changeVolume(volume - 0.1f) },
                            enabled = controlsEnabled && volume > 0f,
                            modifier = Modifier.weight(1f).height(48.dp)
                                .semantics { stateDescription = volumeDescription }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeDown, stringResource(R.string.audio_volume_down))
                        }
                        FilledTonalIconButton(
                            onClick = { changeVolume(volume + 0.1f) },
                            enabled = controlsEnabled && volume < 1f,
                            modifier = Modifier.weight(1f).height(48.dp)
                                .semantics { stateDescription = volumeDescription }
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.VolumeUp, stringResource(R.string.audio_volume_up))
                        }
                    }
                }
            }

            AudioPlayerMode.WIDE -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = filename,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PlayPauseButton(
                        isPlaying = playWhenReady, isPrepared = isPrepared && controlsEnabled,
                        onToggle = togglePlayback)
                    Column(modifier = Modifier.weight(1f)) {
                        seekSlider(Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatMediaTime(displayedPosition),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = formatMediaTime(duration),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        PlayPauseButton(
                            isPlaying = playWhenReady, isPrepared = isPrepared && controlsEnabled,
                            onToggle = togglePlayback, modifier = Modifier.size(48.dp)
                        )
                    }

                    if (mode == AudioPlayerMode.NORMAL) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = filename,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    if (showContainer && mode != AudioPlayerMode.FULL_SCREEN) {
        Card(
            elevation = flatCardElevation(),
            modifier = modifier.then(if (mode == AudioPlayerMode.NORMAL || mode == AudioPlayerMode.COMPACT) Modifier.height(100.dp) else Modifier),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = MaterialTheme.shapes.medium
        ) { content() }
    } else {
        Box(modifier = modifier) { content() }
    }
}

@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    isPrepared: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 32.dp,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    val rotation = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    var isInitial by remember { mutableStateOf(true) }

    LaunchedEffect(isPlaying) {
        if (isInitial) {
            isInitial = false
            if (isPlaying) rotation.snapTo(180f)
            return@LaunchedEffect
        }

        launch {
            rotation.animateTo(
                targetValue = rotation.targetValue + 180f, animationSpec = spring(
                    stiffness = Spring.StiffnessLow, dampingRatio = Spring.DampingRatioMediumBouncy
                )
            )
        }

        launch {
            scale.animateTo(
                targetValue = if (isPlaying) 1.2f else 1f,
                animationSpec = spring(stiffness = Spring.StiffnessLow)
            )
        }
    }

    IconButton(onClick = onToggle, enabled = isPrepared, modifier = modifier) {
        Box(
            modifier = Modifier.graphicsLayer {
                rotationZ = rotation.value
                scaleX = scale.value
                scaleY = scale.value
            }, contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = isPlaying, transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(300))
                }, label = "IconSwap"
            ) { playing ->
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = androidx.compose.ui.res.stringResource(
                        if (playing) org.example.memosm.R.string.memo_action_pause
                        else org.example.memosm.R.string.memo_action_play
                    ),
                    modifier = Modifier.size(iconSize),
                    tint = tint
                )
            }
        }
    }
}

internal fun audioSeekPosition(position: Long, duration: Long): Long =
    position.coerceIn(0L, duration.coerceAtLeast(0L))

internal fun audioProgress(position: Long, duration: Long): Float =
    if (duration > 0L) audioSeekPosition(position, duration).toFloat() / duration else 0f
