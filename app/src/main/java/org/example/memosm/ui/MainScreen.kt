package org.example.memosm.ui


import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Attachment
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.api.MemosApi
import org.example.memosm.data.DataStoreManager
import org.example.memosm.data.DraftManager
import org.example.memosm.data.cache.MemoCacheRepository
import org.example.memosm.model.Account
import org.example.memosm.model.Attachment
import org.example.memosm.model.Location
import org.example.memosm.model.ShareIntentData
import org.example.memosm.model.Visibility
import org.example.memosm.state.rememberAppSettingsState
import org.example.memosm.state.rememberAttachmentState
import org.example.memosm.state.rememberDraftState
import org.example.memosm.state.rememberExploreMemosState
import org.example.memosm.state.rememberMemoActionState
import org.example.memosm.state.rememberSessionState
import org.example.memosm.state.rememberUserMemosState
import org.example.memosm.ui.component.LoginDialog
import org.example.memosm.ui.component.composer.ComposerMode
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.item.media.MemoImage
import org.example.memosm.ui.component.resolveResourceUrl
import org.example.memosm.ui.nav.AttachmentsScreen
import org.example.memosm.ui.nav.ExploreScreen
import org.example.memosm.ui.nav.MemosScreen
import org.example.memosm.ui.nav.ProfileScreen

enum class MainDestination(
    val labelRes: Int
) {
    MEMOS(R.string.nav_memos), EXPLORE(R.string.nav_explore), ATTACHMENTS(R.string.nav_attachments), PROFILE(
        R.string.nav_profile
    )
}

