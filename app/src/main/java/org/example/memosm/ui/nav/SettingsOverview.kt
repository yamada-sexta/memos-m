package org.example.memosm.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import org.example.memosm.R
import org.example.memosm.ui.component.setting.SettingsGroup

enum class SettingsSection(
    @StringRes val titleRes: Int,
    val icon: ImageVector,
    @StringRes val summaryRes: Int? = null
) {
    GENERAL(R.string.settings_group_general, Icons.Outlined.Settings, R.string.settings_summary_general),
    APPEARANCE(R.string.settings_appearance, Icons.Outlined.Palette, R.string.settings_summary_appearance),
    SHORTCUTS(R.string.profile_shortcuts, Icons.Outlined.Bookmarks),
    WEBHOOKS(R.string.profile_webhooks, Icons.Outlined.Link),
    OFFLINE(R.string.offline_settings_title, Icons.Outlined.CloudDownload, R.string.settings_summary_offline),
    RECOVERY(R.string.recovery_title, Icons.Outlined.ImportExport, R.string.settings_summary_recovery),
    AUDIT(R.string.audit_log_title, Icons.Outlined.History),
    ABOUT(R.string.profile_about, Icons.Outlined.Info),
    INSTANCE(R.string.profile_instance_info, Icons.Outlined.Dns)
}

@Composable
internal fun SettingsOverview(onOpenSection: (SettingsSection) -> Unit, modifier: Modifier = Modifier) {
    val groups = listOf(
        listOf(SettingsSection.GENERAL, SettingsSection.APPEARANCE),
        listOf(SettingsSection.OFFLINE, SettingsSection.RECOVERY)
    )
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        groups.forEach { group ->
            item(key = group.first().name) {
                Box(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    SettingsGroup {
                        group.forEach { section ->
                            ListItem(
                                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onOpenSection(section) },
                                headlineContent = { Text(stringResource(section.titleRes)) },
                                supportingContent = section.summaryRes?.let { { Text(stringResource(it)) } },
                                leadingContent = {
                                    Surface(
                                        modifier = Modifier.size(40.dp),
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(section.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            )
                        }
                    }
                }
            }
        }
    }
}
