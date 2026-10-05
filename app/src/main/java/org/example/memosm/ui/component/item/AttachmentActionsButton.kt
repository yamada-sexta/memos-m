package org.example.memosm.ui.component.item

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.example.memosm.R
import org.example.memosm.model.Attachment
import org.example.memosm.ui.component.ActionSheet
import org.example.memosm.ui.component.ActionSheetItem
import org.example.memosm.ui.component.item.media.LocalViewerGesturesBlocked
import org.example.memosm.viewmodel.manager.AttachmentManager

/** The card and full-screen viewer share actions, availability, and download confirmation. */
@Composable
internal fun AttachmentActionsButton(
    attachment: Attachment?,
    token: String?,
    hostUrl: String,
    filename: String,
    onShowInfo: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    var showMenu by remember(attachment, filename) { mutableStateOf(false) }
    var showDownloadDialog by remember(attachment, filename) { mutableStateOf(false) }
    val openWebUrl = remember(attachment, hostUrl) {
        AttachmentManager.getAttachmentUrl(hostUrl, attachment)
    }
    val errorOpenLinkString = stringResource(R.string.attachments_error_open_link)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.size(32.dp)
    ) {
        IconButton(onClick = { showMenu = true }, enabled = enabled && !LocalViewerGesturesBlocked.current) {
            Icon(Icons.Outlined.MoreVert, stringResource(R.string.memo_action_more), Modifier.size(20.dp))
        }
    }

    if (showMenu) {
        ActionSheet(onDismissRequest = { showMenu = false }) {
            ActionSheetItem(
                text = { Text(stringResource(R.string.attachments_info_title)) },
                onClick = { showMenu = false; onShowInfo() },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Info, contentDescription = null
                    )
                })
            ActionSheetItem(
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
                ActionSheetItem(
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

                ActionSheetItem(
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
        val message = context.getString(R.string.attachments_error_download_failed)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