@Composable
fun MainScreen(
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    shareIntentData: ShareIntentData? = null,
    onShareIntentConsumed: () -> Unit = {},
    shouldOpenComposer: Boolean = false,
    onComposerOpened: () -> Unit = {},
    api: MemosApi?,
    accounts: List<Account>,
    dataStoreManager: DataStoreManager,
    draftManager: DraftManager,
    memoCacheRepository: MemoCacheRepository
) {
    var currentDestination by rememberSaveable { mutableStateOf(MainDestination.MEMOS) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    val sessionControls = rememberSessionState(api, accounts)
    val appSettingsControls = rememberAppSettingsState(dataStoreManager)

    val activeAccount = accounts.find { it.isActive }

    val userMemosControls = rememberUserMemosState(
        api = api,
        repository = memoCacheRepository,
        currentUser = sessionControls.state.currUser,
        accountId = activeAccount?.id,
        pageSize = appSettingsControls.settings.pageSize
    )

    val exploreMemosControls = rememberExploreMemosState(
        api = api,
        repository = memoCacheRepository,
        accountId = activeAccount?.id,
        pageSize = appSettingsControls.settings.pageSize
    )

    val attachmentControls = rememberAttachmentState(api)
    val draftControls = rememberDraftState(draftManager, activeAccount?.id?.toLongOrNull())

    val memoActionControls = rememberMemoActionState(
        api = api,
        onMemoCreated = { memo ->
            userMemosControls.insertItem(memo)
        },
        onMemoUpdated = { memo ->
            userMemosControls.updateItem(memo)
            exploreMemosControls.updateItem(memo)
        },
        onMemoDeleted = { name ->
            userMemosControls.removeItem(name)
            exploreMemosControls.removeItem(name)
        }
    )

    val saveableStateHolder = rememberSaveableStateHolder()

    var isNavBarVisible by remember { mutableStateOf(true) }
    var isAddingAccount by remember { mutableStateOf(false) }

    // Share intent composer dialog state
    var showShareComposerDialog by remember { mutableStateOf(false) }
    var shareText by remember { mutableStateOf<String?>(null) }
    var shareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var shareAttachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var shareVisibility by remember { mutableStateOf<Visibility?>(null) }
    var shareLocation by remember { mutableStateOf<Location?>(null) }

    // Track if we've already processed the current share intent
    var processedShareData by remember { mutableStateOf<ShareIntentData?>(null) }

    // Trigger composer when share data is received - CREATE NEW draft

    // Switch to Memos tab if widget triggered composer
    LaunchedEffect(shouldOpenComposer) {
        if (shouldOpenComposer) {
            currentDestination = MainDestination.MEMOS
        }
    }

    LaunchedEffect(shareIntentData) {
        if (shareIntentData != null && !shareIntentData.isEmpty && processedShareData != shareIntentData) {
            shareText = shareIntentData.text ?: ""
            shareUris = shareIntentData.uris
            shareAttachments = emptyList()
            shareVisibility = null
            shareLocation = null

            processedShareData = shareIntentData
            showShareComposerDialog = true
            onShareIntentConsumed()
        }
    }

    DisposableEffect(currentDestination) {
        focusManager.clearFocus()
        onDispose { }
    }

    val configuration = LocalConfiguration.current
    val isMobile = configuration.screenWidthDp < 600

    android.util.Log.d(
        "MemosScaffoldResize",
        "MainScreen recomposing: screenWidthDp=${configuration.screenWidthDp} isMobile=$isMobile"
    )

    val adaptiveInfo = androidx.compose.material3.adaptive.currentWindowAdaptiveInfo()
    android.util.Log.d(
        "MemosScaffoldResize",
        "MainScreen adaptiveInfo: windowSizeClass=${adaptiveInfo.windowSizeClass}"
    )


    val toggleNavBar: ((Boolean) -> Unit)? = if (isMobile) {
        { isNavBarVisible = it }
    } else null


    if (isAddingAccount) {
        LoginDialog(onLoginSuccess = { newBaseUrl, newToken ->
            scope.launch {
                dataStoreManager.addAccount(newBaseUrl, newToken)
                isAddingAccount = false
            }
        }, onDismiss = { isAddingAccount = false })
    }

    // Share intent composer easing values
    val enterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    val exitEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    @Composable
    fun NavigationIcon(
        destination: MainDestination, isSelected: Boolean, modifier: Modifier = Modifier.size(24.dp)
    ) {
        when (destination) {
            MainDestination.MEMOS -> Icon(
                if (isSelected) Icons.AutoMirrored.Filled.LibraryBooks else Icons.AutoMirrored.Outlined.LibraryBooks,
                contentDescription = null,
                modifier = modifier
            )

            MainDestination.EXPLORE -> Icon(
                if (isSelected) Icons.Default.Public else Icons.Outlined.Public,
                contentDescription = null,
                modifier = modifier
            )

            MainDestination.ATTACHMENTS -> Icon(
                if (isSelected) Icons.Default.Attachment else Icons.Outlined.Attachment,
                contentDescription = null,
                modifier = modifier
            )

            MainDestination.PROFILE -> {
                val user = sessionControls.state.currUser
                val account = accounts.find { it.isActive }
                val rawAvatarUrl = user?.avatarUrl ?: account?.avatarUrl
                val hostUrl = sessionControls.state.hostUrl

                val avatarUri = remember(rawAvatarUrl, hostUrl) {
                    if (rawAvatarUrl.isNullOrBlank()) Uri.EMPTY
                    else (resolveResourceUrl(hostUrl, rawAvatarUrl) ?: "").toUri()
                }

                MemoImage(
                    attachment = null,
                    token = sessionControls.state.token,
                    hostUrl = hostUrl,
                    uri = avatarUri,
                    filename = "avatar",
                    isRound = true,
                    placeholderIcon = if (isSelected) Icons.Default.Person else Icons.Outlined.Person,
                    modifier = modifier
                        .then(
                            if (isSelected) Modifier.border(
                                2.dp, MaterialTheme.colorScheme.primary, CircleShape
                            ) else Modifier
                        )
                        .padding(if (isSelected) 1.dp else 0.dp)
                )
            }
        }
    }

    fun handleDestinationClick(destination: MainDestination) {
        focusManager.clearFocus()
        val currentTime = System.currentTimeMillis()
        if (currentDestination == destination && currentTime - lastTapTime < 500) {
            when (destination) {
                MainDestination.MEMOS -> userMemosControls.fetch(true)
                MainDestination.EXPLORE -> exploreMemosControls.fetch(true)
                MainDestination.ATTACHMENTS -> attachmentControls.fetch(true)
                else -> {}
            }
        }
        currentDestination = destination
        lastTapTime = currentTime
    }

    Surface(
        modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // Navigation Rail for tablets/desktops
                if (!isMobile) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = contentColorFor(MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Spacer(Modifier.height(12.dp))
                        // Items
                        MainDestination.entries.forEach { destination ->
                            if (destination == MainDestination.PROFILE) {
                                Spacer(Modifier.weight(1f))
                            }
                            NavigationRailItem(
                                selected = currentDestination == destination,
                                onClick = { handleDestinationClick(destination) },
                                icon = {
                                    NavigationIcon(
                                        destination, currentDestination == destination
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) })
                        }
                    }
                }

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Content
                    AnimatedContent(
                        targetState = currentDestination,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220)) togetherWith fadeOut(
                                animationSpec = tween(220)
                            )
                        },
                        label = "MainScreenDestinationTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { targetDestination ->
                        saveableStateHolder.SaveableStateProvider(targetDestination) {
                            when (targetDestination) {
                                MainDestination.MEMOS -> MemosScreen(
                                    controls = userMemosControls,
                                    actionControls = memoActionControls,
                                    sessionControls = sessionControls,
                                    appSettingsControls = appSettingsControls,
                                    draftControls = draftControls,
                                    onToggleNavBar = toggleNavBar,
                                    isNavBarVisible = isNavBarVisible,
                                    openComposer = shouldOpenComposer,
                                    onComposerOpened = onComposerOpened
                                )

                                MainDestination.EXPLORE -> ExploreScreen(
                                    controls = exploreMemosControls,
                                    actionControls = memoActionControls,
                                    sessionControls = sessionControls,
                                    onToggleNavBar = toggleNavBar,
                                    isNavBarVisible = isNavBarVisible
                                )

                                MainDestination.ATTACHMENTS -> AttachmentsScreen(
                                    controls = attachmentControls,
                                    sessionControls = sessionControls,
                                    onToggleNavBar = toggleNavBar,
                                    isNavBarVisible = isNavBarVisible
                                )

                                MainDestination.PROFILE -> ProfileScreen(
                                    sessionControls = sessionControls,
                                    appSettingsControls = appSettingsControls,
                                    dataStoreManager = dataStoreManager,
                                    onLogout = onLogout,
                                    onAddAccount = { isAddingAccount = true },
                                    onToggleNavBar = toggleNavBar,
                                    isNavBarVisible = isNavBarVisible
                                )
                            }
                        }
                    }
                }
            }

            // Bottom Navigation Bar for mobile
            if (isMobile) {
                Box(Modifier.align(Alignment.BottomCenter)) {
                    AnimatedVisibility(
                        visible = isNavBarVisible,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it })
                    ) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = contentColorFor(MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            MainDestination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentDestination == destination,
                                    onClick = { handleDestinationClick(destination) },
                                    icon = {
                                        NavigationIcon(
                                            destination, currentDestination == destination
                                        )
                                    },
                                    label = { Text(stringResource(destination.labelRes)) })
                            }
                        }
                    }
                }
            }
        }
    }

    // Share intent composer screen (full-screen OVERLAY — must be AFTER Surface for correct z-order)
    AnimatedVisibility(
        visible = showShareComposerDialog,
        enter = slideInVertically(
            animationSpec = tween(400, easing = enterEasing), initialOffsetY = { it }) + fadeIn(
            animationSpec = tween(400, easing = enterEasing)
        ),
        exit = slideOutVertically(
            animationSpec = tween(200, easing = exitEasing), targetOffsetY = { it }) + fadeOut(
            animationSpec = tween(200, easing = exitEasing)
        )
    ) {
        MemoComposerScreen(
            onDismiss = {
                showShareComposerDialog = false
                shareText = null
                shareUris = emptyList()
                shareAttachments = emptyList()
                shareVisibility = null
                shareLocation = null
            },
            onToggleNavBar = toggleNavBar,
            actionControls = memoActionControls,
            draftControls = draftControls,
            sessionControls = sessionControls,
            hostUrl = sessionControls.state.hostUrl,
            title = stringResource(R.string.memo_composer_fab_new_memo),
            initialContent = shareText ?: "",
            initialUris = shareUris,
            initialAttachments = shareAttachments,
            initialVisibility = shareVisibility,
            initialLocation = shareLocation,
            mode = ComposerMode.PUBLISH
        )
    }
}
