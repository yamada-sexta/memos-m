package org.example.memosm.ui.nav

import AccountsList
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.example.memosm.R
import org.example.memosm.model.Account
import org.example.memosm.model.InstanceProfile
import org.example.memosm.model.User
import org.example.memosm.model.UserSnapshot
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.model.toUserSnapshot
import org.example.memosm.ui.ProfileDetailKey
import org.example.memosm.ui.component.ArchivedMemosScreen
import org.example.memosm.ui.component.ErrorView
import org.example.memosm.ui.component.LoginDialog
import org.example.memosm.ui.component.NotificationsScreen
import org.example.memosm.ui.component.ProfileHeader
import org.example.memosm.ui.component.StatsActivityCard
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.ui.component.setting.AboutAppCard
import org.example.memosm.ui.component.setting.AccountEditDialog
import org.example.memosm.ui.component.setting.AppSettingsCard
import org.example.memosm.ui.component.setting.SettingsCard
import org.example.memosm.ui.component.setting.ShortcutsCard
import org.example.memosm.ui.component.setting.WebhooksCard
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.RefreshSource

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProfileScreen(
    viewModel: MemosViewModel,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    var activeDetail by rememberSaveable { mutableStateOf<ProfileDetailKey?>(null) }
    val listState = rememberLazyListState()

    val transitionState = remember { SeekableTransitionState<ProfileDetailKey?>(activeDetail) }

    LaunchedEffect(activeDetail) {
        if (activeDetail != transitionState.targetState) {
            transitionState.animateTo(activeDetail)
        }
    }

    PredictiveBackHandler(enabled = activeDetail != null) { progress ->
        try {
            progress.collect { backEvent ->
                transitionState.seekTo(backEvent.progress, targetState = null)
            }
            transitionState.animateTo(null)
            activeDetail = null
        } catch (e: CancellationException) {
            transitionState.animateTo(activeDetail)
        }
    }

    SharedTransitionLayout {
        val transition = rememberTransition(transitionState, label = "ProfileArchiveTransition")
        transition.AnimatedContent(
            transitionSpec = {
                if (targetState != null) {
                    (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(
                        initialScale = 0.97f, animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )).togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMediumLow)))
                } else {
                    fadeIn(spring(stiffness = Spring.StiffnessMediumLow)).togetherWith(
                        fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) + scaleOut(
                            targetScale = 0.97f,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                        )
                    )
                }
            }) { detail ->
            when (detail) {
                ProfileDetailKey.Archived -> ArchivedMemosScreen(
                    viewModel = viewModel,
                    onBack = { activeDetail = null },
                    onToggleNavBar = onToggleNavBar,
                    animatedVisibilityScope = this@AnimatedContent,
                    modifier = Modifier.sharedBounds(
                        rememberSharedContentState(key = "archived_container"),
                        animatedVisibilityScope = this@AnimatedContent,
                        boundsTransform = { _, _ ->
                            spring(dampingRatio = 0.8f, stiffness = 380f)
                        })
                )

                ProfileDetailKey.Notifications -> NotificationsScreen(
                    viewModel = viewModel,
                    onBack = { activeDetail = null },
                    onToggleNavBar = onToggleNavBar
                )

                null -> ProfileListPane(
                    viewModel = viewModel,
                    onLogout = onLogout,
                    onAddAccount = onAddAccount,
                    onShowArchived = { activeDetail = ProfileDetailKey.Archived },
                    onShowNotifications = { activeDetail = ProfileDetailKey.Notifications },
                    animatedVisibilityScope = this@AnimatedContent,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    onToggleNavBar = onToggleNavBar,
                    isNavBarVisible = isNavBarVisible,
                    listState = listState
                )
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListPane(
    viewModel: MemosViewModel,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onShowArchived: () -> Unit,
    onShowNotifications: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
    listState: LazyListState
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.session.currUser
    val stats = uiState.session.userStats
    val shortcuts = uiState.userMemoList.shortcuts
    val webhooks = uiState.session.webhooks
    val instance = uiState.session.instanceProfile
    val userSettings = uiState.session.userSettings
    val accounts = uiState.accounts

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

    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var accountToEditCredentials by remember { mutableStateOf<Account?>(null) }
    var isSavingProfile by remember { mutableStateOf(false) }

    // Get current account for profile editing
    val activeAccount = accounts.find { it.isActive }

    // Profile Edit Dialog (remote API update)
    if (showEditDialog && activeAccount != null) {
        AccountEditDialog(
            account = activeAccount,
            onDismiss = { showEditDialog = false },
            onSave = { update ->
                isSavingProfile = true
                viewModel.userDelegate.updateUserProfile(
                    username = update.username,
                    email = update.email,
                    displayName = update.displayName,
                    avatarUrl = update.avatarUrl,
                    description = update.description,
                    password = update.password
                ) { success ->
                    isSavingProfile = false
                    if (success) {
                        showEditDialog = false
                    }
                }
            },
            isSaving = isSavingProfile
        )
    }

    // Credential Edit Dialog (local login info)
    accountToEditCredentials?.let { account ->
        LoginDialog(
            onLoginSuccess = { baseUrl, token ->
                // Update the account with new credentials
                viewModel.userDelegate.updateAccountCredentials(account, baseUrl, token)
                accountToEditCredentials = null
                showAccountSwitcher = false
            }, onDismiss = { accountToEditCredentials = null }, editAccount = account
        )
    }

    if (showAccountSwitcher) {
        ModalBottomSheet(
            onDismissRequest = { showAccountSwitcher = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            AccountsList(
                accounts = accounts,
                onSwitchAccount = {
                    viewModel.userDelegate.switchAccount(it)
                    showAccountSwitcher = false
                },
                onLogoutAccount = { viewModel.userDelegate.removeAccount(it) },
                onEditAccount = { account ->
                    accountToEditCredentials = account
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
                            onClick = { showAccountSwitcher = true },
                            onEditClick = { showEditDialog = true })
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
                                onClick = { showAccountSwitcher = true },
                                onEditClick = { showEditDialog = true })
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
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onShowNotifications
                        ) {
                            ListItem(
                                headlineContent = {
                                    Text(stringResource(R.string.profile_notifications))
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Notifications,
                                        contentDescription = null
                                    )
                                },
                                trailingContent = {
                                    Icon(
                                        Icons.Outlined.ChevronRight,
                                        contentDescription = null
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                            )
                        }
                    }
                }

                item {
                    Box(itemModifier) {
                        with(sharedTransitionScope) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .sharedBounds(
                                        rememberSharedContentState(key = "archived_container"),
                                        animatedVisibilityScope = animatedVisibilityScope,
                                        boundsTransform = { _, _ ->
                                            spring(dampingRatio = 0.8f, stiffness = 380f)
                                        }), onClick = onShowArchived
                            ) {
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.profile_archived),
                                            modifier = Modifier.sharedBounds(
                                                rememberSharedContentState(key = "archive_text"),
                                                animatedVisibilityScope = animatedVisibilityScope,
                                                boundsTransform = { _, _ ->
                                                    tween(durationMillis = 300)
                                                })
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            Icons.Outlined.Archive, contentDescription = null
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            Icons.Outlined.ChevronRight, contentDescription = null
                                        )
                                    },
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                                )
                            }
                        }
                    }
                }

                item {
                    Box(itemModifier) {
                        SettingsCard(
                            settings = userSettings ?: UserGeneralSetting(),
                            onUpdate = { locale, visibility ->
                                viewModel.userDelegate.updateUserGeneralSetting(locale, visibility)
                            })
                    }
                }

                item {
                    Box(itemModifier) {
                        ShortcutsCard(
                            shortcuts = shortcuts,
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
                                viewModel.shortcutDelegate.deleteShortcut(
                                    shortcut
                                )
                            })
                    }
                }

                item {
                    Box(itemModifier) {
                        WebhooksCard(
                            webhooks = webhooks,
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

                item {
                    Box(itemModifier) {
                        InstanceCard(instance ?: InstanceProfile())
                    }
                }

                item {
                    Box(itemModifier) {
                        AppSettingsCard(
                            pageSize = uiState.appSettings.pageSize,
                            onPageSizeChange = { viewModel.appSettingsDelegate.updatePageSize(it) },
                            headerScale = uiState.appSettings.headerScale,
                            onHeaderScaleChange = {
                                viewModel.appSettingsDelegate.updateHeaderScale(
                                    it
                                )
                            })
                    }
                }

                item {
                    Box(itemModifier) {
                        AboutAppCard()
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.profile_instance_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val unknown = stringResource(R.string.memo_unknown_user)
            InfoRow(
                stringResource(R.string.profile_instance_version), instance.version ?: unknown
            )

            val modeLabel = if (instance.mode != null) {
                instance.mode
            } else if (instance.demo == true) {
                "demo" // Or a localized string if available, but "demo" is standard
            } else {
                "prod" // Default assumption if not demo and no mode
            }

            InfoRow(
                stringResource(R.string.profile_instance_mode), modeLabel
            )
            InfoRow(
                stringResource(R.string.profile_instance_url), instance.instanceUrl ?: unknown
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
