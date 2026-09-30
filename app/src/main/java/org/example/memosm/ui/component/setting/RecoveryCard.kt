package org.example.memosm.ui.component.setting

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.audit.LocalRecoveryService
import org.example.memosm.ui.formatBytes
import org.koin.core.context.GlobalContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Local backup & restore settings: exports the cached memos and the offline
 * outbox of the active account to a user-picked file (SAF) via
 * [LocalRecoveryService], imports/restores such archives again, and lists the
 * archives already present in the protected recovery directory as the
 * disaster/corruption restore entry point. Feedback is shown via Toasts (the
 * pattern used elsewhere in the app) and a small result dialog for import
 * summaries and validation errors.
 */
@Composable
fun RecoveryCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Same Koin access pattern as OutboxSyncWorker / MemosApplication.
    val recoveryService: LocalRecoveryService = remember { GlobalContext.get().get() }
    val dataStoreManager: DataStoreManager = remember { GlobalContext.get().get() }

    val activeAccount by dataStoreManager.account.collectAsState(initial = null)
    val activeAccountId = activeAccount?.id

    var archives by remember { mutableStateOf<List<File>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf<String?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var deleteCandidate by remember { mutableStateOf<File?>(null) }
    var importCandidate by remember { mutableStateOf<File?>(null) }

    // Strings captured for coroutine/SAF callbacks (stringResource is
    // composable-only).
    val exportedMessage = stringResource(R.string.recovery_exported)
    val exportFailedMessage = stringResource(R.string.recovery_export_failed)
    val invalidFileMessage = stringResource(R.string.recovery_invalid_file)
    val noAccountMessage = stringResource(R.string.recovery_no_account)
    val importSummaryTitle = stringResource(R.string.recovery_import_summary_title)
    val importErrorTitle = stringResource(R.string.recovery_import_error_title)
    val unknownError = stringResource(R.string.recovery_unknown_error)

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun refreshArchives() {
        scope.launch {
            archives = withContext(Dispatchers.IO) { listRecoveryArchives(recoveryService) }
        }
    }

    /** Copies the export produced by the service to the SAF destination, then drops the private source file. */
    suspend fun copyToDestination(source: File, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(destination)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        } finally {
            source.delete()
        }
    }

    /** Copies a SAF-picked file into the recovery directory so importFile() accepts it as a source. */
    suspend fun copyIntoRecoveryDir(source: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val directory = recoveryService.recoveryDirectory().apply { mkdirs() }
            val target = File(directory, "import-${System.currentTimeMillis()}.json")
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            target
        } catch (_: Exception) {
            null
        }
    }

    fun runImport(source: File, deleteAfterwards: Boolean) {
        val accountId = activeAccountId
        if (accountId.isNullOrBlank()) {
            toast(noAccountMessage)
            if (deleteAfterwards) source.delete()
            return
        }
        busy = true
        scope.launch {
            try {
                val result = recoveryService.importFile(accountId, source)
                if (deleteAfterwards) withContext(Dispatchers.IO) { source.delete() }
                dialogText = result.fold(
                    onSuccess = {
                        importSummaryTitle + "\n" + context.getString(
                            R.string.recovery_import_summary,
                            it.memoCount,
                            it.pendingOpCount
                        )
                    },
                    onFailure = {
                        importErrorTitle + "\n" + (it.message ?: unknownError)
                    }
                )
                refreshArchives()
            } finally {
                busy = false
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri == null) {
            source?.delete()
            return@rememberLauncherForActivityResult
        }
        if (source == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val ok = copyToDestination(source, uri)
            busy = false
            refreshArchives()
            toast(if (ok) exportedMessage else exportFailedMessage)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val copy = copyIntoRecoveryDir(uri)
            if (copy == null) {
                busy = false
                toast(invalidFileMessage)
            } else {
                runImport(copy, deleteAfterwards = true)
            }
        }
    }

    LaunchedEffect(activeAccountId) { refreshArchives() }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.recovery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.recovery_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                OutlinedButton(
                    onClick = {
                        val accountId = activeAccountId
                        if (accountId.isNullOrBlank()) {
                            toast(noAccountMessage)
                            return@OutlinedButton
                        }
                        busy = true
                        scope.launch {
                            try {
                                val result = recoveryService.exportAccount(accountId)
                                result.fold(
                                    onSuccess = {
                                        pendingExport = it
                                        exportLauncher.launch(it.name)
                                    },
                                    onFailure = { toast(exportFailedMessage) }
                                )
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Outlined.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.recovery_export))
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.recovery_import))
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                stringResource(R.string.recovery_archives_title),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (archives.isEmpty()) {
                Text(
                    stringResource(R.string.recovery_archives_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(archives, key = { it.absolutePath }) { archive ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    archive.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            supportingContent = {
                                Text(formatArchiveMeta(archive))
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { importCandidate = archive },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            Icons.Outlined.Download,
                                            contentDescription = stringResource(R.string.recovery_archive_import)
                                        )
                                    }
                                    IconButton(
                                        onClick = { deleteCandidate = archive },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.common_delete),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }

            if (busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    deleteCandidate?.let { archive ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.recovery_delete_title)) },
            text = { Text(stringResource(R.string.recovery_delete_message, archive.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteCandidate = null
                        scope.launch {
                            withContext(Dispatchers.IO) { archive.delete() }
                            refreshArchives()
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    importCandidate?.let { archive ->
        AlertDialog(
            onDismissRequest = { importCandidate = null },
            title = { Text(stringResource(R.string.recovery_import_confirm_title)) },
            text = { Text(stringResource(R.string.recovery_import_confirm_message, archive.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        importCandidate = null
                        runImport(archive, deleteAfterwards = false)
                    }
                ) {
                    Text(stringResource(R.string.recovery_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { importCandidate = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    dialogText?.let { message ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            confirmButton = {
                TextButton(onClick = { dialogText = null }) {
                    Text(stringResource(R.string.common_close))
                }
            },
            text = { Text(message) }
        )
    }
}

private suspend fun listRecoveryArchives(service: LocalRecoveryService): List<File> =
    withContext(Dispatchers.IO) {
        service.recoveryDirectory()
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

private fun formatArchiveMeta(file: File): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(file.lastModified()))
    return "${formatBytes(file.length())} · $date"
}
