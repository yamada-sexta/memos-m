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
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    onShowSetup: () -> Unit,
    section: SettingsSection? = null,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    SettingsPageScaffold(
        title = stringResource(section?.titleRes ?: R.string.settings_title),
        onBack = onBack,
        collapsingHeader = section == SettingsSection.OFFLINE ||
            section == SettingsSection.AUDIT || section == SettingsSection.RECOVERY,
        modifier = modifier
    ) { innerPadding ->
        if (section == null) {
            SettingsOverview(onOpenSection = onOpenSection, modifier = Modifier.padding(innerPadding))
        } else if (section == SettingsSection.AUDIT) {
            AuditLogContent(modifier = Modifier.fillMaxSize().padding(innerPadding))
        } else if (section == SettingsSection.APPEARANCE) {
            AppearanceSettingsContent(
                headerScale = uiState.appSettings.headerScale,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                linkPreviewPreference = { AppPreferenceRows(viewModel, AppSettingsCategory.LINK_PREVIEWS) }
            ) {
                AppPreferenceRows(viewModel, AppSettingsCategory.APPEARANCE)
            }
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
                    SettingsSection.APPEARANCE -> Unit
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
                            preDownloadState = uiState.preDownloadState,
                            onCacheNow = viewModel::cacheNow,
                            onClearTextCache = { viewModel.clearTextCache() },
                            onClearAttachmentCache = { viewModel.clearAttachmentCache() }
                        )
                    }
                    SettingsSection.RECOVERY -> settingsItem { RecoveryCard() }
                    SettingsSection.AUDIT -> Unit
                    SettingsSection.ABOUT -> settingsItem {
                        AboutAppCard(
                            onOpenLicenses = onOpenLicenses,
                            onShowSetup = onShowSetup,
                            onOpenLogs = { onOpenSection(SettingsSection.AUDIT) }
                        )
                    }
                    SettingsSection.INSTANCE -> settingsItem {
                        InstanceCard(uiState.session.instanceProfile ?: InstanceProfile())
                    }
                }
            }
        }
    }
}

/** A collapsing header must own the scroll behavior, otherwise it consumes swipes indefinitely. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    collapsingHeader: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    val scrollBehavior = if (collapsingHeader) {
        val collapsedOffset = with(LocalDensity.current) {
            (TopAppBarDefaults.LargeAppBarCollapsedHeight - TopAppBarDefaults.LargeAppBarExpandedHeight).toPx()
        }
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            state = rememberTopAppBarState(
                initialHeightOffsetLimit = collapsedOffset,
                initialHeightOffset = collapsedOffset
            )
        )
    } else null
    Scaffold(
        modifier = modifier.fillMaxSize().then(
            scrollBehavior?.let { Modifier.nestedScroll(it.nestedScrollConnection) } ?: Modifier
        ),
        topBar = {
            if (scrollBehavior != null) {
                LargeTopAppBar(
                    title = { Text(title) },
                    navigationIcon = { ProfileBackButton(onClick = onBack) },
                    scrollBehavior = scrollBehavior
                )
            } else {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = { ProfileBackButton(onClick = onBack) }
                )
            }
        },
        content = content
    )
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
