package org.example.memosm.ui.nav

import android.os.Build

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.memosm.R
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.ui.component.ProfileBackButton
import org.example.memosm.ui.component.setting.*
import org.example.memosm.viewmodel.MemosViewModel

@Composable
fun SettingsScreen(
    viewModel: MemosViewModel,
    onBack: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onOpenLicenses: () -> Unit,
    section: SettingsSection? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (section == SettingsSection.OFFLINE) {
                LargeTopAppBar(
                    title = { Text(stringResource(section.titleRes)) },
                    navigationIcon = { ProfileBackButton(onClick = onBack) },
                    scrollBehavior = scrollBehavior
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(section?.titleRes ?: R.string.settings_title)) },
                    navigationIcon = { ProfileBackButton(onClick = onBack) }
                )
            }
        }
    ) { innerPadding ->
        if (section == null) {
            SettingsOverview(onOpenSection = onOpenSection, modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (section) {
                    SettingsSection.GENERAL -> settingsItem {
                        SettingsGroup {
                            SettingsCard(
                                settings = uiState.session.userSettings ?: UserGeneralSetting(),
                                onUpdate = { locale, visibility ->
                                    viewModel.userDelegate.updateUserGeneralSetting(locale, visibility)
                                }
                            )
                            AppPreferenceRows(viewModel, AppSettingsCategory.GENERAL)
                            if (Build.VERSION.SDK_INT >= 37) {
                                AppPreferenceRows(viewModel, AppSettingsCategory.NETWORK)
                            }
                        }
                    }
                    SettingsSection.APPEARANCE -> settingsItem {
                        SettingsGroup { AppPreferenceRows(viewModel, AppSettingsCategory.APPEARANCE) }
                    }
                    SettingsSection.CONTENT -> {
                        settingsItem {
                            SettingsGroup {
                                SettingsNavigationRow(
                                    title = stringResource(R.string.profile_shortcuts),
                                    summary = if (uiState.userMemoList.shortcuts.isEmpty()) {
                                        stringResource(R.string.profile_shortcuts_none)
                                    } else stringResource(R.string.settings_shortcuts_summary),
                                    onClick = { onOpenSection(SettingsSection.SHORTCUTS) }
                                )
                                SettingsNavigationRow(
                                    title = stringResource(R.string.profile_webhooks),
                                    summary = if (uiState.session.webhooks.isEmpty()) {
                                        stringResource(R.string.profile_webhooks_none)
                                    } else stringResource(R.string.settings_webhooks_summary),
                                    onClick = { onOpenSection(SettingsSection.WEBHOOKS) }
                                )
                            }
                        }
                    }
                    SettingsSection.SHORTCUTS -> {
                        settingsItem {
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
                                }
                            )
                        }
                    }
                    SettingsSection.WEBHOOKS -> {
                        settingsItem {
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
                                onDelete = { webhook -> viewModel.webhookDelegate.deleteWebhook(webhook) }
                            )
                        }
                    }
                    SettingsSection.OFFLINE -> settingsItem {
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
                            onClearAttachmentCache = { viewModel.clearAttachmentCache() }
                        )
                    }
                    SettingsSection.RECOVERY -> settingsItem { RecoveryCard() }
                    SettingsSection.AUDIT -> settingsItem { AuditLogCard() }
                    SettingsSection.ABOUT -> settingsItem { AboutAppCard(onOpenLicenses) }
                    SettingsSection.INSTANCE -> settingsItem {
                        InstanceCard(uiState.session.instanceProfile ?: InstanceProfile())
                    }
                }
            }
        }
    }
}

private fun LazyListScope.settingsItem(content: @Composable () -> Unit) {
    item {
        Box(Modifier.widthIn(max = 600.dp).fillMaxWidth()) { content() }
    }
}

@Composable
private fun AppPreferenceRows(viewModel: MemosViewModel, category: AppSettingsCategory) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AppSettingsCard(
        pageSize = uiState.appSettings.pageSize,
        onPageSizeChange = { viewModel.appSettingsDelegate.updatePageSize(it) },
        headerScale = uiState.appSettings.headerScale,
        linkPreviewEnabled = uiState.appSettings.linkPreviewEnabled,
        onLinkPreviewEnabledChange = viewModel.appSettingsDelegate::updateLinkPreviewEnabled,
        onHeaderScaleChange = {
            viewModel.appSettingsDelegate.updateHeaderScale(it)
        },
        category = category
    )
}
