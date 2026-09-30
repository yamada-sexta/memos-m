package org.example.memosm.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QuestionMark
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import org.example.memosm.R
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.model.Visibility
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun getVisibilityLabel(visibility: Visibility): String {
    return when (visibility) {
        Visibility.PUBLIC -> stringResource(R.string.memo_visibility_public)
        Visibility.PROTECTED -> stringResource(R.string.memo_visibility_protected)
        Visibility.PRIVATE -> stringResource(R.string.memo_visibility_private)
        Visibility.VISIBILITY_UNSPECIFIED -> stringResource(R.string.memo_visibility_unspecified)
    }
}

fun getVisibilityIcon(visibility: Visibility, outlined: Boolean = true): ImageVector {
    return when (visibility) {
        Visibility.PUBLIC -> if (outlined) Icons.Outlined.Public else Icons.Filled.Public
        Visibility.PROTECTED -> if (outlined) Icons.Outlined.People else Icons.Filled.People
        Visibility.PRIVATE -> if (outlined) Icons.Outlined.Lock else Icons.Filled.Lock
        Visibility.VISIBILITY_UNSPECIFIED -> if (outlined) Icons.Outlined.QuestionMark else Icons.Filled.QuestionMark
    }
}

@Composable
fun VisibilityIcon(
    modifier: Modifier = Modifier,
    visibility: Visibility,
    outlined: Boolean = true,
    tint: Color = LocalContentColor.current
) {
    // Centralized logic for icon and description
    val icon = getVisibilityIcon(visibility, outlined)
    val label = getVisibilityLabel(visibility)

    Icon(
        imageVector = icon, contentDescription = label, modifier = modifier, tint = tint
    )
}

/**
 * Helper to find the Activity from a Context.
 */
fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

fun getFileSize(context: Context, uri: android.net.Uri): Long {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (it.moveToFirst()) {
            if (!it.isNull(sizeIndex)) {
                it.getLong(sizeIndex)
            } else {
                0L
            }
        } else {
            0L
        }
    } ?: 0L
}

/** Shared byte-size formatting (sync panel, cache cleanup, recovery archives). */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.getDefault(), "%.2f GB", gb)
}

/**
 * Shared last-sync timestamp formatting. [pattern] defaults to the detail
 * panel's "MM-dd HH:mm"; the compact status bar passes "HH:mm".
 */
fun formatSyncTime(timestamp: Long, pattern: String = "MM-dd HH:mm"): String {
    if (timestamp <= 0L) return "-"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}

/** Shared label for the pre-download phase shown in the sync status UIs. */
@Composable
fun preDownloadPhaseLabel(phase: PreDownloadState.Running.Phase): String = stringResource(
    if (phase == PreDownloadState.Running.Phase.TEXT) {
        R.string.sync_status_phase_text
    } else {
        R.string.sync_status_phase_attachments
    }
)