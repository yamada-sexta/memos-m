package org.example.memosm.ui.component.item

import android.net.Uri
import org.example.memosm.ui.component.item.media.LocalAccountMediaIdentity
import android.util.Base64
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.model.Attachment
import org.example.memosm.ui.component.item.media.AttachmentOrigin
import org.example.memosm.ui.component.item.media.AudioPlayer
import org.example.memosm.ui.component.item.media.AudioPlayerMode
import org.example.memosm.ui.component.item.media.FileThumbnail
import org.example.memosm.ui.component.item.media.FileThumbnailMode
import org.example.memosm.ui.component.item.media.FullScreenImageViewer
import org.example.memosm.ui.component.item.media.MemoImage
import org.example.memosm.ui.component.item.media.VideoPlayer
import org.example.memosm.viewmodel.manager.AttachmentManager
import java.io.File

enum class AttachmentCompactMode {
    Area, Width, Height, Always, Never
}


@Composable
fun AttachmentCard(
    modifier: Modifier = Modifier,
    attachment: Attachment?,
    token: String?,
    hostUrl: String,
    uri: Uri = Uri.EMPTY,
    showInfo: Boolean = true,
    showActions: Boolean = true,
    showSize: Boolean = true,
    showFilename: Boolean = true,
    compactMode: AttachmentCompactMode = AttachmentCompactMode.Area,
    isFullScreen: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRatioAvailable: (Float, Boolean) -> Unit = { _, _ -> },
    mediaModifier: Modifier = Modifier
) {
    val accountIdentity = LocalAccountMediaIdentity.current
    val accountId = accountIdentity?.id
    val context = LocalContext.current
    val origin = remember { AttachmentOrigin() }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showFullScreenImage by remember { mutableStateOf(false) }
    var isAudioPlaying by remember { mutableStateOf(false) }

    val info = rememberAttachmentInfo(attachment, uri)
    val filename = info.filename
    val displayType = info.type
    val formattedSize = info.size.orEmpty()
    val formattedDate = info.created.orEmpty()
    val isImage = remember(displayType) {
        displayType.startsWith("image/", ignoreCase = true) || displayType.contains(
            "image", ignoreCase = true
        )
    }
    val isAudio = remember(displayType) {
        displayType.startsWith("audio/", ignoreCase = true) || displayType.contains(
            "audio", ignoreCase = true
        )
    }
    val isVideo = remember(displayType) {
        displayType.startsWith("video/", ignoreCase = true) || displayType.contains(
            "video", ignoreCase = true
        )
    }

    // Audio handling (temp file for base64 if needed)
    val audioUrl =
        produceState<String?>(initialValue = null, uri, attachment, displayType, hostUrl, accountIdentity) {
            if (!isAudio) {
                value = null
            } else {
                value = withContext(Dispatchers.IO) {
                    val localFile = org.example.memosm.MemosApplication.instance
                        .attachmentCacheManager.getLocalFileForAccount(accountId, attachment?.name)
                    when {
                        uri != Uri.EMPTY -> uri.toString()
                        localFile != null -> localFile.toUri().toString()
                        else -> AttachmentManager.getAttachmentUrl(hostUrl, attachment) ?: when {
                            !attachment?.content.isNullOrBlank() -> {
                                try {
                                    val bytes = Base64.decode(attachment.content, Base64.NO_WRAP)
                                    val ext = when {
                                        displayType.contains("aac") -> "aac"
                                        displayType.contains("mp3") || displayType.contains("mpeg") -> "mp3"
                                        displayType.contains("ogg") -> "ogg"
                                        displayType.contains("wav") -> "wav"
                                        displayType.contains("m4a") -> "m4a"
                                        else -> "aac"
                                    }
                                    val tempFile = File(
                                        context.cacheDir, "cached_audio_${filename.hashCode()}.$ext"
                                    )
                                    if (!tempFile.exists() || tempFile.length() != bytes.size.toLong()) {
                                        tempFile.writeBytes(bytes)
                                    }
                                    tempFile.toUri().toString()
                                } catch (e: Exception) {
                                    Log.e("AttachmentCard", "Error creating temp audio file", e)
                                    null
                                }
                            }

                            else -> null
                        }
                    }
                }
            }
        }.value


    // Default ratios before loading
    var intrinsicRatio by remember {
        mutableFloatStateOf(
            when {
                isVideo -> 1.777f // 16:9 as a better default for videos
                else -> 1.0f
            }
        )
    }
    var isIntrinsicExact by remember { mutableStateOf(false) }

    val backgroundColor by animateColorAsState(
        targetValue = if (isAudioPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        label = "AttachmentCardBackground",
        animationSpec = tween(durationMillis = 300)
    )

    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        @Suppress("COMPOSE_APPLIER_CALL_MISMATCH") BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isCompact = when (compactMode) {
                AttachmentCompactMode.Always -> true
                AttachmentCompactMode.Never -> false
                AttachmentCompactMode.Width -> maxWidth < 160.dp
                AttachmentCompactMode.Height -> maxHeight < 140.dp
                AttachmentCompactMode.Area -> {
                    val area = maxWidth.value * maxHeight.value
                    area < 25000f || maxWidth < 160.dp || maxHeight < 140.dp
                }
            }
            val isWide = !isCompact && maxWidth > 240.dp
            val showFooter =
                showInfo && !isCompact && (showFilename || showSize || attachment?.createTime != null)

            // Report total ratio to parent
            LaunchedEffect(
                intrinsicRatio,
                maxWidth,
                isCompact,
                isWide,
                showInfo,
                showFilename,
                showActions,
                showSize
            ) {
                val w = maxWidth.value
                val footerHeight =
                    if (showInfo && !isCompact && (showFilename || showSize || attachment?.createTime != null)) 56f else 0f

                val calculatedRatio = if (isImage || isVideo) {
                    if (w > 0 && footerHeight > 0) {
                        // For media with footer: (Width) / (MediaHeight + FooterHeight)
                        // MediaHeight = Width / IntrinsicRatio
                        w / (w / intrinsicRatio + footerHeight)
                    } else {
                        // No footer or width 0: just use intrinsic
                        intrinsicRatio
                    }
                } else {
                    // To fix "RECT" (square), let's ensure non-media is nicer.
                    // "Rect" usually means square in this context (1:1)
                    val nonMediaIntrinsic = 1.0f
                    val effectiveIntrinsic =
                        if (isWide) (if (w > 0) w / 180f else 2.0f) else nonMediaIntrinsic

                    if (w > 0 && footerHeight > 0) {
                        w / (w / effectiveIntrinsic + footerHeight)
                    } else {
                        effectiveIntrinsic
                    }
                }

                // If it's not an image/video, the calculated ratio is always exact (we defined it)
                val isExact = if (isImage || isVideo) isIntrinsicExact else true
                onRatioAvailable(calculatedRatio, isExact)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(backgroundColor)
                        .onGloballyPositioned { origin.coordinates = it }
                        .then(mediaModifier),
                    contentAlignment = Alignment.Center
                ) {
                    if (isImage) {
                        MemoImage(
                            attachment = attachment,
                            token = token,
                            hostUrl = hostUrl,
                            uri = uri,
                            filename = filename,
                            modifier = Modifier.fillMaxSize(),
                            onRatioAvailable = {
                                intrinsicRatio = it
                                isIntrinsicExact = true
                            },
                            onClick = if (isFullScreen) null else { onClick ?: { showFullScreenImage = true } },
                            isFullScreen = isFullScreen
                        )
                    } else if (isVideo) {
                        val videoUrl = produceState<String?>(
                            initialValue = null, uri, attachment, hostUrl, accountIdentity
                        ) {
                            value = withContext(Dispatchers.IO) {
                                val localFile = org.example.memosm.MemosApplication.instance
                                    .attachmentCacheManager.getLocalFileForAccount(accountId, attachment?.name)
                                when {
                                    uri != Uri.EMPTY -> uri.toString()
                                    localFile != null -> localFile.toUri().toString()
                                    else -> AttachmentManager.getAttachmentUrl(hostUrl, attachment)
                                }
                            }
                        }.value
                        if (!videoUrl.isNullOrBlank()) {
                            VideoPlayer(
                                url = videoUrl,
                                token = token,
                                modifier = Modifier.fillMaxSize(),
                                isFullScreen = isFullScreen,
                                onClick = if (isFullScreen) null else onClick,
                                onRatioAvailable = {
                                    intrinsicRatio = it
                                    isIntrinsicExact = true
                                })
                        }
                    } else if (isAudio && !audioUrl.isNullOrBlank()) {
                        AudioPlayer(
                            url = audioUrl,
                            filename = filename,
                            token = token,
                            mode = when {
                                    isFullScreen -> AudioPlayerMode.NORMAL
                                isWide -> AudioPlayerMode.WIDE
                                isCompact -> AudioPlayerMode.COMPACT
                                else -> AudioPlayerMode.NORMAL
                            },
                            showContainer = false,
                            onPlayingStateChanged = { isAudioPlaying = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        // Check if it's a profile picture/avatar
                        val isProfilePicture = remember(displayType) {
                            displayType.startsWith(
                                "avatar/", ignoreCase = true
                            ) || filename.contains("profile", ignoreCase = true)
                        }

                        if (isProfilePicture) {
                            MemoImage(
                                attachment = attachment,
                                token = token,
                                hostUrl = hostUrl,
                                uri = uri,
                                filename = filename,
                                isRound = true,
                                modifier = Modifier.fillMaxSize(),
                                onClick = if (isFullScreen) null else { onClick ?: { showFullScreenImage = true } },
                                isFullScreen = isFullScreen
                            )
                        } else {
                            FileThumbnail(
                                displayType = displayType,
                                filename = filename,
                                mode = when {
                                    isFullScreen -> FileThumbnailMode.NORMAL
                                    isWide -> FileThumbnailMode.WIDE
                                    isCompact -> FileThumbnailMode.COMPACT
                                    else -> FileThumbnailMode.NORMAL
                                },
                                onClick = if (isFullScreen) { {} } else { onClick ?: { showInfoDialog = true } },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Floating menu button
                    if (showInfo && showActions) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            AttachmentActionsButton(
                                attachment = attachment, token = token, hostUrl = hostUrl,
                                filename = filename, onShowInfo = { showInfoDialog = true }
                            )
                        }
                    }
                }

                if (showFooter) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .height(48.dp)
                    ) {
                        if (showFilename) {
                            Text(
                                text = filename,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val infoText = remember(formattedSize, formattedDate) {
                                listOfNotNull(
                                    formattedSize.takeIf { showSize && attachment?.size != null },
                                    formattedDate.takeIf { formattedDate.isNotEmpty() }).joinToString(
                                    " • "
                                )
                            }

                            if (infoText.isNotEmpty()) {
                                Text(
                                    text = infoText,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfoDialog) {
        AttachmentInfoSheet(info = info, onDismiss = { showInfoDialog = false })
    }

    if (showFullScreenImage && isImage) {
        val model = produceState<Any?>(initialValue = null, uri, attachment, hostUrl, accountIdentity) {
            value = withContext(Dispatchers.IO) {
                val localFile = org.example.memosm.MemosApplication.instance
                    .attachmentCacheManager.getLocalFileForAccount(accountId, attachment?.name)
                when {
                    uri != Uri.EMPTY -> uri
                    localFile != null -> localFile
                    else -> AttachmentManager.getAttachmentUrl(hostUrl, attachment) ?: when {
                        !attachment?.content.isNullOrBlank() -> {
                            try {
                                Base64.decode(attachment.content, Base64.NO_WRAP)
                            } catch (_: Exception) {
                                null
                            }
                        }

                        else -> null
                    }
                }
            }
        }.value

        if (model != null && !isFullScreen) {
            FullScreenImageViewer(
                model = model,
                filename = filename,
                token = token,
                onDismiss = { showFullScreenImage = false },
                originBounds = origin::boundsOnScreen,
                infoContent = { AttachmentInfoContent(info) },
                actionsContent = { showInfo ->
                    AttachmentActionsButton(attachment, token, hostUrl, filename, showInfo)
                })
        }
    }
}

@Composable
fun mutableLongPositionOf() = remember { mutableLongStateOf(0L) }
