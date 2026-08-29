package org.example.memosm.ui.component.item.media

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import org.example.memosm.ui.component.item.mutableLongPositionOf
import java.util.Locale

enum class AudioPlayerMode {
    WIDE, NORMAL, COMPACT
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
    val context = LocalContext.current
    val exoPlayer = remember(url, token) {
        val dataSourceFactory = MediaCache.createDataSourceFactory(context, token)
        ExoPlayer.Builder(context).setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        ).build()
    }
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    var currentPosition by mutableLongPositionOf()
    var isPrepared by remember { mutableStateOf(false) }

    DisposableEffect(exoPlayer) {
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isPrepared = true
                    duration = exoPlayer.duration
                } else if (playbackState == Player.STATE_ENDED) {
                    progress = 0f
                    currentPosition = 0
                    exoPlayer.pause()
                    exoPlayer.seekTo(0)
                    onPlayingStateChanged(false)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onPlayingStateChanged(playing)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosition = exoPlayer.currentPosition
            progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
            delay(500)
        }
    }

    val content = @Composable {
        when (mode) {
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
                        isPlaying = isPlaying, isPrepared = isPrepared, onToggle = {
                            if (isPrepared) {
                                if (isPlaying) exoPlayer.pause()
                                else exoPlayer.play()
                            }
                        })
                    Column(modifier = Modifier.weight(1f)) {
                        Slider(value = progress, onValueChange = {
                            if (isPrepared) {
                                progress = it; exoPlayer.seekTo((it * duration).toLong())
                            }
                        }, modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(currentPosition),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = formatTime(duration),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = modifier
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
                            isPlaying = isPlaying, isPrepared = isPrepared, onToggle = {
                                if (isPrepared) {
                                    if (isPlaying) exoPlayer.pause()
                                    else exoPlayer.play()
                                }
                            }, modifier = Modifier.size(48.dp)
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

    if (showContainer) {
        Card(
            modifier = modifier.then(if (mode != AudioPlayerMode.WIDE) Modifier.height(100.dp) else Modifier),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp)
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

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
