package org.example.memosm.ui.component.item.media

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import kotlinx.coroutines.launch
import org.example.memosm.R
import kotlin.math.abs

@Composable
fun FullScreenImageViewer(
    model: Any, filename: String, token: String?, onDismiss: () -> Unit
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
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

        val scale = remember { Animatable(1f) }
        val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
        val dismissOffset = remember { Animatable(0f) }
        var imageSize by remember { mutableStateOf(IntSize.Zero) }
        val coroutineScope = rememberCoroutineScope()
        var isZooming by remember { mutableStateOf(false) }

        // Calculate background alpha based on dismiss drag distance
        val dismissDragProgress = (abs(dismissOffset.value) / 300f).coerceIn(0f, 1f)
        val backgroundAlpha = (1f - dismissDragProgress).coerceIn(0f, 1f)
        val dismissScale = (1f - (abs(dismissOffset.value) / 1000f)).coerceIn(0.6f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = backgroundAlpha)),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val viewWidth = constraints.maxWidth.toFloat()
                val viewHeight = constraints.maxHeight.toFloat()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                coroutineScope.launch {
                                    if (scale.value > 1.5f) {
                                        scale.animateTo(1f)
                                        offset.animateTo(Offset.Zero)
                                    } else {
                                        scale.animateTo(2.5f)
                                    }
                                }
                            }, onTap = {
                                // Optional: Toggle UI visibility
                            })
                        }
                        .pointerInput(imageSize) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                coroutineScope.launch {
                                    // If not zoomed in significantly, vertical drag triggers dismiss gesture
                                    if ((scale.value <= 1.05f && abs(dismissOffset.value) > 10f) || (scale.value <= 1.05f && abs(
                                            pan.y
                                        ) > abs(pan.x) * 1.5f && !isZooming)
                                    ) {
                                        val newDismissOffset = dismissOffset.value + pan.y
                                        dismissOffset.snapTo(newDismissOffset)
                                        scale.snapTo(1f) // Reset zoomed state if starting dismiss
                                    } else {
                                        // Regular pan/zoom
                                        if (zoom != 1f) isZooming = true

                                        val newScale = (scale.value * zoom).coerceIn(0.8f, 5f)

                                        // Calculate new offset to keep zoom centered around centroid
                                        val newOffset = if (newScale > 1f) {
                                            val currentOffset = offset.value
                                            currentOffset + pan
                                        } else {
                                            Offset.Zero
                                        }

                                        scale.snapTo(newScale)
                                        offset.snapTo(newOffset)
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            // Detect end of gesture to animate back or dismiss
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    // If all pointers are up (gesture ended)
                                    if (event.changes.none { it.pressed }) {
                                        coroutineScope.launch {
                                            isZooming = false

                                            // Handle Dismiss Logic
                                            if (abs(dismissOffset.value) > 200f) {
                                                onDismiss()
                                            } else {
                                                dismissOffset.animateTo(0f)
                                            }

                                            // Handle Zoom Bounce-back
                                            if (scale.value < 1f) {
                                                scale.animateTo(1f)
                                                offset.animateTo(Offset.Zero)
                                            } else if (scale.value > 1f) {
                                                // Check bounds logic
                                                if (imageSize.width > 0 && imageSize.height > 0) {
                                                    val imageWidth = imageSize.width.toFloat()
                                                    val imageHeight = imageSize.height.toFloat()
                                                    val info = calculateScaledSizes(
                                                        viewWidth,
                                                        viewHeight,
                                                        imageWidth,
                                                        imageHeight,
                                                        scale.value
                                                    )
                                                    val maxX = maxOf(
                                                        0f, (info.scaledWidth - viewWidth) / 2f
                                                    )
                                                    val maxY = maxOf(
                                                        0f, (info.scaledHeight - viewHeight) / 2f
                                                    )

                                                    val targetX =
                                                        offset.value.x.coerceIn(-maxX, maxX)
                                                    val targetY =
                                                        offset.value.y.coerceIn(-maxY, maxY)

                                                    offset.animateTo(Offset(targetX, targetY))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale.value * dismissScale
                            scaleY = scale.value * dismissScale
                            translationX = offset.value.x
                            translationY = offset.value.y + dismissOffset.value
                        }, contentAlignment = Alignment.Center
                ) {
                    val headers = remember(token) {
                        val builder = NetworkHeaders.Builder()
                        if (token != null) builder.set("Authorization", "Bearer $token")
                        builder.build()
                    }

                    val fullImageRequest = remember(model, token, accountIdentity) {
                        ImageRequest.Builder(context).data(model).httpHeaders(headers)
            .memoryCacheKey(accountMediaCacheKey(accountId, model.toString()))
            .diskCacheKey(accountMediaCacheKey(accountId, model.toString())).build()
                    }

                    AsyncImage(
                        model = fullImageRequest,
                        contentDescription = filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        onSuccess = { state ->
                            imageSize = IntSize(
                                state.painter.intrinsicSize.width.toInt(),
                                state.painter.intrinsicSize.height.toInt()
                            )
                        })
                }
            }

            // Close Button - Fades out when dragging to dismiss
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
                    .graphicsLayer { alpha = backgroundAlpha }) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

data class ScaledInfo(val scaledWidth: Float, val scaledHeight: Float)

fun calculateScaledSizes(
    viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float, scale: Float
): ScaledInfo {
    val scaleFactor = minOf(viewWidth / imageWidth, viewHeight / imageHeight)
    val fitWidth = imageWidth * scaleFactor
    val fitHeight = imageHeight * scaleFactor
    return ScaledInfo(fitWidth * scale, fitHeight * scale)
}
