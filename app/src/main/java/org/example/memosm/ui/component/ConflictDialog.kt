package org.example.memosm.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.data.sync.ConflictItem
import org.example.memosm.data.sync.ConflictResolution
import org.example.memosm.data.sync.DiffLineType
import org.example.memosm.data.sync.computeLineDiff
import org.example.memosm.ui.component.composer.MemoInput

private enum class ConflictDialogMode {
    VERSIONS, DIFF, MERGE
}

/**
 * Conflict resolution dialog: the server version of a memo was modified while
 * the user was offline. Lets the user:
 *  - see both versions (and a line-level diff between them),
 *  - keep local / keep server / defer (Later), or
 *  - edit a third merged version combining both sides and push it.
 *
 * [onResolve] receives the resolution; [ConflictResolution.MERGE] also carries
 * the user-edited merged content as the second argument.
 */
@Composable
fun ConflictDialog(
    conflict: ConflictItem,
    onResolve: (ConflictResolution, String?) -> Unit,
    onDismiss: () -> Unit
) {
    // Keyed on the conflict: if a new conflict arrives while the dialog is up
    // (the ViewModel never overwrites an unresolved one, but be safe), the mode
    // and the pre-filled merge text must reset instead of leaking from the old memo.
    var mode by remember(conflict) { mutableStateOf(ConflictDialogMode.VERSIONS) }
    var mergedText by remember(conflict) {
        mutableStateOf(TextFieldValue(conflict.localMemo.content))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when (mode) {
                    ConflictDialogMode.VERSIONS -> stringResource(R.string.offline_conflict_title)
                    ConflictDialogMode.DIFF -> stringResource(R.string.offline_conflict_diff_title)
                    ConflictDialogMode.MERGE -> stringResource(R.string.offline_conflict_merge_title)
                }
            )
        },
        text = {
            when (mode) {
                ConflictDialogMode.VERSIONS -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.offline_conflict_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        VersionPreview(
                            label = stringResource(R.string.offline_conflict_local),
                            content = conflict.localMemo.content
                        )
                        VersionPreview(
                            label = stringResource(R.string.offline_conflict_server),
                            content = conflict.serverMemo.content
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { mode = ConflictDialogMode.DIFF }) {
                                Text(stringResource(R.string.offline_conflict_view_diff))
                            }
                            TextButton(onClick = { mode = ConflictDialogMode.MERGE }) {
                                Text(stringResource(R.string.offline_conflict_edit_merged))
                            }
                        }
                    }
                }

                ConflictDialogMode.DIFF -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.offline_conflict_diff_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DiffList(conflict)
                    }
                }

                ConflictDialogMode.MERGE -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.offline_conflict_merge_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        ) {
                            MemoInput(
                                modifier = Modifier.fillMaxHeight(),
                                contentState = mergedText,
                                onContentChange = { mergedText = it },
                                placeholder = "",
                                availableTags = emptyMap()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (mode) {
                ConflictDialogMode.VERSIONS -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onResolve(ConflictResolution.KEEP_LOCAL, null) }) {
                            Text(stringResource(R.string.offline_conflict_keep_local))
                        }
                        TextButton(onClick = { onResolve(ConflictResolution.KEEP_SERVER, null) }) {
                            Text(stringResource(R.string.offline_conflict_keep_server))
                        }
                    }
                }

                ConflictDialogMode.DIFF -> {
                    TextButton(onClick = { mode = ConflictDialogMode.VERSIONS }) {
                        Text(stringResource(R.string.offline_conflict_back))
                    }
                }

                ConflictDialogMode.MERGE -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { mode = ConflictDialogMode.VERSIONS }) {
                            Text(stringResource(R.string.offline_conflict_back))
                        }
                        TextButton(
                            onClick = {
                                onResolve(ConflictResolution.MERGE, mergedText.text)
                            }
                        ) {
                            Text(stringResource(R.string.offline_conflict_save_merged))
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (mode == ConflictDialogMode.VERSIONS) {
                TextButton(onClick = { onResolve(ConflictResolution.LATER, null) }) {
                    Text(stringResource(R.string.offline_conflict_later))
                }
            }
        }
    )
}

@Composable
private fun VersionPreview(label: String, content: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = content.ifBlank { "-" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DiffList(conflict: ConflictItem) {
    val lines = remember(conflict) {
        computeLineDiff(conflict.localMemo.content, conflict.serverMemo.content)
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 320.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
    ) {
        items(lines) { line ->
            val background = when (line.type) {
                DiffLineType.REMOVED ->
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                DiffLineType.ADDED ->
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                DiffLineType.SAME -> Color.Transparent
            }
            val prefix = when (line.type) {
                DiffLineType.REMOVED -> "- "
                DiffLineType.ADDED -> "+ "
                DiffLineType.SAME -> "  "
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(background)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(
                    text = prefix + line.text.ifBlank { " " },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = when (line.type) {
                        DiffLineType.REMOVED -> MaterialTheme.colorScheme.onErrorContainer
                        DiffLineType.ADDED -> MaterialTheme.colorScheme.onPrimaryContainer
                        DiffLineType.SAME -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}
