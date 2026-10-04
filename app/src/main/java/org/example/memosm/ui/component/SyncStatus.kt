package org.example.memosm.ui.component

import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.ConnectionState

enum class SyncStatus { SYNCING, PREDOWNLOAD, PENDING, OFFLINE, RATE_LIMITED, IDLE }

/**
 * THE one place that decides which sync/cache state the UI surfaces, used by
 * both the icon button/panel (SyncStatusWidget) and the status row
 * (SyncStatusBar) so they can never disagree about the same uiState.
 *
 * Precedence: offline first. Being offline is the most truthful user-facing
 * fact — nothing can sync or pre-download while offline, and any queued
 * writes only matter because the device is offline — so it outranks
 * syncing/pre-download/pending, which are transient online states.
 */
fun syncStatusOf(
    isOnline: Boolean,
    isSyncing: Boolean,
    preDownloadState: PreDownloadState,
    pendingOpsCount: Int,
    connectionState: ConnectionState = ConnectionState.ONLINE
): SyncStatus = when {
    !isOnline -> SyncStatus.OFFLINE
    connectionState == ConnectionState.RATE_LIMITED -> SyncStatus.RATE_LIMITED
    isSyncing -> SyncStatus.SYNCING
    preDownloadState is PreDownloadState.Running -> SyncStatus.PREDOWNLOAD
    pendingOpsCount > 0 -> SyncStatus.PENDING
    else -> SyncStatus.IDLE
}

fun syncStatusOf(uiState: MemosUiState): SyncStatus = syncStatusOf(
    isOnline = uiState.isOnline,
    isSyncing = uiState.isSyncing,
    preDownloadState = uiState.preDownloadState,
    pendingOpsCount = uiState.pendingOpsCount,
    connectionState = uiState.connectionState
)
