package org.example.memosm.ui.component.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import android.os.Build
import kotlinx.coroutines.launch
import org.example.memosm.ui.component.LocalNetworkPermission
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.example.memosm.R

@Composable
fun AppSettingsCard(
    pageSize: Int,
    onPageSizeChange: (Int) -> Unit,
    headerScale: Float,
    onHeaderScaleChange: (Float) -> Unit
) {
    var showPageSizeDialog by remember { mutableStateOf(false) }
    val networkPermission = LocalNetworkPermission.current
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.profile_app_settings),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (Build.VERSION.SDK_INT >= 37) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.local_network_permission_title)) },
                    supportingContent = {
                        Text(stringResource(
                            if (networkPermission.granted) R.string.local_network_permission_allowed
                            else R.string.local_network_permission_description
                        ))
                    },
                    trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                    modifier = Modifier.clickable {
                        if (networkPermission.granted) networkPermission.openSettings()
                        else scope.launch { networkPermission.requestAccess() }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_app_settings_page_size)) },
                supportingContent = {
                    Text(
                        text = pageSize.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { showPageSizeDialog = true },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_app_settings_header_scale)) },
                supportingContent = {
                    Column {
                        Text(
                            text = String.format(
                                stringResource(R.string.profile_app_settings_header_scale_format),
                                headerScale
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = headerScale,
                            onValueChange = onHeaderScaleChange,
                            valueRange = 0.5f..2.0f,
                            steps = 14
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    if (showPageSizeDialog) {
        var textValue by remember { mutableStateOf(pageSize.toString()) }
        var isError by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showPageSizeDialog = false },
            title = { Text(stringResource(R.string.profile_app_settings_page_size)) },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { newValue ->
                        textValue = newValue
                        val parsed = newValue.toIntOrNull()
                        isError = parsed == null || parsed < 1
                    },
                    label = { Text(stringResource(R.string.profile_app_settings_page_size_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = isError,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = textValue.toIntOrNull()
                        if (parsed != null && parsed >= 1) {
                            onPageSizeChange(parsed)
                            showPageSizeDialog = false
                        }
                    },
                    enabled = !isError && textValue.isNotBlank()
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPageSizeDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
