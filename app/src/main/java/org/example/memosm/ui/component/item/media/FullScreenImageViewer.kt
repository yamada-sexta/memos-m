package org.example.memosm.ui.component.item.media

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

@Composable
fun FullScreenImageViewer(
    model: Any,
    filename: String,
    token: String?,
    onDismiss: () -> Unit,
    originBounds: (() -> Rect?)? = null,
    infoContent: (@Composable () -> Unit)? = null,
    actionsContent: (@Composable (showInfo: () -> Unit) -> Unit)? = null
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    var imageSize by remember(model) { mutableStateOf(IntSize.Zero) }
    val request = remember(model, token, accountIdentity) {
        val headers = NetworkHeaders.Builder().apply {
            if (token != null) set("Authorization", "Bearer $token")
        }.build()
        ImageRequest.Builder(context).data(model).httpHeaders(headers)
            .memoryCacheKey(accountMediaCacheKey(accountId, model.toString()))
            .diskCacheKey(accountMediaCacheKey(accountId, model.toString())).build()
    }
    FullScreenMediaDialog(
        onDismiss = onDismiss,
        mediaAspectRatio = if (imageSize.height > 0) imageSize.width.toFloat() / imageSize.height else null,
        originBounds = originBounds,
        infoContent = infoContent,
        actionsContent = actionsContent
    ) { dismiss ->
        AsyncImage(
            model = request,
            contentDescription = filename,
            modifier = Modifier.fillMaxSize().zoomable(true, dismiss, imageSize, doubleTapZoom = true),
            contentScale = ContentScale.Fit,
            onSuccess = {
                imageSize = IntSize(it.painter.intrinsicSize.width.toInt(), it.painter.intrinsicSize.height.toInt())
            }
        )
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
