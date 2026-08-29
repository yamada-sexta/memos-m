package org.example.memosm.ui.component.item.media

import android.net.Uri
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.model.Attachment
import org.example.memosm.viewmodel.manager.AttachmentManager

@Composable
fun MemoImage(
    modifier: Modifier = Modifier,
    attachment: Attachment? = null,
    token: String? = null,
    hostUrl: String = "",
    uri: Uri = Uri.EMPTY,
    filename: String = "",
    contentDescription: String? = filename.takeIf { it.isNotBlank() },
    isRound: Boolean = false,
    placeholderIcon: ImageVector? = null,
    onRatioAvailable: (Float) -> Unit = {},
    onClick: (() -> Unit)? = null,
    isFullScreen: Boolean = false,
    onDismiss: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val imageModels = produceState<Pair<Any?, Any?>>(Pair(null, null), uri, attachment, hostUrl) {
        value = withContext(Dispatchers.IO) {
            when {
                uri != Uri.EMPTY -> Pair(uri, uri)
                attachment != null -> {
                    val original = AttachmentManager.getAttachmentUrl(hostUrl, attachment)
                        ?: when {
                            !attachment.content.isNullOrBlank() -> {
                                try {
                                    Base64.decode(attachment.content, Base64.NO_WRAP)
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            else -> null
                        }

                    val preview = if (original is String) {
                        AttachmentManager.getAttachmentThumbnailUrl(hostUrl, attachment) ?: original
                    } else {
                        original
                    }
                    Pair(preview, original)
                }

                else -> Pair(null, null)
            }
        }
    }
    val previewModel = imageModels.value.first
    val originalModel = imageModels.value.second
    var useOriginal by remember(previewModel, originalModel, isFullScreen) {
        mutableStateOf(isFullScreen || previewModel == originalModel)
    }
    val model = if (useOriginal) originalModel else previewModel

    val cacheKey = remember(uri, attachment, hostUrl) {
        when {
            uri != Uri.EMPTY -> uri.toString()
            attachment?.name != null -> "${hostUrl}_${attachment.name}"
            else -> model?.toString()
        }
    }

    LaunchedEffect(model, token) {
        android.util.Log.d(
            "MemosDebug", "MemoImage: model=$model, hasToken=${token != null}, hostUrl=$hostUrl"
        )
    }

    // Use cached ratio if available
    val cachedRatio = cacheKey?.let { MediaCache.getAspectRatio(it) }
    if (cachedRatio != null) {
        onRatioAvailable(cachedRatio)
    }

    val headers = remember(token) {
        val builder = NetworkHeaders.Builder()
        if (token != null) builder.set("Authorization", "Bearer $token")
        builder.build()
    }

    val imageRequest = remember(model, headers) {
        ImageRequest.Builder(context).data(model).httpHeaders(headers)
            .diskCachePolicy(CachePolicy.ENABLED).memoryCachePolicy(CachePolicy.ENABLED).build()
    }

    var isLoading by remember { mutableStateOf(model != null) }
    var isError by remember { mutableStateOf(false) }

    Box(
        modifier = modifier, contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            val imgModifier = Modifier
                .fillMaxSize()
                .then(if (isRound) Modifier.clip(CircleShape) else Modifier)
                .then(if (onClick != null && !isFullScreen) Modifier.clickable { onClick() } else Modifier)

            AsyncImage(
                model = imageRequest,
                contentDescription = contentDescription,
                modifier = imgModifier.zoomable(isFullScreen, onDismiss),
                contentScale = if (isFullScreen) ContentScale.Fit else ContentScale.Crop,
                onLoading = { isLoading = true; isError = false },
                onSuccess = { state ->
                    isLoading = false
                    isError = false
                    val size = state.painter.intrinsicSize
                    if (size.width > 0 && size.height > 0) {
                        val ratio = size.width / size.height
                        if (cacheKey != null) MediaCache.setAspectRatio(cacheKey, ratio)
                        onRatioAvailable(ratio)
                    }
                },
                onError = {
                    if (!useOriginal && originalModel != null && originalModel != previewModel) {
                        useOriginal = true
                        return@AsyncImage
                    }
                    android.util.Log.e(
                        "MemosDebug", "MemoImage error: $filename, result=${it.result.throwable}"
                    )
                    isLoading = false; isError = true
                })
        } else {
            isError = true
            isLoading = false
        }

        if (isLoading && !isRound) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp), strokeWidth = 2.dp
            )
        }

        if (isError || (isLoading && isRound)) {
            if (placeholderIcon != null) {
                Icon(
                    imageVector = placeholderIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isRound) 4.dp else 0.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            } else if (isError) {
                Icon(
                    imageVector = Icons.Outlined.BrokenImage,
                    contentDescription = stringResource(R.string.attachments_error),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
