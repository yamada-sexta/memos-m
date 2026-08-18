package org.example.memosm.ui.component.setting

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Balance
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.net.toUri
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import org.example.memosm.R
import org.example.memosm.ui.nav.InfoRow

private data class KaomojiMessage(val text: String, val kaomoji: String)

@Suppress("LocalContextGetResourceValueCall")
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AboutAppCard() {
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
    var showLicensesDialog by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            currentToast.value?.cancel()
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Text(
                stringResource(R.string.profile_about),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))

            InfoRow(
                stringResource(R.string.profile_about_version),
                versionName,
                modifier = Modifier
                    .combinedClickable(onClick = {
                        currentToast.value?.cancel()
                        val item = kaomojis.randomOrNull()
                        if (item != null) {
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
                    .padding(horizontal = 16.dp, vertical = 8.dp)
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
                modifier = Modifier.combinedClickable(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, repoUrl.toUri())
                        context.startActivity(intent)
                    }),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_about_issues)) },
                leadingContent = { Icon(Icons.Outlined.BugReport, contentDescription = null) },
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null
                    )
                },
                modifier = Modifier.combinedClickable(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, issuesUrl.toUri())
                        context.startActivity(intent)
                    }),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )

            ListItem(
                headlineContent = { Text(stringResource(R.string.profile_about_licenses)) },
                leadingContent = { Icon(Icons.Outlined.Balance, contentDescription = null) },
                modifier = Modifier.combinedClickable(
                    onClick = { showLicensesDialog = true }),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
    }

    if (showLicensesDialog) {
        Dialog(
            onDismissRequest = { showLicensesDialog = false },
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.profile_about_licenses),
                            style = MaterialTheme.typography.titleLarge
                        )
                        IconButton(onClick = { showLicensesDialog = false }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                    val libs by produceLibraries()
                    libs?.let {
                        LibrariesContainer(
                            it, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

