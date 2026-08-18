package org.example.memosm.ui.component.setting

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.example.memosm.R
import org.example.memosm.model.Shortcut

@Composable
fun ShortcutsCard(
    shortcuts: List<Shortcut>,
    onCreate: (String, String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit,
    onUpdate: (Shortcut, String, String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit,
    onDelete: (Shortcut) -> Unit
) {
    var showEditDialog by remember { mutableStateOf<Shortcut?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<Shortcut?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.profile_shortcuts),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { showCreateDialog = true }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.profile_shortcuts_add)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            if (shortcuts.isEmpty()) {
                Text(
                    text = stringResource(R.string.profile_shortcuts_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            } else {
                shortcuts.forEachIndexed { index, shortcut ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = shortcut.title ?: "",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = shortcut.filter ?: "",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { showEditDialog = shortcut }) {
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = stringResource(R.string.memo_action_edit),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = { showDeleteConfirm = shortcut }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.memo_action_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    if (showCreateDialog) {
        ShortcutEditDialog(
            title = stringResource(R.string.profile_shortcuts_add),
            onDismiss = { showCreateDialog = false },
            onConfirm = { title, filter, onSuccess, onError ->
                onCreate(title, filter, onSuccess, onError)
            }
        )
    }

    showEditDialog?.let { shortcut ->
        ShortcutEditDialog(
            title = stringResource(R.string.profile_shortcuts_edit),
            initialTitle = shortcut.title ?: "",
            initialFilter = shortcut.filter ?: "",
            onDismiss = { showEditDialog = null },
            onConfirm = { title, filter, onSuccess, onError ->
                onUpdate(shortcut, title, filter, onSuccess, onError)
            }
        )
    }

    showDeleteConfirm?.let { shortcut ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text(stringResource(R.string.profile_shortcuts_delete_title)) },
            text = { Text(stringResource(R.string.profile_shortcuts_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(shortcut)
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
fun ShortcutEditDialog(
    title: String,
    initialTitle: String = "",
    initialFilter: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String, String, onSuccess: () -> Unit, onError: (Int) -> Unit) -> Unit
) {
    var titleText by remember { mutableStateOf(initialTitle) }
    var filterText by remember { mutableStateOf(initialFilter) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessageRes by remember { mutableStateOf<Int?>(null) }

    val context = LocalContext.current
    val helpUrl = stringResource(R.string.profile_shortcuts_help_url)

    AlertDialog(
        onDismissRequest = if (isSaving) ({}) else onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (errorMessageRes != null) {
                    Text(
                        text = stringResource(errorMessageRes ?: R.string.profile_shortcuts_error_save),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = titleText,
                    onValueChange = {
                        titleText = it
                        errorMessageRes = null
                    },
                    label = { Text(stringResource(R.string.profile_shortcuts_title)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isSaving
                )
                OutlinedTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                        errorMessageRes = null
                    },
                    label = { Text(stringResource(R.string.profile_shortcuts_filter)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("\"work\" in tags && has_task_list") },
                    enabled = !isSaving,
                    trailingIcon = {
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, helpUrl.toUri())
                            context.startActivity(intent)
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.HelpOutline,
                                contentDescription = stringResource(R.string.profile_shortcuts_help)
                            )
                        }
                    }
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
                        titleText,
                        filterText,
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
                enabled = titleText.isNotBlank() && filterText.isNotBlank() && !isSaving
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
