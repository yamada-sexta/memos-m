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
import org.example.memosm.model.UserGeneralSetting
import org.example.memosm.ui.component.ArchivedMemosScreen
import org.example.memosm.ui.component.ErrorView
import org.example.memosm.ui.component.LoginDialog
import org.example.memosm.ui.component.ProfileHeader
import org.example.memosm.ui.component.StatsActivityCard
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.ui.component.setting.AboutAppCard
import org.example.memosm.ui.component.setting.AccountEditDialog
import org.example.memosm.ui.component.setting.AppSettingsCard
import org.example.memosm.ui.component.setting.SettingsCard
import org.example.memosm.data.DataStoreManager
import org.example.memosm.state.AppSettingsControls
import org.example.memosm.state.SessionControls
import org.example.memosm.ui.component.setting.ShortcutsCard
import org.example.memosm.ui.component.setting.WebhooksCard

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ProfileScreen(
    sessionControls: SessionControls,
    appSettingsControls: AppSettingsControls,
    dataStoreManager: DataStoreManager,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    var isArchivedVisible by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val transitionState = remember { SeekableTransitionState(isArchivedVisible) }

    LaunchedEffect(isArchivedVisible) {
        if (isArchivedVisible != transitionState.targetState) {
            transitionState.animateTo(isArchivedVisible)
        }
    }

    PredictiveBackHandler(enabled = isArchivedVisible) { progress ->
        try {
            progress.collect { backEvent ->
                transitionState.seekTo(backEvent.progress, targetState = false)
            }
            transitionState.animateTo(false)
            isArchivedVisible = false
        } catch (e: CancellationException) {
            transitionState.animateTo(true)
        }
    }

    SharedTransitionLayout {
        val transition = rememberTransition(transitionState, label = "ProfileArchiveTransition")
        transition.AnimatedContent(
            transitionSpec = {
                if (targetState) {
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
            }) { showArchived ->
            if (showArchived) {
                // ArchivedMemosScreen needs updating to use flows
                Box(modifier = Modifier.fillMaxSize()) {
                     Text("Archived Memos Screen Refactoring Pending", modifier = Modifier.align(Alignment.Center))
                }
            } else {
                ProfileListPane(
                    sessionControls = sessionControls,
                    appSettingsControls = appSettingsControls,
                    dataStoreManager = dataStoreManager,
                    onLogout = onLogout,
                    onAddAccount = onAddAccount,
                    onShowArchived = { isArchivedVisible = true },
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
    sessionControls: SessionControls,
    appSettingsControls: AppSettingsControls,
    dataStoreManager: DataStoreManager,
    onLogout: () -> Unit,
    onAddAccount: () -> Unit,
    onShowArchived: () -> Unit,
    animatedVisibilityScope: AnimatedVisibilityScope,
    sharedTransitionScope: SharedTransitionScope,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
    listState: LazyListState
) {
    val user = sessionControls.state.currUser
    val stats = sessionControls.state.userStats
    val instance = sessionControls.state.instanceProfile
    val userSettings = sessionControls.state.userSettings
    val accounts = sessionControls.accounts

    // Scroll direction tracking for nav bar visibility
    rememberScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) })

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 96.dp else 16.dp, // Profile has more bottom internal padding?
        label = "BottomPadding"
    )


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
                showEditDialog = false // TODO implementation via flow
            },
            isSaving = isSavingProfile
        )
    }

    // Credential Edit Dialog (local login info)
    accountToEditCredentials?.let { account ->
        LoginDialog(
            onLoginSuccess = { baseUrl, token ->
                // Update the account with new credentials
                // TODO update
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
                    // TODO update
                    showAccountSwitcher = false
                },
                onLogoutAccount = { /*TODO*/ },
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
        isRefreshing = false, onRefresh = {
             sessionControls.fetchCurrentUser()
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
                    val hostUrl = sessionControls.state.hostUrl
                    if (user != null) {
                        val rawAvatarUrl = user.avatarUrl
                        val avatarUrl = org.example.memosm.ui.component.resolveResourceUrl(
                            hostUrl, rawAvatarUrl
                        )

                        ProfileHeader(
                            user = user.copy(avatarUrl = avatarUrl, token = sessionControls.state.token),
                            onClick = { showAccountSwitcher = true },
                            onEditClick = { showEditDialog = true })
                    } else {
                        if (activeAccount != null) {
                            val rawAvatarUrl = activeAccount.avatarUrl
                            val avatarUrl = org.example.memosm.ui.component.resolveResourceUrl(
                                hostUrl, rawAvatarUrl
                            )

                            ProfileHeader(
                                user = User(
                                    name = activeAccount.name?.let { "users/$it" },
                                    username = activeAccount.name ?: "",
                                    displayName = activeAccount.displayName,
                                    avatarUrl = avatarUrl,
                                    token = sessionControls.state.token
                                ),
                                onClick = { showAccountSwitcher = true },
                                onEditClick = { showEditDialog = true })
                        }
                    }
                }
            }

            if (user != null || accounts.any { it.isActive }) {
                item {
                    Box(itemModifier) {
                        StatsActivityCard(
                            userStats = stats,
                            weekStartDayOffset = sessionControls.state.instanceSettings?.generalSetting?.weekStartDayOffset
                                ?: 0
                        )
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
                                // TODO Update
                            })
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
                            pageSize = appSettingsControls.settings.pageSize,
                            onPageSizeChange = { /* TODO viewModel.appSettingsDelegate.updatePageSize(it) */ },
                            headerScale = appSettingsControls.settings.headerScale,
                            onHeaderScaleChange = {
                                // TODO viewModel.appSettingsDelegate.updateHeaderScale(it)
                            })
                    }
                }

                item {
                    Box(itemModifier) {
                        AboutAppCard()
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(64.dp))
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
