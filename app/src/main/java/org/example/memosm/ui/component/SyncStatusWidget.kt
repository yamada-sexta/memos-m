package org.example.memosm.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.sync.PendingOp
import org.example.memosm.data.sync.PendingOpType
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.ui.formatSyncTime
import org.example.memosm.ui.preDownloadPhaseLabel
import org.example.memosm.viewmodel.MemosUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status icon rendered inside the search bar's trailing-icon slot: a plain
 * icon button so it shares the search bar's internal vertical centering (no
 * separate Surface that can drift off the search bar's center line). One
 * glance tells the sync/cache state; tapping opens the detail panel.
 */
@Composable
fun SyncStatusIconButton(
    uiState: MemosUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val status = syncStatusOf(uiState)
    IconButton(onClick = onClick, modifier = modifier) {
        when (status) {
            SyncStatus.SYNCING, SyncStatus.PREDOWNLOAD -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            }

            SyncStatus.PENDING -> Icon(
                Icons.Outlined.CloudSync,
                contentDescription = stringResource(
                    R.string.sync_status_pending, uiState.pendingOpsCount
                ),
                tint = MaterialTheme.colorScheme.tertiary
            )

            SyncStatus.OFFLINE -> Icon(
                Icons.Outlined.CloudOff,
                contentDescription = stringResource(R.string.sync_status_offline),
                // Error red: the icon is the only persistent offline cue the
                // user asked to keep (the banner below the search bar stays
                // quiet), so it must stand out at a glance.
                tint = MaterialTheme.colorScheme.error
            )

            SyncStatus.IDLE -> Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(R.string.sync_status_idle),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Essential sync status, pending changes and cache actions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusPanel(
    uiState: MemosUiState,
    onDismiss: () -> Unit,
    onPreDownloadText: () -> Unit,
    onPreDownloadAttachments: () -> Unit,
    onDeleteOp: (String) -> Unit,
    onManageCache: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            // Main status row (larger than the old caption-sized row)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val status = syncStatusOf(uiState)
                val icon = when (status) {
                    SyncStatus.OFFLINE -> Icons.Outlined.CloudOff
                    SyncStatus.IDLE -> Icons.Outlined.CheckCircle
                    else -> Icons.Outlined.CloudSync
                }
                val statusText: String = when (status) {
                    SyncStatus.SYNCING ->
                        stringResource(R.string.offline_sync_status_syncing)
                    SyncStatus.PREDOWNLOAD -> {
                        val phase = (uiState.preDownloadState as PreDownloadState.Running).phase
                        stringResource(
                            R.string.sync_status_predownload,
                            preDownloadPhaseLabel(phase)
                        )
                    }
                    SyncStatus.PENDING ->
                        stringResource(R.string.sync_status_pending, uiState.pendingOpsCount)
                    SyncStatus.OFFLINE ->
                        stringResource(R.string.sync_status_offline)
                    SyncStatus.IDLE ->
                        stringResource(R.string.sync_status_idle)
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        statusText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = stringResource(
                            R.string.sync_panel_last_sync, formatSyncTime(uiState.lastSyncTime)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Pending ops queue
            if (uiState.pendingOps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    stringResource(R.string.sync_panel_queue, uiState.pendingOps.size),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                uiState.pendingOps.forEach { op ->
                    PendingOperationItem(
                        op = op,
                        onDelete = { onDeleteOp(op.id) }
                    )
                }
            }

            // Pre-download progress
            Spacer(modifier = Modifier.height(12.dp))
            when (val state = uiState.preDownloadState) {
                is PreDownloadState.Running -> {
                    Text(
                        text = stringResource(
                            R.string.sync_panel_predownload_running,
                            preDownloadPhaseLabel(state.phase),
                            state.page
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // Indeterminate: the total page count is unknown, and a
                    // "1/page" determinate value would walk BACKWARDS as pages
                    // accumulate, which reads as a broken progress bar.
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is PreDownloadState.Failed -> {
                    Text(
                        text = stringResource(R.string.offline_predownload_failed, state.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                else -> Unit
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPreDownloadAttachments,
                    enabled = uiState.isOnline,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).fillMaxHeight()
                ) {
                    Text(
                        stringResource(R.string.offline_predownload_attachments_button),
                        textAlign = TextAlign.Center
                    )
                }
                FilledTonalButton(
                    onClick = onPreDownloadText,
                    enabled = uiState.isOnline,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).fillMaxHeight()
                ) {
                    Text(
                        stringResource(R.string.offline_predownload_text_button),
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onManageCache,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                contentPadding = PaddingValues(16.dp)
            ) {
                Icon(Icons.Outlined.Storage, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    stringResource(R.string.sync_panel_manage_cache),
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
            }
        }
    }
}

@Composable
private fun PendingOperationItem(op: PendingOp, onDelete: () -> Unit) {
    var expanded by remember(op.id) { mutableStateOf(false) }
    // Deleting a queued op discards an unsynced user write, so it needs an
    // explicit confirmation (same AlertDialog pattern as RecoveryCard).
    var confirmDelete by remember(op.id) { mutableStateOf(false) }
    val target = op.memoName ?: op.parentName ?: "-"
    val payloadSummary = remember(op.payloadJson) { pendingPayloadSummary(op.payloadJson) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = if (op.permanentlyFailed) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 12.dp, top = 8.dp, end = 4.dp, bottom = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = opTypeLabel(op),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = payloadSummary ?: target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.sync_queue_collapse else R.string.sync_queue_expand
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.offline_sync_delete_op),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                PendingOperationDetail(
                    label = stringResource(R.string.sync_queue_target),
                    value = target
                )
                PendingOperationDetail(
                    label = stringResource(R.string.sync_queue_queued_at),
                    value = formatOperationTime(op.createdAt)
                )
                PendingOperationDetail(
                    label = stringResource(R.string.sync_queue_attempts),
                    value = op.attemptCount.toString()
                )
                if (op.lastAttemptAt > 0L) {
                    PendingOperationDetail(
                        label = stringResource(R.string.sync_queue_last_attempt),
                        value = formatOperationTime(op.lastAttemptAt)
                    )
                }
                if (!op.updateMask.isNullOrBlank()) {
                    PendingOperationDetail(
                        label = stringResource(R.string.sync_queue_fields),
                        value = op.updateMask
                    )
                }
                if (!op.lastError.isNullOrBlank()) {
                    PendingOperationDetail(
                        label = stringResource(R.string.sync_queue_error),
                        value = op.lastError,
                        isError = true
                    )
                }
                if (op.permanentlyFailed) {
                    Text(
                        text = stringResource(R.string.sync_queue_needs_attention),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.sync_queue_delete_title)) },
            text = { Text(stringResource(R.string.sync_queue_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.offline_sync_delete_op))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun PendingOperationDetail(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(92.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun pendingPayloadSummary(payloadJson: String?): String? {
    if (payloadJson.isNullOrBlank()) return null
    return runCatching {
        val payload = GsonProvider.gson.fromJson(payloadJson, com.google.gson.JsonObject::class.java)
        payload.get("content")?.takeUnless { it.isJsonNull }?.asString
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(120)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun formatOperationTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

@Composable
private fun opTypeLabel(op: PendingOp): String {
    val type = runCatching { PendingOpType.valueOf(op.type) }.getOrDefault(PendingOpType.UPDATE)
    return stringResource(
        when (type) {
            PendingOpType.CREATE -> R.string.offline_op_create
            PendingOpType.UPDATE -> R.string.offline_op_update
            PendingOpType.DELETE -> R.string.offline_op_delete
            PendingOpType.COMMENT_CREATE -> R.string.offline_op_comment_create
            PendingOpType.REACTION_UPSERT -> R.string.offline_op_reaction_upsert
            PendingOpType.REACTION_DELETE -> R.string.offline_op_reaction_delete
        }
    )
}
