package org.example.memosm.ui.component.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import org.example.memosm.R
import org.example.memosm.data.media.AttachmentCacheManager
import org.example.memosm.ui.component.CacheCleanupDialog
import java.util.Locale

/**
 * Offline & pre-download settings: toggles for automatic text/attachment
 * pre-download (with a Wi-Fi-only option), per-tier cache size limits (each
 * an editable dropdown with presets, MB/GB units and "unlimited"),
 * a cache-analysis summary and a single clear button that opens the shared
 * CacheCleanupDialog (the same one the sync status panel uses).
 */
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
    var showCleanup by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.offline_settings_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

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

            Spacer(modifier = Modifier.height(8.dp))
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

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { showCleanup = true }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.cache_cleanup_title))
                }
            }
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
private fun CacheLimitRow(
    label: String,
    valueMb: Int,
    onChange: (Int) -> Unit
) {
    var unit by remember { mutableStateOf(CacheUnit.MB) }
    var expanded by remember { mutableStateOf(false) }
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
                    .menuAnchor()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
