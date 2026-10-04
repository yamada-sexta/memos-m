package org.example.memosm.ui.component.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsNavigationRow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    supportingContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    ListItem(
        modifier = modifier.clip(RoundedCornerShape(4.dp)).clickable(enabled = enabled, onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = supportingContent ?: summary?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null) } },
        trailingContent = if (showChevron) {
            { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }
        } else null,
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            leadingIconColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            trailingIconColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    )
}
