package org.example.memosm.ui.component

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.ui.component.item.media.MediaCache
import org.example.memosm.ui.formatBytes

/**
 * Shared cache-cleanup dialog (used by the sync status panel and the offline
 * settings card): shows a per-type analysis (text / attachment / media cache)
 * with checkboxes, and clears exactly the selected types. The media cache is
 * cleared in-place here (it is the global Coil cache, no ViewModel involved),
 * so callers only pass the text/attachment actions.
 */
@Composable
fun CacheCleanupDialog(
    textCacheCount: Int,
    attachmentUsage: AttachmentCacheManager.Usage,
    onClearText: () -> Unit,
    onClearAttachment: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Media cache bytes change after every clear; recompute on open and after
    // clearing (cheap directory walk, off the main thread).
    var mediaCacheBytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) {
        mediaCacheBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(context) }
    }

    var clearText by remember { mutableStateOf(false) }
    var clearAttachments by remember { mutableStateOf(false) }
    var clearMedia by remember { mutableStateOf(false) }
    val anySelected = clearText || clearAttachments || clearMedia

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.cache_cleanup_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CleanupCheckRow(
                    label = stringResource(R.string.cache_cleanup_text),
                    size = stringResource(R.string.offline_settings_text_count, textCacheCount),
                    checked = clearText,
                    onCheckedChange = { clearText = it }
                )
                CleanupCheckRow(
                    label = stringResource(R.string.cache_cleanup_attachments),
                    size = formatBytes(attachmentUsage.bytes) +
                        if (attachmentUsage.count > 0) " · ${attachmentUsage.count}" else "",
                    checked = clearAttachments,
                    onCheckedChange = { clearAttachments = it }
                )
                CleanupCheckRow(
                    label = stringResource(R.string.cache_cleanup_media),
                    size = if (mediaCacheBytes >= 0) formatBytes(mediaCacheBytes) else "…",
                    checked = clearMedia,
                    onCheckedChange = { clearMedia = it }
                )
                if (!anySelected) {
                    Spacer(modifier = Modifier.padding(top = 12.dp))
                    Text(
                        stringResource(R.string.cache_cleanup_none_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Run every clear inside one coroutine and only dismiss on
                    // completion, so the dialog never leaves while its state is
                    // still being written and failures stay visible in the log.
                    scope.launch {
                        try {
                            if (clearText) onClearText()
                            if (clearAttachments) onClearAttachment()
                            if (clearMedia) {
                                withContext(Dispatchers.IO) { MediaCache.clear(context) }
                                mediaCacheBytes = 0L
                            }
                        } catch (e: Exception) {
                            Log.e("CacheCleanupDialog", "Failed to clear selected caches", e)
                        } finally {
                            onDismiss()
                        }
                    }
                },
                enabled = anySelected,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.cache_cleanup_clear_selected))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
private fun CleanupCheckRow(
    label: String,
    size: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = size,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

