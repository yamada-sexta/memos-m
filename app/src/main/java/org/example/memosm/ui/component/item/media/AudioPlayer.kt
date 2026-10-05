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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

enum class AudioPlayerMode {
    WIDE, NORMAL, COMPACT, GALLERY
}

internal data class AudioTrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null
)

internal data class AudioMetadataText(val title: String, val subtitle: String?, val details: String?)

internal fun audioMetadataText(
    filename: String, metadata: AudioTrackMetadata, fileType: String?, fileSize: String?
): AudioMetadataText {
    fun String?.nonBlank() = this?.trim()?.takeIf { it.isNotEmpty() }
    val tags = listOfNotNull(
        metadata.title.nonBlank()?.takeIf { it != filename }, metadata.artist.nonBlank(), metadata.album.nonBlank()
    )
    val subtitle = tags.joinToString(" • ").takeIf { it.isNotEmpty() }
    val details = listOfNotNull(
        metadata.durationMs?.takeIf { it > 0 }?.let(::formatAudioTime),
        fileType.nonBlank(), fileSize.nonBlank()
    ).joinToString(" • ").takeIf { it.isNotEmpty() }
    return AudioMetadataText(filename, subtitle, details)
}

@Composable
internal fun AudioMetadataContent(
    filename: String,
    metadata: AudioTrackMetadata = AudioTrackMetadata(),
    fileType: String? = null,
    fileSize: String? = null,
    modifier: Modifier = Modifier
) {
    val text = audioMetadataText(filename, metadata, fileType, fileSize)
    Column(
        modifier = modifier.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.AudioFile, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(text.title, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        text.details?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
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
    fileType: String? = null,
    fileSize: String? = null,
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
    var progress by remember(exoPlayer) { mutableFloatStateOf(0f) }
    var duration by remember(exoPlayer) { mutableLongStateOf(0L) }
    var currentPosition by remember(exoPlayer) { mutableLongStateOf(0L) }
    var isPrepared by remember(exoPlayer) { mutableStateOf(false) }

    var trackMetadata by remember(exoPlayer) { mutableStateOf(AudioTrackMetadata()) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                trackMetadata = AudioTrackMetadata(
                    title = mediaMetadata.title?.toString(),
                    artist = mediaMetadata.artist?.toString(),
                    album = mediaMetadata.albumTitle?.toString(),
                    durationMs = duration
                )
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isPrepared = true
                    duration = exoPlayer.duration
                    trackMetadata = trackMetadata.copy(durationMs = duration)
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
        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
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

    val togglePlayback = {
        if (isPrepared) {
            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
        }
    }
    val content = @Composable {
        when (mode) {
            AudioPlayerMode.GALLERY -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val text = audioMetadataText(filename, trackMetadata, fileType, fileSize)
                val showTags = maxHeight >= 160.dp
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    PlayPauseButton(
                        isPlaying = isPlaying, isPrepared = isPrepared, onToggle = togglePlayback,
                        modifier = Modifier.size(48.dp), iconSize = 28.dp
                    )
                    Text(text.title, style = MaterialTheme.typography.bodySmall, maxLines = if (showTags) 2 else 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    if (showTags) text.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                    text.details?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
                        isPlaying = isPlaying, isPrepared = isPrepared, onToggle = togglePlayback)
                    Column(modifier = Modifier.weight(1f)) {
                        val sliderState = remember { SliderState(value = progress) }
                        LaunchedEffect(progress) { sliderState.value = progress }
                        Slider(state = sliderState, onValueChange = {
                            if (isPrepared) {
                                progress = it; exoPlayer.seekTo((it * duration).toLong())
                            }
                        }, modifier = Modifier.height(24.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatAudioTime(currentPosition),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = formatAudioTime(duration),
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
                            isPlaying = isPlaying, isPrepared = isPrepared, onToggle = togglePlayback, modifier = Modifier.size(48.dp)
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
            modifier = modifier.then(if (mode != AudioPlayerMode.WIDE && mode != AudioPlayerMode.GALLERY) Modifier.height(100.dp) else Modifier),
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

internal fun formatAudioTime(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
