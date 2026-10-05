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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    onRatioAvailable: (Float) -> Unit = {}
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
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
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = isFullScreen
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    if (isFullScreen) {
                        setFullscreenButtonClickListener { /* disabled */ }
                        controllerShowTimeoutMs = 3000
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
                view.useController = isFullScreen
            }, modifier = Modifier
                .fillMaxSize()
                .alpha(if (isReady) 1f else 0f)
                .zoomable(isFullScreen, mediaSize)
        )
        if (!isReady) CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false }, properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = false
            )
        ) {
            val window = (LocalView.current.parent as? DialogWindowProvider)?.window
            if (window != null) {
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

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
                    .background(Color.Black)
            ) {
                AndroidView(factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                        setFullscreenButtonClickListener { isFullscreen = false }
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
