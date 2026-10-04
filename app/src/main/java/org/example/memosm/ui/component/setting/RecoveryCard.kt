package org.example.memosm.ui.component.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.memosm.R
import org.example.memosm.api.GsonProvider
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.backup.*
import org.example.memosm.model.Draft
import org.example.memosm.model.Memo
import org.example.memosm.ui.formatBytes
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.UUID

@Composable
fun RecoveryCard(importOnly: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { GlobalContext.get().get<BackupService>() }
    val settings = remember { GlobalContext.get().get<DataStoreManager>() }
    val drafts = remember { GlobalContext.get().get<DraftManager>() }
    val accounts by settings.accounts.collectAsState(initial = emptyList())
    val activeId = accounts.firstOrNull { it.isActive }?.id
    val reviewFlow = remember(activeId) { activeId?.let { settings.restoredEdits(it) } ?: flowOf(com.google.gson.JsonArray()) }
    val reviews by reviewFlow.collectAsState(initial = com.google.gson.JsonArray())
    var archives by remember { mutableStateOf(emptyList<File>()) }
    var busy by remember { mutableStateOf(false) }
    var deleteArchive by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val savedDraft = stringResource(R.string.backup_edit_saved_draft)
    fun refresh() { scope.launch(Dispatchers.IO) {
        val files = service.recoveryDirectory().listFiles { file -> file.isFile && file.extension.lowercase() in setOf("json", BackupArchive.EXTENSION) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        withContext(Dispatchers.Main) { archives = files }
    } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { refresh() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsGroup {
            if (!importOnly) SettingsNavigationRow(title = stringResource(R.string.recovery_export), summary = stringResource(R.string.backup_export_description),
                icon = Icons.Outlined.FileUpload, showChevron = false, enabled = !busy, onClick = {
                    context.startActivity(org.example.memosm.ui.backup.BackupTransferActivity.exportIntent(context))
                })
            SettingsNavigationRow(title = stringResource(R.string.recovery_import), summary = stringResource(R.string.backup_import_description),
                icon = Icons.Outlined.FileDownload, showChevron = false, enabled = !busy,
                onClick = { context.startActivity(org.example.memosm.ui.backup.BackupTransferActivity.importIntent(context)) })
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!importOnly) {
            Text(stringResource(R.string.recovery_archives_title), style = MaterialTheme.typography.titleSmall)
            if (archives.isEmpty()) Text(stringResource(R.string.recovery_archives_empty), style = MaterialTheme.typography.bodySmall)
            archives.forEach { archive ->
                ListItem(headlineContent = { Text(archive.name) }, supportingContent = { Text(formatBytes(archive.length())) },
                    leadingContent = { TextButton(enabled = !busy, onClick = { context.startActivity(org.example.memosm.ui.backup.BackupTransferActivity.importIntent(context, archive.absolutePath)) }) { Text(stringResource(R.string.recovery_import)) } },
                    trailingContent = { TextButton(enabled = !busy, onClick = { deleteArchive = archive }) { Text(stringResource(R.string.common_delete)) } })
            }
            if (reviews.size() > 0) {
                Text(stringResource(R.string.backup_edit_review_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.backup_edit_review_description), style = MaterialTheme.typography.bodySmall)
                reviews.forEach { value ->
                    val edit = value.asJsonObject
                    val payload = edit.getAsJsonObject("payload")
                    val canDraft = edit.get("type").asString in setOf("CREATE", "UPDATE", "COMMENT_CREATE") && payload != null
                    ListItem(headlineContent = { Text("${edit.get("type").asString} · ${edit.get("memoName")?.takeUnless { it.isJsonNull }?.asString.orEmpty()}") },
                        supportingContent = { Text(payload?.get("content")?.takeUnless { it.isJsonNull }?.asString ?: edit.get("parentName")?.takeUnless { it.isJsonNull }?.asString.orEmpty()) },
                        trailingContent = { Column {
                            if (canDraft) TextButton(enabled = !busy, onClick = {
                                val accountId = activeId ?: return@TextButton
                                busy = true
                                scope.launch {
                                    try {
                                        val memo = GsonProvider.gson.fromJson(payload, Memo::class.java)
                                        val draftId = "restored-${edit.get("id").asString}"
                                        drafts.replaceDrafts(accountId, drafts.getDrafts(accountId).filterNot { it.id == draftId } + Draft(id = draftId, content = memo.content,
                                            visibility = memo.visibility ?: org.example.memosm.model.Visibility.PRIVATE, attachments = memo.attachments.orEmpty(), location = memo.location))
                                        settings.dismissRestoredEdit(accountId, edit.get("id").asString)
                                        message = savedDraft
                                    } catch (error: Exception) { message = error.message }
                                    finally { busy = false }
                                }
                            }) { Text(stringResource(R.string.backup_save_as_draft)) }
                            TextButton(enabled = !busy, onClick = { val accountId = activeId ?: return@TextButton; scope.launch { settings.dismissRestoredEdit(accountId, edit.get("id").asString) } }) { Text(stringResource(R.string.common_delete)) }
                        } })
                }
            }
        }
    }

    deleteArchive?.let { archive -> AlertDialog(onDismissRequest = { deleteArchive = null }, title = { Text(stringResource(R.string.recovery_delete_title)) },
        text = { Text(stringResource(R.string.recovery_delete_message, archive.name)) },
        confirmButton = { TextButton(onClick = { deleteArchive = null; scope.launch(Dispatchers.IO) { archive.delete(); refresh() } }) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deleteArchive = null }) { Text(stringResource(R.string.common_cancel)) } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = {
        TextButton(onClick = { message = null }) { Text(stringResource(R.string.common_close)) }
    }) }
}

/** Full-screen Import/Export selection; the Settings backup page stays in place. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupTransferScreen(exporting: Boolean, sourcePath: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { GlobalContext.get().get<BackupService>() }
    val settings = remember { GlobalContext.get().get<DataStoreManager>() }
    val accounts by settings.accounts.collectAsState(initial = emptyList())
    var busy by remember { mutableStateOf(false) }
    var exportPage by remember { mutableStateOf(exporting) }
    var chosenAccounts by remember { mutableStateOf(emptySet<String>()) }
    var chosenCategories by remember { mutableStateOf(BackupCategory.entries.toSet() - BackupCategory.QUEUED_EDITS) }
    var encrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var repeatPassword by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<File?>(null) }
    var importSource by remember { mutableStateOf<File?>(null) }
    var deleteSource by remember { mutableStateOf(false) }
    var prepared by remember { mutableStateOf<BackupService.PreparedBackup?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val exported = stringResource(R.string.recovery_exported)
    val failedExport = stringResource(R.string.recovery_export_failed)
    val summary = stringResource(R.string.backup_restore_summary)
    val legacyExplanation = stringResource(R.string.backup_legacy_explanation)

    fun closeImport() {
        prepared?.close(); prepared = null
        if (deleteSource) importSource?.delete()
        importSource = null; password = ""; askPassword = false
    }
    fun inspect(source: File, secret: CharArray? = null) {
        busy = true
        scope.launch {
            try {
                service.inspect(source, secret).fold(onSuccess = { backup ->
                    prepared?.close(); prepared = backup; askPassword = false; password = ""
                    chosenAccounts = backup.manifest.accounts.map { it.identity.id }.toSet()
                    chosenCategories = backup.manifest.categories - BackupCategory.QUEUED_EDITS
                }, onFailure = { error ->
                    if (error is PasswordRequiredException || error is BackupPasswordException) {
                        askPassword = true
                        if (error is BackupPasswordException) message = error.message
                    } else { message = error.message; closeImport() }
                })
            } finally { secret?.fill('\u0000'); busy = false }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupArchive.MIME_TYPE)) { destination ->
        val source = pendingExport
        pendingExport = null
        if (destination == null || source == null) { source?.delete(); onClose(); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(destination, "wt")?.use { output -> source.inputStream().use { it.copyTo(output) } }
                        ?: error("Could not open backup destination")
                }
                android.widget.Toast.makeText(context, exported, android.widget.Toast.LENGTH_LONG).show()
                onClose()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { message = failedExport }
            finally { withContext(Dispatchers.IO) { source.delete() }; busy = false }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { onClose(); return@rememberLauncherForActivityResult }
        busy = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = File(service.recoveryDirectory(), "import-${UUID.randomUUID()}.mmbackup")
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                var bytes = 0L
                                while (true) {
                                    val n = input.read(buffer); if (n < 0) break
                                    bytes += n
                                    require(bytes <= BackupArchive.MAX_ARCHIVE_BYTES && target.parentFile!!.usableSpace > n + 1024 * 1024) { "Backup is too large or storage is full" }
                                    output.write(buffer, 0, n)
                                }
                                output.fd.sync()
                            }
                        } ?: error("Could not open backup")
                        target
                    } catch (error: Exception) { target.delete(); throw error }
                }
                closeImport(); importSource = file; deleteSource = true
                inspect(file)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message = error.message; busy = false }
        }
    }

    LaunchedEffect(Unit) {
        chosenAccounts = settings.getAccounts().map { it.id }.toSet()
        if (!exporting) {
            if (sourcePath == null) importLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
            else {
                val file = File(sourcePath).canonicalFile
                if (file.parentFile == service.recoveryDirectory().canonicalFile && file.isFile) {
                    importSource = file; deleteSource = false; inspect(file)
                } else message = "Could not open backup"
            }
        }
    }
    androidx.activity.compose.BackHandler { if (!busy) onClose() }
    DisposableEffect(Unit) {
        onDispose {
            prepared?.close()
            pendingExport?.delete()
            if (deleteSource) importSource?.delete()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (exporting) R.string.backup_choose_export else R.string.backup_choose_restore)) },
            navigationIcon = { IconButton(enabled = !busy, onClick = onClose) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.backup_back))
            } })
    }) { padding ->
    if (exportPage || prepared != null) {
        val importing = prepared != null
        val available = prepared?.manifest?.categories ?: BackupCategory.entries.toSet()
        val identities = prepared?.manifest?.accounts?.map { it.identity } ?: accounts.map { BackupIdentity(it.id, it.hostUrl, it.user?.name, it.displayName ?: it.name ?: it.hostUrl) }
                Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.backup_accounts_heading), style = MaterialTheme.typography.titleSmall)
                        identities.forEach { identity ->
                            SelectionRow(identity.label, identity.id in chosenAccounts, !busy) { checked ->
                                chosenAccounts = if (checked) chosenAccounts + identity.id else chosenAccounts - identity.id
                            }
                        }
                        Text(stringResource(R.string.backup_contents_heading), style = MaterialTheme.typography.titleSmall)
                        available.forEach { category ->
                            SelectionRow(stringResource(categoryLabel(category)), category in chosenCategories, !busy) { checked ->
                                chosenCategories = if (checked) chosenCategories + category else chosenCategories - category
                            }
                        }
                        if (BackupCategory.QUEUED_EDITS in chosenCategories) Text(stringResource(R.string.backup_edit_review_description), style = MaterialTheme.typography.bodySmall)
                        if (prepared?.manifest?.legacy == true) Text(legacyExplanation, style = MaterialTheme.typography.bodySmall)
                        if (!importing) {
                            SelectionRow(stringResource(R.string.backup_password_protection), encrypted, !busy) { encrypted = it }
                            if (encrypted) {
                                OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !busy, label = { Text(stringResource(R.string.backup_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                                OutlinedTextField(value = repeatPassword, onValueChange = { repeatPassword = it }, enabled = !busy, label = { Text(stringResource(R.string.backup_repeat_password)) }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                            }
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(enabled = !busy, onClick = { closeImport(); onClose() }) { Text(stringResource(R.string.common_cancel)) }
                        Button(enabled = !busy && chosenCategories.isNotEmpty() && (chosenAccounts.isNotEmpty() || chosenCategories == setOf(BackupCategory.SETTINGS)) &&
                            (importing || !encrypted || (password.isNotEmpty() && password == repeatPassword)), onClick = {
                            if (importing) confirmRestore = true
                            else {
                                busy = true
                                val secret = if (encrypted) password.toCharArray() else null
                                scope.launch {
                                    try {
                                        service.export(BackupSelection(chosenAccounts, chosenCategories), secret).fold(onSuccess = { file ->
                                            pendingExport = file; exportPage = false; password = ""; repeatPassword = ""; exportLauncher.launch(file.name)
                                        }, onFailure = { message = it.message ?: failedExport })
                                    } finally { secret?.fill('\u0000'); busy = false }
                                }
                            }
                        }) { Text(stringResource(if (importing) R.string.recovery_import else R.string.recovery_export)) }
                    }
                }
    } else {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (busy) CircularProgressIndicator()
        }
    }
    }
    if (askPassword) AlertDialog(onDismissRequest = { if (!busy) { closeImport(); onClose() } }, title = { Text(stringResource(R.string.backup_password)) },
        text = { OutlinedTextField(value = password, onValueChange = { password = it }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy) },
        confirmButton = { TextButton(enabled = !busy && password.isNotEmpty(), onClick = { importSource?.let { inspect(it, password.toCharArray()) } }) { Text(stringResource(R.string.recovery_import)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = { closeImport(); onClose() }) { Text(stringResource(R.string.common_cancel)) } })
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, title = { Text(stringResource(R.string.backup_replace_title)) },
        text = { Column { Text(stringResource(R.string.backup_replace_description)); chosenCategories.forEach { Text(stringResource(categoryLabel(it))) } } },
        confirmButton = { TextButton(onClick = {
            confirmRestore = false; busy = true
            val backup = prepared ?: return@TextButton
            scope.launch {
                try {
                    withContext(NonCancellable) {
                        service.restore(backup, RestoreSelection(chosenAccounts, chosenCategories)).fold(onSuccess = {
                            val resultText = summary.format(it.accountCount, it.memoCount, it.draftCount, it.mediaCount)
                            android.widget.Toast.makeText(context.applicationContext, resultText, android.widget.Toast.LENGTH_LONG).show()
                            closeImport(); onClose()
                        }, onFailure = { message = it.message })
                    }
                } finally { busy = false }
            }
        }) { Text(stringResource(R.string.recovery_import)) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.common_cancel)) } })
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = {
        TextButton(onClick = { message = null; if (prepared == null && !exportPage && !askPassword) onClose() }) { Text(stringResource(R.string.common_close)) }
    }) }
}

@Composable
private fun SelectionRow(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private fun categoryLabel(category: BackupCategory): Int = when (category) {
    BackupCategory.ACCOUNTS -> R.string.backup_category_accounts
    BackupCategory.SETTINGS -> R.string.backup_category_settings
    BackupCategory.CACHE -> R.string.backup_category_cache
    BackupCategory.MEDIA -> R.string.backup_category_media
    BackupCategory.DRAFTS -> R.string.backup_category_drafts
    BackupCategory.QUEUED_EDITS -> R.string.backup_category_queued
}
