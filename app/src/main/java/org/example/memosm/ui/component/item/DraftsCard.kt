package org.example.memosm.ui.component.item

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.viewmodel.MemosViewModel

/**
 * A card that shows a summary of saved drafts with actions to
 * open the drafts list, publish all, or delete all.
 */
@Composable
fun DraftsCard(
    draftCount: Int,
    viewModel: MemosViewModel,
    onCardClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showPublishAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        // Top: Draft info (clickable to open drafts screen)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onCardClick)
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column {
                Text(
                    text = stringResource(R.string.drafts_card_message),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = pluralStringResource(R.plurals.drafts_count_plural, draftCount, draftCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                )
            }
        }
        // Bottom: Action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showPublishAllDialog = true }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.common_publish))
            }
            TextButton(
                onClick = { showDeleteAllDialog = true },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.common_delete))
            }
        }
    }

    // Delete all confirmation dialog
    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text(stringResource(R.string.drafts_delete_all_confirmation_title)) },
            text = { Text(stringResource(R.string.drafts_delete_all_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.draftDelegate.deleteAllDrafts()
                        showDeleteAllDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }

    // Publish all confirmation dialog
    if (showPublishAllDialog) {
        AlertDialog(
            onDismissRequest = { showPublishAllDialog = false },
            title = { Text(stringResource(R.string.drafts_publish_all_confirmation_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.drafts_publish_all_confirmation_message_plural, draftCount, draftCount
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPublishAllDialog = false
                        viewModel.draftDelegate.publishAllDrafts { count ->
                            Toast.makeText(
                                context,
                                context.resources.getQuantityString(
                                    R.plurals.drafts_publish_all_success_plural,
                                    count,
                                    count
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_publish))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPublishAllDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }
}
