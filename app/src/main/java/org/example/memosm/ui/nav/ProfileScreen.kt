package org.example.memosm.ui.nav

import AccountsList
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import org.example.memosm.ui.profile.EditProfileActivity
import org.example.memosm.ui.profile.EditAccountCredentialsActivity
import org.example.memosm.ui.profile.ArchivedMemosActivity
import org.example.memosm.ui.profile.NotificationsActivity
import org.example.memosm.ui.profile.SettingsActivity
import org.example.memosm.ui.theme.ProfileTheme
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.example.memosm.ui.component.LocalNetworkPermission
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.data.DataStoreManager
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.toUserSnapshot
import org.example.memosm.ui.component.ErrorView
import org.example.memosm.ui.component.ProfileHeader
import org.example.memosm.ui.component.StatsActivityCard
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.ui.component.setting.SettingsGroup
import org.example.memosm.ui.component.setting.SettingsNavigationRow
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.RefreshSource
import org.koin.compose.koinInject

@Composable
fun ProfileScreen(
    viewModel: MemosViewModel,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    val context = LocalContext.current
    val archived = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterProfileDetails()
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterProfileDetails()
    }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterProfileDetails()
    }
    val editProfile = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterProfileDetails()
    }
    val listState = rememberLazyListState()
    ProfileTheme {
        ProfileListPane(
            viewModel = viewModel,
            onLogout = onLogout,
            onAddAccount = onAddAccount,
            onShowArchived = { archived.launch(Intent(context, ArchivedMemosActivity::class.java)) },
            onShowNotifications = { notifications.launch(Intent(context, NotificationsActivity::class.java)) },
            onShowSettings = { settings.launch(Intent(context, SettingsActivity::class.java)) },
            onOpenSection = { section ->
                settings.launch(SettingsActivity.createIntent(context, section))
            },
            onEditProfile = { editProfile.launch(Intent(context, EditProfileActivity::class.java)) },
            onToggleNavBar = onToggleNavBar,
            isNavBarVisible = isNavBarVisible,
            listState = listState
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListPane(
    viewModel: MemosViewModel,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onShowArchived: () -> Unit,
    onShowNotifications: () -> Unit,
    onShowSettings: () -> Unit,
    onOpenSection: (SettingsSection) -> Unit,
    onEditProfile: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
    listState: LazyListState
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.session.currUser
    val stats = uiState.session.userStats
    val accounts = uiState.accounts
    val networkPermission = LocalNetworkPermission.current
    val scope = rememberCoroutineScope()

    // Scroll direction tracking for nav bar visibility
    rememberScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) })

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 96.dp else 16.dp, // Profile has more bottom internal padding?
        label = "BottomPadding"
    )

    // Double tap refresh logic: scroll to top
    var lastProcessedTrigger by rememberSaveable { mutableLongStateOf(uiState.refreshTrigger) }
    LaunchedEffect(uiState.refreshTrigger) {
        if (uiState.refreshTrigger > lastProcessedTrigger) {
            if (uiState.refreshSource == RefreshSource.USerMemos || uiState.refreshSource == RefreshSource.Manual) {
                listState.animateScrollToItem(0)
            }
        }
        lastProcessedTrigger = uiState.refreshTrigger
    }

    var showAccountSwitcher by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val dataStore: DataStoreManager = koinInject()
    val editCredentials = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val credentials = EditAccountCredentialsActivity.readResult(result.resultCode, result.data)
        if (credentials != null) {
            scope.launch {
                val account = dataStore.getAccounts().firstOrNull { it.id == credentials.accountId }
                if (account != null) {
                    viewModel.userDelegate.updateAccountCredentials(account, credentials.hostUrl, credentials.token)
                    showAccountSwitcher = false
                }
            }
        }
    }

    // Get current account for profile editing
    val activeAccount = accounts.find { it.isActive }

    if (showAccountSwitcher) {
        ModalBottomSheet(
            onDismissRequest = { showAccountSwitcher = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            )
        ) {
            AccountsList(
                accounts = accounts,
                onSwitchAccount = { account ->
                    scope.launch {
                        if (networkPermission.ensureAccess(account.hostUrl)) {
                            viewModel.userDelegate.switchAccount(account)
                            showAccountSwitcher = false
                        }
                    }
                },
                onLogoutAccount = { viewModel.userDelegate.removeAccount(it) },
                onEditAccount = { account ->
                    editCredentials.launch(EditAccountCredentialsActivity.createIntent(context, account.id))
                },
                onAddAccount = {
                    onAddAccount()
                    showAccountSwitcher = false
                },
                modifier = Modifier.padding(bottom = 32.dp)
            )
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing, onRefresh = {
            viewModel.fetchUserMemos(refresh = true)
            viewModel.userDelegate.refreshInstanceSettings()
            viewModel.userDelegate.refreshUserStats()
        }, modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                end = 16.dp,
                bottom = bottomPadding + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val itemModifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxWidth()

            item {
                Box(itemModifier) {
                    val hostUrl = uiState.session.hostUrl
                    if (user != null) {
                        val rawAvatarUrl = user.avatarUrl
                        val avatarUrl = org.example.memosm.ui.component.resolveResourceUrl(
                            hostUrl, rawAvatarUrl
                        )

                        ProfileHeader(
                            user = user.toUserSnapshot(
                                token = uiState.session.token
                            ).copy(avatarUrl = avatarUrl),
                            userStats = stats,
                            onClick = { showAccountSwitcher = true },
                            onEditClick = onEditProfile)
                    } else {
                        if (activeAccount != null) {
                            val rawAvatarUrl = activeAccount.avatarUrl
                            val avatarUrl = org.example.memosm.ui.component.resolveResourceUrl(
                                hostUrl, rawAvatarUrl
                            )

                            ProfileHeader(
                                user = UserSnapshot(
                                    name = activeAccount.name?.let { "users/$it" },
                                    username = activeAccount.name ?: "",
                                    displayName = activeAccount.displayName,
                                    avatarUrl = avatarUrl,
                                    token = uiState.session.token
                                ),
                                userStats = stats,
                                onClick = { showAccountSwitcher = true },
                                onEditClick = onEditProfile)
                        } else if (uiState.userMemoList.list.isLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            if (user != null || accounts.any { it.isActive }) {
                item {
                    Box(itemModifier) {
                        StatsActivityCard(
                            userStats = stats,
                            weekStartDayOffset = uiState.session.instanceSettings?.generalSetting?.weekStartDayOffset
                                ?: 0
                        )
                    }
                }

                item {
                    Box(itemModifier) {
                        SettingsGroup {
                            SettingsNavigationRow(
                                title = stringResource(R.string.profile_notifications),
                                icon = Icons.Outlined.Notifications,
                                onClick = onShowNotifications
                            )
                            listOf(SettingsSection.SHORTCUTS, SettingsSection.WEBHOOKS, SettingsSection.INSTANCE)
                                .forEach { section ->
                                    SettingsNavigationRow(
                                        title = stringResource(section.titleRes),
                                        icon = section.icon,
                                        onClick = { onOpenSection(section) }
                                    )
                                }
                        }
                    }
                }

                item {
                    Box(itemModifier) {
                        SettingsGroup {
                            SettingsNavigationRow(
                                title = stringResource(R.string.profile_archived),
                                icon = Icons.Outlined.Archive,
                                onClick = onShowArchived
                            )
                        }
                    }
                }

                item {
                    Box(itemModifier) {
                        SettingsGroup {
                            SettingsNavigationRow(
                                title = stringResource(R.string.settings_title),
                                icon = Icons.Outlined.Settings,
                                onClick = onShowSettings
                            )
                            SettingsNavigationRow(
                                title = stringResource(SettingsSection.ABOUT.titleRes),
                                icon = SettingsSection.ABOUT.icon,
                                onClick = { onOpenSection(SettingsSection.ABOUT) }
                            )
                        }
                    }
                }

                if (uiState.error != null) {
                    item {
                        Box(itemModifier) {
                            ErrorView(
                                title = stringResource(R.string.common_error_failed_to_load_profile),
                                message = stringResource(uiState.error!!.resourceId, *uiState.error!!.formatArgs.toTypedArray()),
                                onRetry = { viewModel.fetchUserMemos(refresh = true) })
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(64.dp))
                }
            } else if (!uiState.userMemoList.list.isLoading) {
                item {
                    ErrorView(
                        message = uiState.error?.let { stringResource(it.resourceId, *it.formatArgs.toTypedArray()) }
                            ?: stringResource(R.string.profile_user_info_not_available),
                        onRetry = { viewModel.fetchUserMemos(refresh = true) },
                        modifier = itemModifier.fillParentMaxHeight(0.7f)
                    )
                }
            }
        }
    }
}


@Composable
fun InstanceCard(instance: InstanceProfile) {
    val unknown = stringResource(R.string.memo_unknown_user)
    val mode = instance.mode ?: if (instance.demo == true) "demo" else "prod"
    org.example.memosm.ui.component.setting.SettingsGroup {
        listOf(
            stringResource(R.string.profile_instance_version) to (instance.version ?: unknown),
            stringResource(R.string.profile_instance_mode) to mode,
            stringResource(R.string.profile_instance_url) to (instance.instanceUrl?.takeIf { it.isNotBlank() } ?: unknown)
        ).forEach { (label, value) ->
            ListItem(
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                content = { Text(label) },
                supportingContent = { Text(value) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            )
        }
    }
}


@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
