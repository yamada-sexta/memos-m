package org.example.memosm.ui.component.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import org.example.memosm.R

private data class KaomojiMessage(val text: String, val kaomoji: String)

@Suppress("LocalContextGetResourceValueCall")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AboutAppCard(onOpenLicenses: () -> Unit, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: stringResource(R.string.common_not_available)
    val versionLabel = stringResource(R.string.profile_about_version)
    val versionCopiedMessage = stringResource(R.string.profile_about_version_copied)

    val kaomojisArray = stringArrayResource(R.array.profile_about_kaomojis)
    val kaomojis = remember(kaomojisArray) {
        kaomojisArray.mapNotNull { item ->
            val parts = item.split("|")
            if (parts.size == 2) {
                KaomojiMessage(parts[0], parts[1])
            } else {
                null
            }
        }
    }

    val currentToast = remember { mutableStateOf<Toast?>(null) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var showMemoCatch by rememberSaveable { mutableStateOf(false) }

    if (showMemoCatch) {
        MemoCatchDialog(onDismiss = { showMemoCatch = false })
    }

    DisposableEffect(Unit) {
        onDispose {
            currentToast.value?.cancel()
        }
    }

    SettingsGroup {
        ListItem(
            headlineContent = { Text(stringResource(R.string.profile_about_version)) },
            supportingContent = { Text(versionName) },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .combinedClickable(onClick = {
                    currentToast.value?.cancel()
                    versionTaps++
                    if (versionTaps == 10) {
                        versionTaps = 0
                        showMemoCatch = true
                    } else kaomojis.randomOrNull()?.let { item ->
                        val toast = Toast.makeText(
                            context, "${item.text} ${item.kaomoji}", Toast.LENGTH_SHORT
                        )
                        currentToast.value = toast
                        toast.show()
                    }
                }, onLongClick = {
                    val clipboard =
                        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(versionLabel, versionName)
                    clipboard.setPrimaryClip(clip)

                    currentToast.value?.cancel()
                    val toast =
                        Toast.makeText(context, versionCopiedMessage, Toast.LENGTH_SHORT)
                    currentToast.value = toast
                    toast.show()
                })
        )

        val repoUrl = stringResource(R.string.profile_about_repo_url)
        val issuesUrl = stringResource(R.string.profile_about_issues_url)

        ListItem(
            headlineContent = { Text(stringResource(R.string.profile_about_repo)) },
            leadingContent = { Icon(Icons.Outlined.Code, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null
                )
            },
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, repoUrl.toUri())
                    context.startActivity(intent)
                }),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.profile_about_issues)) },
            leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null
                )
            },
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, issuesUrl.toUri())
                    context.startActivity(intent)
                }),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )

        SettingsNavigationRow(
            title = stringResource(R.string.audit_log_title),
            icon = Icons.Outlined.History,
            onClick = onOpenLogs
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.profile_about_licenses)) },
            leadingContent = { Icon(Icons.Outlined.Balance, contentDescription = null) },
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).combinedClickable(
                onClick = onOpenLicenses),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        )
    }

}
