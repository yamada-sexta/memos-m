package org.example.memosm.ui.component.setting

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.data.audit.SyncAuditEntry
import org.example.memosm.data.audit.SyncAuditLogger
import org.koin.core.context.GlobalContext
import java.text.DateFormat
import java.util.Date

/**
 * Read-only viewer for the persistent sync audit log written by
 * [SyncAuditLogger]. Shows the most recent entries in a dialog; account and
 * target identifiers are already stored as short privacy hashes and are
 * displayed as-is.
 */
@Composable
fun AuditLogCard() {
    // Same Koin access pattern as RecoveryCard.
    val auditLogger: SyncAuditLogger = remember { GlobalContext.get().get() }
    val entries by remember { auditLogger.observeRecent(MAX_ENTRIES) }
        .collectAsState(initial = emptyList())

    var showDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(LogCategory.ALL) }
    val filteredEntries = entries.filter { selectedCategory.matches(it) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.audit_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.audit_log_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { showDialog = true },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.List,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.audit_log_view))
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.audit_log_title)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LogCategory.entries.forEach { category ->
                            FilterChip(
                                selected = category == selectedCategory,
                                onClick = { selectedCategory = category },
                                label = { Text(stringResource(category.labelRes)) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (filteredEntries.isEmpty()) {
                        Text(stringResource(R.string.audit_log_empty))
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                        ) {
                            items(filteredEntries, key = { it.id }) { entry ->
                                AuditLogItem(entry)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
}

@Composable
private fun AuditLogItem(entry: SyncAuditEntry) {
    ListItem(
        headlineContent = { Text("${entry.event} · ${entry.outcome}") },
        supportingContent = {
            Column {
                Text(formatAuditTime(entry.occurredAt))
                if (!entry.operation.isNullOrBlank() || !entry.detailCode.isNullOrBlank()) {
                    Text(
                        listOfNotNull(entry.operation, entry.detailCode)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    )
                }
                if (entry.accountHash.isNotBlank()) {
                    Text(stringResource(R.string.audit_log_account, entry.accountHash))
                }
                if (!entry.targetHash.isNullOrBlank()) {
                    Text(stringResource(R.string.audit_log_target, entry.targetHash))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun formatAuditTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

/**
 * Filter categories for the sync log dialog, matched against
 * [SyncAuditEntry.event]. Unknown events fall back to [SYSTEM].
 */
private enum class LogCategory(val labelRes: Int) {
    ALL(R.string.log_filter_all),
    SYNC(R.string.log_filter_sync),
    QUEUE(R.string.log_filter_queue),
    ATTACHMENT(R.string.log_filter_attachment),
    RECOVERY(R.string.log_filter_recovery),
    SYSTEM(R.string.log_filter_system);

    fun matches(entry: SyncAuditEntry): Boolean =
        this == ALL || this == categoryOf(entry.event)

    private companion object {
        fun categoryOf(event: String): LogCategory = when (event) {
            "QUEUE" -> QUEUE
            "SYNC", "WORKER" -> SYNC
            "ATTACHMENT_UPLOAD" -> ATTACHMENT
            "EXPORT", "IMPORT" -> RECOVERY
            else -> SYSTEM // DATABASE_CORRUPTED and anything unknown
        }
    }
}

private const val MAX_ENTRIES = 100
