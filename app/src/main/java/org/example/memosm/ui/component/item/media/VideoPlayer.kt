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
    onDurationAvailable: (Long) -> Unit = {}
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    val immersive = LocalViewerImmersive.current
    val toggleImmersive = LocalViewerToggleImmersive.current
    val reportDuration by rememberUpdatedState(onDurationAvailable)
    var isFullscreen by remember { mutableStateOf(false) }
    var isReady by remember { mutableStateOf(false) }

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

    var mediaSize by remember(exoPlayer) { mutableStateOf(IntSize.Zero) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                player.duration.takeIf { it > 0L }?.let(reportDuration)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    isReady = true
                    val videoSize = exoPlayer.videoSize
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        mediaSize = IntSize(videoSize.width, videoSize.height)
                        val ratio = videoSize.width.toFloat() / videoSize.height
                        MediaCache.setAspectRatio(url, ratio)
                        onRatioAvailable(ratio)
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    mediaSize = IntSize(videoSize.width, videoSize.height)
                    val ratio = videoSize.width.toFloat() / videoSize.height
                    MediaCache.setAspectRatio(url, ratio)
                    onRatioAvailable(ratio)
                }
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = isFullScreen && !immersive
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    if (isFullScreen) {
                        setFullscreenButtonClickListener { toggleImmersive?.invoke() }
                        controllerShowTimeoutMs = 0
                    }
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    if (!isFullScreen) {
                        setOnClickListener { onClick?.invoke() ?: run { isFullscreen = true } }
                    }
                }
            }, update = { view ->
                view.player = if (isFullscreen) null else exoPlayer
                view.useController = isFullScreen && !immersive
                if (isFullScreen) {
                    view.setOnClickListener { toggleImmersive?.invoke() }
                    view.setFullscreenButtonClickListener { toggleImmersive?.invoke() }
                    if (!immersive) view.showController()
                }
                view.setOnLongClickListener(if (!isFullScreen && onLongClick != null) {
                    android.view.View.OnLongClickListener { onLongClick(); true }
                } else null)
            }, modifier = Modifier
                .fillMaxSize()
                .alpha(if (isReady) 1f else 0f)
                .zoomable(isFullScreen, mediaSize)
        )
        if (!isReady) CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }

    if (isFullscreen) {
        FullScreenMediaDialog(
            onDismiss = { isFullscreen = false },
            mediaAspectRatio = mediaSize.takeIf { it.width > 0 && it.height > 0 }
                ?.let { it.width.toFloat() / it.height },
            infoContent = infoContent,
            actionsContent = actionsContent
        ) {
            val videoImmersive = LocalViewerImmersive.current
            val toggleVideoImmersive = LocalViewerToggleImmersive.current
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            ) {
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = !videoImmersive
                        controllerShowTimeoutMs = 0
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }, update = { view ->
                    view.useController = !videoImmersive
                    view.setOnClickListener { toggleVideoImmersive?.invoke() }
                    view.setFullscreenButtonClickListener { toggleVideoImmersive?.invoke() }
                    if (!videoImmersive) view.showController()
                }, modifier = Modifier.fillMaxSize().zoomable(true, mediaSize))
            }
        }
    }
}
