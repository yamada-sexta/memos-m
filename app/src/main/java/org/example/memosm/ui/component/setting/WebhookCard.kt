package org.example.memosm.ui.component.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.UserWebhook

@Composable
fun WebhooksCard(
    webhooks: List<UserWebhook>,
    onCreate: (displayName: String, url: String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit,
    onUpdate: (UserWebhook, displayName: String, url: String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit,
    onDelete: (UserWebhook) -> Unit
) {
    var showEditDialog by remember { mutableStateOf<UserWebhook?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<UserWebhook?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup {
            SettingsNavigationRow(
                title = stringResource(R.string.profile_webhooks_add),
                icon = Icons.Outlined.Add,
                showChevron = false,
                onClick = { showCreateDialog = true }
            )
        }
        if (webhooks.isEmpty()) {
            Text(
                text = stringResource(R.string.profile_webhooks_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        } else {
            SettingsGroup {
                webhooks.forEach { webhook ->
                    ListItem(
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { showEditDialog = webhook },
                        content = { Text(webhook.displayName ?: "") },
                        supportingContent = { Text(webhook.url, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        trailingContent = {
                            IconButton(onClick = { showDeleteConfirm = webhook }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.memo_action_delete),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        WebhookEditDialog(
            title = stringResource(R.string.profile_webhooks_add),
            onDismiss = { showCreateDialog = false },
            onConfirm = { displayName, url, onSuccess, onError ->
                onCreate(displayName, url, onSuccess, onError)
            }
        )
    }

    showEditDialog?.let { webhook ->
        WebhookEditDialog(
            title = stringResource(R.string.profile_webhooks_edit),
            initialDisplayName = webhook.displayName ?: "",
            initialUrl = webhook.url,
            onDismiss = { showEditDialog = null },
            onConfirm = { displayName, url, onSuccess, onError ->
                onUpdate(webhook, displayName, url, onSuccess, onError)
            }
        )
    }

    showDeleteConfirm?.let { webhook ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.profile_webhooks_delete_title)) },
            text = { Text(stringResource(R.string.profile_webhooks_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(webhook)
                        showDeleteConfirm = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
fun WebhookEditDialog(
    title: String,
    initialDisplayName: String = "",
    initialUrl: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit
) {
    var displayNameText by remember { mutableStateOf(initialDisplayName) }
    var urlText by remember { mutableStateOf(initialUrl) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessageRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = if (isSaving) ({}) else onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errorMessageRes != null) {
                    Text(
                        text = stringResource(errorMessageRes ?: R.string.profile_webhooks_error_save),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = displayNameText,
                    onValueChange = {
                        displayNameText = it
                        errorMessageRes = null
                    },
                    label = { Text(stringResource(R.string.profile_webhooks_display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = {
                        urlText = it
                        errorMessageRes = null
                    },
                    label = { Text(stringResource(R.string.profile_webhooks_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://example.com/webhook") },
                    enabled = !isSaving,
                    singleLine = true
                )

                if (isSaving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    isSaving = true
                    errorMessageRes = null
                    onConfirm(
                        displayNameText,
                        urlText,
                        {
                            isSaving = false
                            onDismiss()
                        },
                        { errorRes ->
                            isSaving = false
                            errorMessageRes = errorRes
                        }
                    )
                },
                enabled = displayNameText.isNotBlank() && urlText.isNotBlank() && !isSaving
            ) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSaving) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
