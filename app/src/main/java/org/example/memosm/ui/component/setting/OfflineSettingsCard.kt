package org.example.memosm.ui.component.setting

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.memosm.ui.component.item.media.MediaCache
import org.example.memosm.ui.formatBytes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.example.memosm.R
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.ui.component.CacheCleanupDialog
import java.util.Locale

/** Storage overview with grouped download preferences and cache limit editors. */
@Composable
fun OfflineSettingsCard(
    preDownloadText: Boolean,
    onPreDownloadTextChange: (Boolean) -> Unit,
    preDownloadAttachments: Boolean,
    onPreDownloadAttachmentsChange: (Boolean) -> Unit,
    preDownloadWifiOnly: Boolean,
    onPreDownloadWifiOnlyChange: (Boolean) -> Unit,
    preDownloadExplore: Boolean,
    onPreDownloadExploreChange: (Boolean) -> Unit,
    textCacheMaxMb: Int,
    onTextCacheMaxMbChange: (Int) -> Unit,
    attachmentCacheMaxMb: Int,
    onAttachmentCacheMaxMbChange: (Int) -> Unit,
    themeCacheMaxMb: Int,
    onThemeCacheMaxMbChange: (Int) -> Unit,
    textCacheCount: Int,
    attachmentCacheUsage: AttachmentCacheManager.Usage,
    onClearTextCache: () -> Unit,
    onClearAttachmentCache: () -> Unit
) {
    var showCleanup by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var mediaBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(attachmentCacheUsage, showCleanup) {
        mediaBytes = withContext(Dispatchers.IO) { MediaCache.sizeBytes(context) }
    }
    val fileBytes = mediaBytes?.let { it + attachmentCacheUsage.bytes }
    val fileLimitBytes = if (attachmentCacheMaxMb > 0 && themeCacheMaxMb > 0) {
        (attachmentCacheMaxMb.toLong() + themeCacheMaxMb) * 1024L * 1024L
    } else null

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = fileBytes?.let(::formatBytes) ?: "…",
                style = MaterialTheme.typography.displayMedium
            )
            Text(
                text = stringResource(R.string.settings_cache_files_used),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (fileLimitBytes != null && fileBytes != null) {
                LinearProgressIndicator(
                    progress = { (fileBytes.toFloat() / fileLimitBytes).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(32.dp),
                    gapSize = 2.dp
                )
                Text(
                    text = stringResource(R.string.settings_cache_files_limit, formatBytes(fileLimitBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        FilledTonalButton(
            onClick = { showCleanup = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cache_cleanup_title))
        }
        SettingsGroup {
            CacheUsageRow(
                label = stringResource(R.string.cache_cleanup_attachments),
                bytes = attachmentCacheUsage.bytes,
                limitMb = attachmentCacheMaxMb
            )
            CacheUsageRow(
                label = stringResource(R.string.cache_cleanup_media),
                bytes = mediaBytes,
                limitMb = themeCacheMaxMb
            )
            ListItem(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)),
                headlineContent = { Text(stringResource(R.string.cache_cleanup_text)) },
                supportingContent = { Text(stringResource(R.string.offline_settings_text_count, textCacheCount)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
        SettingsLabel(stringResource(R.string.settings_cache_downloads))
        SettingsGroup {
            SettingSwitchRow(
                label = stringResource(R.string.offline_settings_pre_download_text),
                checked = preDownloadText,
                onCheckedChange = onPreDownloadTextChange
            )
            SettingSwitchRow(
                label = stringResource(R.string.offline_settings_pre_download_attachments),
                checked = preDownloadAttachments,
                onCheckedChange = onPreDownloadAttachmentsChange
            )
            SettingSwitchRow(
                label = stringResource(R.string.offline_settings_wifi_only),
                checked = preDownloadWifiOnly,
                onCheckedChange = onPreDownloadWifiOnlyChange
            )
            SettingSwitchRow(
                label = stringResource(R.string.offline_settings_pre_download_explore),
                checked = preDownloadExplore,
                onCheckedChange = onPreDownloadExploreChange
            )
        }
        SettingsLabel(stringResource(R.string.settings_cache_limits))
        SettingsGroup {
            CacheLimitRow(
                label = stringResource(R.string.offline_settings_cache_size_text),
                valueMb = textCacheMaxMb,
                onChange = onTextCacheMaxMbChange
            )
            CacheLimitRow(
                label = stringResource(R.string.offline_settings_cache_size),
                valueMb = attachmentCacheMaxMb,
                onChange = onAttachmentCacheMaxMbChange
            )
            CacheLimitRow(
                label = stringResource(R.string.offline_settings_cache_size_theme),
                valueMb = themeCacheMaxMb,
                onChange = onThemeCacheMaxMbChange
            )
        }
    }

    if (showCleanup) {
        CacheCleanupDialog(
            textCacheCount = textCacheCount,
            attachmentUsage = attachmentCacheUsage,
            onClearText = onClearTextCache,
            onClearAttachment = onClearAttachmentCache,
            onDismiss = { showCleanup = false }
        )
    }
}

@Composable
private fun SettingsLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun CacheUsageRow(label: String, bytes: Long?, limitMb: Int) {
    ListItem(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)),
        headlineContent = { Text(label) },
        trailingContent = { Text(bytes?.let(::formatBytes) ?: "…") },
        supportingContent = if (limitMb > 0 && bytes != null) {
            {
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / (limitMb.toLong() * 1024L * 1024L)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        } else null,
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

@Composable
private fun CacheLimitRow(label: String, valueMb: Int, onChange: (Int) -> Unit) {
    var showEditor by rememberSaveable { mutableStateOf(false) }
    SettingsNavigationRow(
        title = label,
        summary = if (valueMb <= 0) stringResource(R.string.cache_cleanup_unlimited)
            else formatBytes(valueMb.toLong() * 1024L * 1024L),
        onClick = { showEditor = true }
    )
    if (showEditor) {
        var draft by rememberSaveable { mutableStateOf(valueMb) }
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(label) },
            text = { CacheLimitEditor(label = label, valueMb = draft, onChange = { draft = it }) },
            confirmButton = {
                TextButton(onClick = { onChange(draft); showEditor = false }) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditor = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** Display unit for a cache limit; values are stored as whole MB. */
private enum class CacheUnit(val label: String, val bytesPerUnit: Long) {
    MB("MB", 1024L * 1024L),
    GB("GB", 1024L * 1024L * 1024L);

    /** [value] in this unit -> whole MB (rounded up so nothing is under-allocated). */
    fun toMb(value: Double): Int = ceil(value * bytesPerUnit / (1024.0 * 1024.0)).toInt()

    /** [valueMb] whole MB -> display string in this unit. */
    fun fromMb(valueMb: Int): String = when (this) {
        MB -> valueMb.toString()
        GB -> String.format(Locale.getDefault(), "%.1f", valueMb / 1024.0)
    }
}

/**
 * One cache tier: a single full-width editable number field whose dropdown
 * holds both the preset sizes and the unit switch (MB/GB/unlimited — KB is
 * not offered because limits are stored as whole MB, so a KB input would
 * always snap up to 1024 KB). One field avoids the cramped two-field row
 * that overflowed on small widths (Material3 fields enforce a 280dp
 * minimum, so a fixed-width unit picker next to a weighted field gets
 * squeezed off-screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CacheLimitEditor(
    label: String,
    valueMb: Int,
    onChange: (Int) -> Unit
) {
    var unit by remember { mutableStateOf(CacheUnit.MB) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val unlimited = valueMb <= 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = if (unlimited) "" else unit.fromMb(valueMb),
                onValueChange = { text ->
                    text.toDoubleOrNull()?.takeIf { it > 0 }?.let { onChange(unit.toMb(it)) }
                },
                singleLine = true,
                label = {
                    Text(
                        if (unlimited) {
                            stringResource(R.string.cache_cleanup_unlimited)
                        } else {
                            stringResource(R.string.offline_settings_cache_size_hint)
                        }
                    )
                },
                suffix = {
                    if (!unlimited) Text(unit.label)
                },
                trailingIcon = {
                    IconButton(onClick = { expanded = true }) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(R.string.offline_settings_custom)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                // Tall enough to show every preset + unit choice without
                // scrolling (the default caps the viewport at ~4 items).
                modifier = Modifier.heightIn(max = 560.dp)
            ) {
                listOf(50L, 100L, 250L, 500L, 1024L, 2048L).forEach { presetMb ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (presetMb >= 1024) {
                                    "${presetMb / 1024} GB"
                                } else {
                                    "$presetMb MB"
                                }
                            )
                        },
                        onClick = {
                            unit = CacheUnit.MB
                            onChange(presetMb.toInt())
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cache_unit_mb)) },
                    onClick = { unit = CacheUnit.MB; expanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cache_unit_gb)) },
                    onClick = { unit = CacheUnit.GB; expanded = false }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.cache_cleanup_unlimited)) },
                    onClick = {
                        unit = CacheUnit.MB
                        onChange(0)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingToggleRow(label = label, checked = checked, onCheckedChange = onCheckedChange)
}
