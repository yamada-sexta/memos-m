package org.example.memosm.ui.nav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.ui.component.setting.AboutAppCard
import org.example.memosm.ui.component.setting.AppSettingsCard
import org.example.memosm.ui.component.setting.AuditLogCard
import org.example.memosm.ui.component.setting.OfflineSettingsCard
import org.example.memosm.ui.component.setting.RecoveryCard
import org.example.memosm.ui.component.setting.SettingsCard
import org.example.memosm.ui.component.setting.ShortcutsCard
import org.example.memosm.ui.component.setting.WebhooksCard
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MemosViewModel,
    onBack: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    rememberScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) }
    )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = 96.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val itemModifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()

            // General
            item {
                SettingsGroupHeader(
                    title = stringResource(R.string.settings_group_general),
                    modifier = itemModifier
                )
            }

            item {
                Box(itemModifier) {
                    SettingsCard(
                        settings = uiState.session.userSettings ?: UserGeneralSetting(),
                        onUpdate = { locale, visibility ->
                            viewModel.userDelegate.updateUserGeneralSetting(locale, visibility)
                        })
                }
            }

            item {
                Box(itemModifier) {
                    AppSettingsCard(
                        pageSize = uiState.appSettings.pageSize,
                        onPageSizeChange = { viewModel.appSettingsDelegate.updatePageSize(it) },
                        headerScale = uiState.appSettings.headerScale,
                        onHeaderScaleChange = {
                            viewModel.appSettingsDelegate.updateHeaderScale(it)
                        })
                }
            }

            // Shortcuts & webhooks
            item {
                SettingsGroupHeader(
                    title = stringResource(R.string.settings_group_content),
                    modifier = itemModifier
                )
            }

            item {
                Box(itemModifier) {
                    ShortcutsCard(
                        shortcuts = uiState.userMemoList.shortcuts,
                        onCreate = { title, filter, onSuccess, onError ->
                            viewModel.shortcutDelegate.createShortcut(
                                title, filter, onSuccess, onError
                            )
                        },
                        onUpdate = { shortcut, title, filter, onSuccess, onError ->
                            viewModel.shortcutDelegate.updateShortcut(
                                shortcut, title, filter, onSuccess, onError
                            )
                        },
                        onDelete = { shortcut ->
                            viewModel.shortcutDelegate.deleteShortcut(shortcut)
                        })
                }
            }

            item {
                Box(itemModifier) {
                    WebhooksCard(
                        webhooks = uiState.session.webhooks,
                        onCreate = { displayName, url, onSuccess, onError ->
                            viewModel.webhookDelegate.createWebhook(
                                displayName, url, onSuccess, onError
                            )
                        },
                        onUpdate = { webhook, displayName, url, onSuccess, onError ->
                            viewModel.webhookDelegate.updateWebhook(
                                webhook, displayName, url, onSuccess, onError
                            )
                        },
                        onDelete = { webhook -> viewModel.webhookDelegate.deleteWebhook(webhook) })
                }
            }

            // Sync & cache
            item {
                SettingsGroupHeader(
                    title = stringResource(R.string.settings_group_sync_cache),
                    modifier = itemModifier
                )
            }

            item {
                Box(itemModifier) {
                    OfflineSettingsCard(
                        preDownloadText = uiState.appSettings.preDownloadText,
                        onPreDownloadTextChange = {
                            viewModel.appSettingsDelegate.updatePreDownloadText(it)
                        },
                        preDownloadAttachments = uiState.appSettings.preDownloadAttachments,
                        onPreDownloadAttachmentsChange = {
                            viewModel.appSettingsDelegate.updatePreDownloadAttachments(it)
                        },
                        preDownloadWifiOnly = uiState.appSettings.preDownloadWifiOnly,
                        onPreDownloadWifiOnlyChange = {
                            viewModel.appSettingsDelegate.updatePreDownloadWifiOnly(it)
                        },
                        preDownloadExplore = uiState.appSettings.preDownloadExplore,
                        onPreDownloadExploreChange = {
                            viewModel.appSettingsDelegate.updatePreDownloadExplore(it)
                        },
                        textCacheMaxMb = uiState.appSettings.textCacheMaxMb,
                        onTextCacheMaxMbChange = {
                            viewModel.appSettingsDelegate.updateTextCacheMaxMb(it)
                        },
                        attachmentCacheMaxMb = uiState.appSettings.attachmentCacheMaxMb,
                        onAttachmentCacheMaxMbChange = {
                            viewModel.appSettingsDelegate.updateAttachmentCacheMaxMb(it)
                        },
                        themeCacheMaxMb = uiState.appSettings.themeCacheMaxMb,
                        onThemeCacheMaxMbChange = {
                            viewModel.appSettingsDelegate.updateThemeCacheMaxMb(it)
                        },
                        textCacheCount = uiState.textCacheCount,
                        attachmentCacheUsage = uiState.attachmentCacheUsage,
                        onClearTextCache = { viewModel.clearTextCache() },
                        onClearAttachmentCache = { viewModel.clearAttachmentCache() })
                }
            }

            item {
                Box(itemModifier) {
                    RecoveryCard()
                }
            }

            item {
                Box(itemModifier) {
                    AuditLogCard()
                }
            }

            // Instance & about
            item {
                SettingsGroupHeader(
                    title = stringResource(R.string.settings_group_about),
                    modifier = itemModifier
                )
            }

            item {
                Box(itemModifier) {
                    InstanceCard(uiState.session.instanceProfile ?: InstanceProfile())
                }
            }

            item {
                Box(itemModifier) {
                    AboutAppCard()
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier.padding(top = 8.dp, start = 4.dp)
    )
}
