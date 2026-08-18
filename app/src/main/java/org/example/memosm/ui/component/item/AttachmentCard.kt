package org.example.memosm.ui.component.item

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.format.Formatter
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.model.Attachment
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
    onDismiss: (() -> Unit)? = null,
    onRatioAvailable: (Float, Boolean) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showInfoDialog by remember { mutableStateOf(false) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFullScreenImage by remember { mutableStateOf(false) }
    var isAudioPlaying by remember { mutableStateOf(false) }

    val dateMillis by produceState<Long?>(initialValue = null, attachment?.createTime) {
        if (attachment?.createTime != null) {
            value = attachment.createTime.toEpochMilliseconds()
        }
    }

    val formattedDate = if (dateMillis != null) {
        android.text.format.DateUtils.formatDateTime(
            context,
            dateMillis!!,
            android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_SHOW_YEAR or android.text.format.DateUtils.FORMAT_ABBREV_MONTH
        )
    } else ""

    val formattedSize by produceState(initialValue = "", attachment?.size) {
        if (attachment?.size != null) {
            value = withContext(Dispatchers.Default) {
                val bytes = attachment.size.toLongOrNull()
                if (bytes != null) Formatter.formatFileSize(context, bytes) else attachment.size
            }
        }
    }

    val displayType by produceState(
        initialValue = attachment?.displayType ?: "", uri, attachment?.displayType
    ) {
        value = withContext(Dispatchers.IO) {
            if (uri != Uri.EMPTY) {
                val crType = context.contentResolver.getType(uri)
                if (crType != null) crType
                else {
                    val ext = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: ""
                }
            } else {
                attachment?.displayType ?: ""
            }
        }
    }

    val unknownFilename = stringResource(R.string.attachments_unknown_filename)
    val filename = remember(attachment?.filename, uri, unknownFilename) {
        attachment?.filename ?: uri.lastPathSegment ?: unknownFilename
    }

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
        produceState<String?>(initialValue = null, uri, attachment, displayType, hostUrl) {
            if (!isAudio) {
                value = null
            } else {
                value = withContext(Dispatchers.IO) {
                    when {
                        uri != Uri.EMPTY -> uri.toString()
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
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
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
                        .background(backgroundColor),
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
                            isFullScreen = isFullScreen,
                            onDismiss = onDismiss
                        )
                    } else if (isVideo) {
                        val videoUrl =
                            if (uri != Uri.EMPTY) uri.toString() else AttachmentManager.getAttachmentUrl(
                                hostUrl, attachment
                            )
                        if (!videoUrl.isNullOrBlank()) {
                            VideoPlayer(
                                url = videoUrl,
                                token = token,
                                modifier = Modifier.fillMaxSize(),
                                isFullScreen = isFullScreen,
                                onClick = if (isFullScreen) null else onClick,
                                onDismiss = onDismiss,
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
                                isFullScreen = isFullScreen,
                                onDismiss = onDismiss
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

                    val errorOpenLinkString = stringResource(R.string.attachments_error_open_link)

                    // Floating menu button
                    if (showInfo && showActions) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { showMenu = true }) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    val openWebUrl = remember(attachment, hostUrl) {
                                        AttachmentManager.getAttachmentUrl(hostUrl, attachment)
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.attachments_info_title)) },
                                            onClick = { showMenu = false; showInfoDialog = true },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.Info, contentDescription = null
                                                )
                                            })
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.attachments_download_button)) },
                                            onClick = {
                                                showMenu = false; showDownloadDialog = true
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Outlined.Download,
                                                    contentDescription = null
                                                )
                                            })
                                        if (openWebUrl != null) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.memo_action_open_web)) },
                                                onClick = {
                                                    showMenu = false
                                                    try {
                                                        val intent = Intent(
                                                            Intent.ACTION_VIEW, openWebUrl.toUri()
                                                        )
                                                        context.startActivity(intent)
                                                    } catch (e: Exception) {
                                                        Log.e(
                                                            "AttachmentCard",
                                                            "Failed to open link",
                                                            e
                                                        )
                                                        Toast.makeText(
                                                            context,
                                                            errorOpenLinkString,
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                    }
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Outlined.Language,
                                                        contentDescription = null
                                                    )
                                                })

                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.common_share)) },
                                                onClick = {
                                                    showMenu = false
                                                    try {
                                                        val sendIntent = Intent().apply {
                                                            action = Intent.ACTION_SEND
                                                            putExtra(Intent.EXTRA_TEXT, openWebUrl)
                                                            type = "text/plain"
                                                        }
                                                        val shareIntent =
                                                            Intent.createChooser(sendIntent, null)
                                                        context.startActivity(shareIntent)
                                                    } catch (e: Exception) {
                                                        Log.e(
                                                            "AttachmentCard",
                                                            "Failed to share link",
                                                            e
                                                        )
                                                    }
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        Icons.Outlined.Share,
                                                        contentDescription = null
                                                    )
                                                })
                                        }
                                    }
                                }
                            }
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
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            title = { Text(stringResource(R.string.attachments_info_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AttachmentInfoRow(stringResource(R.string.attachments_info_filename), filename)
                    AttachmentInfoRow(stringResource(R.string.attachments_info_type), displayType)
                    if (attachment?.size != null) AttachmentInfoRow(
                        stringResource(R.string.attachments_info_size), formattedSize
                    )
                    if (attachment?.createTime != null) AttachmentInfoRow(
                        stringResource(R.string.attachments_info_created), formattedDate
                    )
                    if (attachment?.name != null) AttachmentInfoRow(
                        stringResource(R.string.attachments_info_id), attachment.name
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            })
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.attachments_download_dialog_title)) },
            text = { Text(stringResource(R.string.attachments_download_dialog_confirm, filename)) },
            confirmButton = {
                TextButton(onClick = {
                    if (attachment != null) downloadAttachmentFile(
                        context, attachment, token, hostUrl
                    )
                    showDownloadDialog = false
                }) {
                    Text(stringResource(R.string.attachments_download_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }

    if (showFullScreenImage && isImage) {
        val model = remember(uri, attachment, hostUrl) {
            when {
                uri != Uri.EMPTY -> uri
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

        if (model != null && !isFullScreen) {
            FullScreenImageViewer(
                model = model,
                filename = filename,
                token = token,
                onDismiss = { showFullScreenImage = false })
        }
    }
}

@Composable
fun AttachmentInfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun downloadAttachmentFile(
    context: Context, attachment: Attachment, token: String?, hostUrl: String
) {
    val url = AttachmentManager.getAttachmentUrl(hostUrl, attachment) ?: return
    try {
        var request = DownloadManager.Request(url.toUri()).setTitle(attachment.filename)
            .setDescription(context.getString(R.string.attachments_download_started))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, attachment.filename)
//            .addRequestHeader("Authorization", "Bearer $token")
        if (token != null) request = request.addRequestHeader("Authorization", "Bearer $token")
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(
            context, context.getString(R.string.attachments_download_started), Toast.LENGTH_SHORT
        ).show()
    } catch (e: Exception) {
        Log.e("AttachmentCard", "Download failed", e)
        val message = context.getString(R.string.common_operation_failed)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
fun mutableLongPositionOf() = remember { mutableLongStateOf(0L) }
