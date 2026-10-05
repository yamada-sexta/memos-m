package org.example.memosm.ui.component.item.media

import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.example.memosm.ui.findActivity

@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    token: String?,
    modifier: Modifier = Modifier,
    isFullScreen: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRatioAvailable: (Float) -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    infoContent: (@Composable () -> Unit)? = null,
    actionsContent: (@Composable (showInfo: () -> Unit) -> Unit)? = null,
    onDurationAvailable: (Long) -> Unit = {},
    filename: String? = null
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    val reportDuration by rememberUpdatedState(onDurationAvailable)
    val reportRatio by rememberUpdatedState(onRatioAvailable)
    var isFullscreen by remember { mutableStateOf(false) }

    // Use cached ratio if available - must use LaunchedEffect to avoid calling during composition
    val cachedRatio = MediaCache.getAspectRatio(url)
    LaunchedEffect(url, cachedRatio) {
        if (cachedRatio != null) {
            onRatioAvailable(cachedRatio)
        }
    }

    val exoPlayer = remember(url, token, accountIdentity) {
        val dataSourceFactory = MediaCache.createDataSourceFactory(context, token, accountId)
        ExoPlayer.Builder(context).setMediaSourceFactory(
            DefaultMediaSourceFactory(dataSourceFactory)
        ).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
        }
    }

    var isReady by remember(exoPlayer) { mutableStateOf(false) }
    var mediaSize by remember(exoPlayer) { mutableStateOf(IntSize.Zero) }

    DisposableEffect(exoPlayer) {
        fun reportVideoSize(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                mediaSize = IntSize(videoSize.width, videoSize.height)
                val ratio = videoSize.width.toFloat() / videoSize.height
                MediaCache.setAspectRatio(url, ratio)
                reportRatio(ratio)
            }
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                player.duration.takeIf { it > 0L }?.let(reportDuration)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReady = true
                    reportVideoSize(exoPlayer.videoSize)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                reportVideoSize(videoSize)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    Box(
        modifier = modifier.background(if (isFullScreen) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (isFullScreen) {
            VideoPlaybackSurface(exoPlayer, mediaSize)
        } else {
            VideoSurface(
                player = if (isFullscreen) null else exoPlayer,
                modifier = Modifier.fillMaxSize().alpha(if (isReady) 1f else 0f),
                onClick = { onClick?.invoke() ?: run { isFullscreen = true } },
                onLongClick = onLongClick
            )
            if (!isReady) CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
    }

    if (isFullscreen) {
        FullScreenMediaDialog(
            onDismiss = { isFullscreen = false },
            title = filename,
            mediaAspectRatio = mediaSize.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height },
            infoContent = infoContent,
            actionsContent = actionsContent
        ) {
            val activity = context.findActivity()
            val videoSize = exoPlayer.videoSize
            val isVertical =
                videoSize.height > 0 && videoSize.width > 0 && videoSize.width < videoSize.height
            DisposableEffect(Unit) {
                val originalOrientation =
                    activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.requestedOrientation = if (isVertical) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                onDispose { activity?.requestedOrientation = originalOrientation }
            }
            VideoPlaybackSurface(exoPlayer, mediaSize)
        }
    }
}

@Composable
private fun VideoPlaybackSurface(player: Player, mediaSize: IntSize) {
    val pollPosition = LocalViewerMediaActive.current && !LocalViewerGesturesBlocked.current && !LocalViewerImmersive.current
    val playback = rememberVideoPlaybackState(player, pollPosition)
    key(player) {
        VideoPlaybackControls(
            state = playback.snapshot,
            onTogglePlayback = playback::togglePlayback,
            onSeekTo = playback::seekTo,
            onSeekBy = playback::seekBy,
            onChangeSpeed = playback::changeSpeed,
            onToggleMute = playback::toggleMute,
            onRetry = playback::retry,
            mediaSize = mediaSize
        ) { VideoSurface(player, Modifier.fillMaxSize()) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(
    player: Player?,
    modifier: Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        update = { view ->
            view.player = player
            view.useController = false
            view.setOnClickListener(if (onClick != null) android.view.View.OnClickListener { onClick() } else null)
            view.isClickable = onClick != null
            view.setOnLongClickListener(if (onLongClick != null) android.view.View.OnLongClickListener {
                onLongClick(); true
            } else null)
            view.isLongClickable = onLongClick != null
        },
        onRelease = { it.player = null },
        modifier = modifier
    )
}
