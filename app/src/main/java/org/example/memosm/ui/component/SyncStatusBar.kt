package org.example.memosm.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.ui.formatSyncTime
import org.example.memosm.ui.preDownloadPhaseLabel

/**
 * Always-visible, lightweight sync/cache status row shown at the top of the
 * memo list. It surfaces the single state resolved by [syncStatusOf] (offline
 * first, then syncing/pre-download/pending, idle last) — the same precedence
 * the status icon button and detail panel use, so the indicators can never
 * disagree about the same state. The idle branch shows the cached count and
 * last sync time.
 *
 * Deliberately quiet (secondary colors, small text) so it informs without
 * competing with the content below it.
 */
@Composable
fun SyncStatusBar(
    isOnline: Boolean,
    isSyncing: Boolean,
    pendingOpsCount: Int,
    preDownloadState: PreDownloadState,
    cachedCount: Int,
    lastSyncTime: Long,
    modifier: Modifier = Modifier
) {
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    val textStyle = MaterialTheme.typography.labelSmall
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    val status = syncStatusOf(isOnline, isSyncing, preDownloadState, pendingOpsCount)
    val (icon, text) = when (status) {
        SyncStatus.SYNCING -> {
            Icons.Outlined.Sync to stringResource(R.string.sync_status_syncing)
        }

        SyncStatus.PREDOWNLOAD -> {
            val phase = preDownloadPhaseLabel(
                (preDownloadState as PreDownloadState.Running).phase
            )
            Icons.Outlined.Download to stringResource(
                R.string.sync_status_predownload, phase
            )
        }

        SyncStatus.PENDING -> {
            Icons.Outlined.CloudSync to stringResource(
                R.string.sync_status_pending, pendingOpsCount
            )
        }

        SyncStatus.OFFLINE -> {
            Icons.Outlined.CloudOff to stringResource(R.string.sync_status_offline)
        }

        SyncStatus.IDLE -> {
            Icons.Outlined.Storage to stringResource(
                R.string.sync_status_cached, cachedCount, formatSyncTime(lastSyncTime, "HH:mm")
            )
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = textStyle,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
