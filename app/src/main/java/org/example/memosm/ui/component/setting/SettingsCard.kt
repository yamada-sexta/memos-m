package org.example.memosm.ui.component.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.Visibility
import org.example.memosm.ui.getVisibilityLabel

private val LANGUAGE_NAMES = mapOf(
    "ar" to "العربية",
    "ca" to "Català",
    "cs" to "Čeština",
    "de" to "Deutsch",
    "en" to "English",
    "en-GB" to "English (UK)",
    "es" to "Español",
    "fa" to "فارسی",
    "fr" to "Français",
    "gl" to "Galego",
    "hi" to "हिन्दी",
    "hr" to "Hrvatski",
    "hu" to "Magyar",
    "id" to "Bahasa Indonesia",
    "it" to "Italiano",
    "ja" to "日本語",
    "ka-GE" to "ქართული",
    "ko" to "한국어",
    "mr" to "मराठी",
    "nb" to "Norsk bokmål",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "pt-PT" to "Português",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "sl" to "Slovenščina",
    "sv" to "Svenska",
    "th" to "ไทย",
    "tr" to "Türkçe",
    "uk" to "Українська",
    "vi" to "Tiếng Việt",
    "zh-Hans" to "简体中文",
    "zh-Hant" to "繁體中文"
)

@Composable
fun SettingsCard(settings: UserGeneralSetting, onUpdate: (String?, Visibility?) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Locale
        SettingsSelectionItem(
            label = stringResource(R.string.profile_settings_locale),
            currentValue = settings.locale ?: "en",
            options = LANGUAGE_NAMES.keys.toList(),
            labelProvider = { LANGUAGE_NAMES[it] ?: it },
            onSelect = { onUpdate(it, null) })

        // Memo Visibility
        SettingsSelectionItem(
            label = stringResource(R.string.profile_settings_visibility),
            currentValue = if (settings.memoVisibility == null) "PRIVATE" else settings.memoVisibility.toString(),
            options = listOf("PRIVATE", "PROTECTED", "PUBLIC"),
            labelProvider = { getVisibilityLabel(visibility = Visibility.valueOf(it)) },
            onSelect = { onUpdate(null, Visibility.valueOf(it)) })
    }
}

@Composable
private fun SettingsSelectionItem(
    label: String,
    currentValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    labelProvider: @Composable (String) -> String = { it }
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }

    ListItem(
        content = { Text(label) },
        supportingContent = {
            Text(
                text = labelProvider(currentValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { showDialog = true },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    )

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = label) },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(options) { option ->
                        val isSelected = option == currentValue
                        ListItem(
                            content = {
                                Text(
                                    labelProvider(option),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            }, leadingContent = {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Spacer(modifier = Modifier.width(24.dp))
                                }
                            }, modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onSelect(option)
                                    showDialog = false
                                }, colors = ListItemDefaults.colors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(
                                    alpha = 0.3f
                                )
                                else Color.Transparent
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }
}
