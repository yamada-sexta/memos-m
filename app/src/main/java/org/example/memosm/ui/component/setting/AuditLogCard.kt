package org.example.memosm.ui.component.setting

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.memosm.R
import org.example.memosm.data.audit.SyncAuditEntry
import org.example.memosm.data.audit.SyncAuditLogger
import org.koin.core.context.GlobalContext
import java.text.DateFormat
import java.util.Date

/** Read-only, inline viewer for the persistent sync audit log. */
@Composable
fun AuditLogContent(modifier: Modifier = Modifier) {
    val auditLogger: SyncAuditLogger = remember { GlobalContext.get().get() }
    val entries by remember(auditLogger) { auditLogger.observeRecent(MAX_ENTRIES) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedCategory by rememberSaveable { mutableStateOf(LogCategory.ALL) }
    val filteredEntries = entries.filter { selectedCategory.matches(it) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "filters") {
            Row(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
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
        }
        if (filteredEntries.isEmpty()) {
            item(key = "empty") {
                Box(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                    SettingsGroup {
                        ListItem(
                            content = { Text(stringResource(R.string.audit_log_empty)) },
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                    }
                }
            }
        } else {
            itemsIndexed(filteredEntries, key = { _, entry -> entry.id }) { index, entry ->
                val first = index == 0
                val last = index == filteredEntries.lastIndex
                AuditLogItem(
                    entry = entry,
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth()
                        .padding(bottom = if (last) 0.dp else 2.dp)
                        .clip(RoundedCornerShape(
                            topStart = if (first) 28.dp else 4.dp,
                            topEnd = if (first) 28.dp else 4.dp,
                            bottomStart = if (last) 28.dp else 4.dp,
                            bottomEnd = if (last) 28.dp else 4.dp
                        ))
                )
            }
        }
    }
}

@Composable
private fun AuditLogItem(entry: SyncAuditEntry, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        content = { Text("${entry.event} · ${entry.outcome}") },
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
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    )
}

private fun formatAuditTime(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

/**
 * Filter categories for the sync log, matched against
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
