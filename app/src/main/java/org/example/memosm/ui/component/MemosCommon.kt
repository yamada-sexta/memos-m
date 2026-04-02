package org.example.memosm.ui.component

import DeleteConfirmationDialog
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoState
import org.example.memosm.model.User
import org.example.memosm.ui.MemoKey
import org.example.memosm.state.AppSettingsControls
import org.example.memosm.state.DraftControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.MemosListControls
import org.example.memosm.state.SessionControls
import org.example.memosm.ui.component.composer.ComposerMode
import org.example.memosm.ui.component.composer.MemoEditScreen
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.item.DraftsCard
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.viewmodel.MemoListState

val LocalMemoEditor = compositionLocalOf<(Memo) -> Unit> { { } }
val LocalMemoCommenter = compositionLocalOf<(Memo) -> Unit> { { } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemosCommon(
    controls: MemosListControls,
    actionControls: MemoActionControls,
    sessionControls: SessionControls,
    appSettingsControls: AppSettingsControls?,
    draftControls: DraftControls?,
    title: String,
    memoListState: MemoListState,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    showComposer: Boolean = false,
    showUserStats: Boolean = false,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
    openComposer: Boolean = false,
    onComposerOpened: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Double tap refresh logic: scroll to top
    // var lastProcessedTrigger by remember { mutableLongStateOf(uiState.refreshTrigger) }

    MemosScaffold(
        memos = memoListState.list.items,
        listState = listState,
        controls = controls,
        actionControls = actionControls,
        sessionControls = sessionControls,
        draftControls = draftControls,
        appSettingsControls = appSettingsControls,
        onToggleNavBar = { onToggleNavBar?.invoke(it) },
        isNavBarVisible = isNavBarVisible,
        listPane = { onMemoClick ->
            MemosListPane(
                controls = controls,
                sessionControls = sessionControls,
                draftControls = draftControls,
                listState = listState,
                onMemoClick = onMemoClick,
                contentPadding = PaddingValues(
                    start = 16.dp, top = 88.dp, end = 16.dp, bottom = 80.dp
                ),
                onDraftsCardClick = { /* showDraftsScreen = true */ },
                onHashtagClick = { tag -> /* TODO */ },
                showUserStats = showUserStats,
                memoListState = memoListState,
                onLoadMore = onLoadMore,
                onRefresh = onRefresh
            )
        },
        overlay = { onMemoClick, showSearchBar, isSearchExpanded, onSearchExpandedChange, isDualPane, isDetailVisible ->
            /* Overlay Content implementation left simple for MemosCommon replacement */
        }
    )
}

@Composable
private fun MemosListPane(
    controls: MemosListControls,
    sessionControls: SessionControls,
    draftControls: DraftControls?,
    listState: LazyListState,
    onMemoClick: (Memo) -> Unit,
    contentPadding: PaddingValues,
    onDraftsCardClick: () -> Unit,
    onHashtagClick: (String) -> Unit,
    showUserStats: Boolean,
    memoListState: MemoListState,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit
) {
    val hasDrafts = draftControls?.state?.drafts?.isNotEmpty() == true
    val shortcutListState = rememberLazyListState()
    val draftCount = draftControls?.state?.drafts?.size ?: 0

    GenericMemosListPane(
        controls = controls,
        actionControls = null,
        sessionControls = sessionControls,
        appSettingsControls = null,
        memos = memoListState.list.items,
        isLoading = memoListState.list.isLoading,
        isRefreshing = false,
        nextPageToken = memoListState.list.nextPageToken,
        onLoadMore = onLoadMore,
        onRefresh = onRefresh,
        onMemoClick = onMemoClick,
        listState = listState,
        contentPadding = contentPadding,
        errorTitle = stringResource(R.string.common_error_failed_to_load_memos),
        isOffline = memoListState.list.isOffline,
        errorMessage = memoListState.list.errorMessage,
        onHashtagClick = onHashtagClick,
        header = {
            val hasShortcuts = memoListState.shortcuts.isNotEmpty()
            val selectedHashtag = memoListState.selectedHashtag
            val showFilterRow = hasShortcuts || selectedHashtag != null

            if (hasDrafts || showFilterRow) {
                // DraftsCard and Shortcuts implementation here
            }
        })
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemosScaffold(
    memos: List<Memo>,
    listState: LazyListState,
    controls: MemosListControls,
    actionControls: MemoActionControls,
    sessionControls: SessionControls,
    draftControls: DraftControls?,
    appSettingsControls: AppSettingsControls?,
    listPane: @Composable BoxScope.(onMemoClick: (Memo) -> Unit) -> Unit,
    topBar: @Composable (isDetailVisible: Boolean, isDualPane: Boolean) -> Unit = { _, _ -> },
    overlay: @Composable BoxScope.(onMemoClick: (Memo) -> Unit, showSearchBar: Boolean, isSearchExpanded: Boolean, onSearchExpandedChange: (Boolean) -> Unit, isDualPane: Boolean, isDetailVisible: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onToggleNavBar: (Boolean) -> Unit = {},
    isNavBarVisible: Boolean = true
) {
    val focusRequester = remember { FocusRequester() }

    val scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).copy(
        defaultPanePreferredWidth = 600.dp
    )

    val navigator = rememberListDetailPaneScaffoldNavigator<MemoKey>(
        scaffoldDirective = scaffoldDirective
    )

    // Workaround for Compose Material 3 Adaptive Navigator not automatically updating the inner state
    // when scaffoldValue changes (e.g. on window resize).
    LaunchedEffect(navigator.scaffoldValue) {
        (navigator.scaffoldState as? androidx.compose.material3.adaptive.layout.MutableThreePaneScaffoldState)?.snapTo(navigator.scaffoldValue)
    }

    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Capture initial focus to prevent child inputs (like composer or search) from auto-focusing
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(navigator.currentDestination) {
        focusManager.clearFocus()
    }

    // Sync selected memo with navigator simplified

    // Scroll direction tracking for search bar visibility
    val scrollContext = rememberScrollContext(
        listState = listState
    )

    val isDetailVisible =
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
    val isListVisible =
        navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded
    val isDualPane = isListVisible && isDetailVisible

    var isSearchExpanded by remember { mutableStateOf(false) }

    val showNavBarByScroll by remember {
        derivedStateOf { !scrollContext.isScrollingDown || listState.firstVisibleItemIndex == 0 }
    }

    LaunchedEffect(showNavBarByScroll, isDetailVisible, isDualPane, isSearchExpanded) {
        if ((isDetailVisible || isSearchExpanded) && !isDualPane) {
            // Hide navbar when viewing detail or searching on single-pane (mobile)
            onToggleNavBar(false)
        } else {
            // Restore navbar state based on scroll direction when returning to list or when in dual-pane
            onToggleNavBar(showNavBarByScroll)
        }
    }

    val showSearchBar by remember {
        derivedStateOf { showNavBarByScroll }
    }

    var memoToEdit by remember { mutableStateOf<Memo?>(null) }
    var memoToComment by remember { mutableStateOf<Memo?>(null) }
    
    // Hold onto the memo object while the exit animation plays
    var activeMemoToEdit by remember { mutableStateOf<Memo?>(null) }
    LaunchedEffect(memoToEdit) {
        if (memoToEdit != null) {
            activeMemoToEdit = memoToEdit
        }
    }
    
    var activeMemoToComment by remember { mutableStateOf<Memo?>(null) }
    LaunchedEffect(memoToComment) {
        if (memoToComment != null) {
            activeMemoToComment = memoToComment
        }
    }

    CompositionLocalProvider(
        LocalMemoEditor provides { memoToEdit = it },
        LocalMemoCommenter provides { memoToComment = it }
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = { topBar(isDetailVisible, isDualPane) }) { paddingValues ->
                NavigableListDetailPaneScaffold(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable(),
                    navigator = navigator,
                    listPane = {
                        AnimatedPane {
                            Box(modifier = Modifier.fillMaxSize()) {
                                listPane { memo ->
                                    focusManager.clearFocus()
                                    scope.launch {
                                        val id = memo.name ?: memo.content.hashCode().toString()
                                        navigator.navigateTo(
                                            ListDetailPaneScaffoldRole.Detail, MemoKey(id)
                                        )
                                    }
                                }

                                overlay(
                                    { memo ->
                                        focusManager.clearFocus()
                                        scope.launch {
                                            val id = memo.name ?: memo.content.hashCode().toString()
                                            navigator.navigateTo(
                                                ListDetailPaneScaffoldRole.Detail,
                                                MemoKey(id, fromSearch = true)
                                            )
                                        }
                                    },
                                    showSearchBar,
                                    isSearchExpanded,
                                    { isSearchExpanded = it },
                                    isDualPane,
                                    isDetailVisible
                                )
                            }
                        }
                    },
                    detailPane = {
                        AnimatedPane {
                            val currentMemoKey = navigator.currentDestination?.contentKey

                            AnimatedContent(
                                targetState = currentMemoKey, transitionSpec = {
                                    if (isDualPane) {
                                        if (initialState == null) {
                                            (fadeIn(
                                                animationSpec = tween(
                                                    220,
                                                    delayMillis = 90
                                                )
                                            ) + scaleIn(
                                                initialScale = 0.92f,
                                                animationSpec = tween(220, delayMillis = 90)
                                            )).togetherWith(fadeOut(animationSpec = tween(90)))
                                        } else {
                                            fadeIn(animationSpec = tween(300)).togetherWith(
                                                fadeOut(animationSpec = tween(300))
                                            )
                                        }
                                    } else {
                                        (slideInVertically(
                                            initialOffsetY = { it }, animationSpec = tween(300)
                                        ) + fadeIn()).togetherWith(
                                            slideOutVertically(
                                                targetOffsetY = { it }, animationSpec = tween(300)
                                            ) + fadeOut()
                                        )
                                    }
                                }, label = "DetailPaneTransition"
                            ) { memoKey ->
                                val memo =
                                    remember(memoKey, memos) {
                                        memoKey?.let { key ->
                                            val pool = memos
                                            pool.find {
                                                (it.name ?: it.content.hashCode()
                                                    .toString()) == key.id
                                            }
                                        }
                                    }

                                if (memo != null) {
                                    // Detail view omitted for brevity
                                } else if (isDualPane) {
                                    MemoDetailPlaceholder()
                                }
                            }
                        }
                    })
                }

                val enterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
                val exitEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GenericMemosListPane(
        controls: MemosListControls?,
        actionControls: MemoActionControls?,
        sessionControls: SessionControls?,
        appSettingsControls: AppSettingsControls?,
        memos: List<Memo>,
        isLoading: Boolean,
        isRefreshing: Boolean,
        nextPageToken: String?,
        onLoadMore: () -> Unit,
        onRefresh: () -> Unit,
        onMemoClick: (Memo) -> Unit,
        modifier: Modifier = Modifier,
        listState: LazyListState = rememberLazyListState(),
        userProvider: (Memo) -> User? = { null },
        header: (LazyListScope.() -> Unit)? = null,
        contentPadding: PaddingValues = PaddingValues(
            start = 16.dp, top = 88.dp, end = 16.dp, bottom = 80.dp
        ),
        errorTitle: String = stringResource(R.string.common_error_failed_to_load),
        isOffline: Boolean = false,
        errorMessage: String? = null,
        onHashtagClick: ((String) -> Unit)? = null
    ) {
        val focusManager = LocalFocusManager.current

        val onEditMemo = LocalMemoEditor.current
        var memoToDelete by remember { mutableStateOf<Memo?>(null) }

        LaunchedEffect(listState, isLoading, nextPageToken) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { lastIndex ->
                if (lastIndex != null && !isLoading && nextPageToken != null && lastIndex >= listState.layoutInfo.totalItemsCount - 5) {
                    onLoadMore()
                }
            }
        }

        val pullToRefreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullToRefreshState,
            modifier = modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                    })
                },
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullToRefreshState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Show offline indicator card at the top when displaying cached data
                if (isOffline) {
                    item(key = "offline_indicator") {
                        NetworkErrorCard(
                            onRetry = onRefresh,
                            errorMessage = errorMessage,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                    }
                }

                header?.invoke(this)

                if (isLoading && memos.isEmpty() && !isRefreshing) {
                    item {
                        Box(
                            modifier = Modifier.fillParentMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (errorMessage != null && memos.isEmpty()) {
                    item {
                        ErrorView(
                            title = errorTitle,
                            message = errorMessage,
                            onRetry = onRefresh,
                            modifier = Modifier.fillParentMaxHeight(0.7f)
                        )
                    }
                } else {
                    itemsIndexed(memos, key = { index, it ->
                        val baseKey = it.name.takeUnless { n -> n.isNullOrBlank() }
                            ?: "${it.content.hashCode()}_${it.createTime}"
                        "${baseKey}_$index"
                    }) { index, memo ->
                        Box(
                            modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
                        ) {
                            val isOwner = memo.creator == sessionControls?.state?.currUser?.name
                            MemoItem(
                                memo = memo,
                                user = userProvider(memo),
                                currentUser = sessionControls?.state?.currUser,
                                token = sessionControls?.state?.token ?: "",
                                hostUrl = sessionControls?.state?.hostUrl ?: "",
                                colors = CardDefaults.cardColors(),
                                onClick = {
                                    focusManager.clearFocus()
                                    onMemoClick(memo)
                                },
                                onEdit = if (isOwner) {
                                    { onEditMemo(memo) }
                                } else null,
                                onArchive = if (isOwner) {
                                    {
                                        actionControls?.updateMemo?.invoke(memo, memo.copy(state = MemoState.ARCHIVED))
                                    }
                                } else null,
                                onUnarchive = if (isOwner) {
                                    {
                                        actionControls?.updateMemo?.invoke(memo, memo.copy(state = MemoState.NORMAL))
                                    }
                                } else null,
                                onPin = if (isOwner) { pinned -> actionControls?.updateMemoPinned?.invoke(memo, pinned) } else null,
                                onDelete = if (isOwner) {
                                    { memoToDelete = memo }
                                } else null,
                                onUpsertReaction = { emoji ->
                                    actionControls?.upsertMemoReaction?.invoke(memo, emoji)
                                },
                                onDeleteReaction = { reaction ->
                                    actionControls?.deleteMemoReaction?.invoke(memo, reaction)
                                },
                                onContentUpdate = if (isOwner) { newContent ->
                                    actionControls?.updateMemo?.invoke(memo, memo.copy(content = newContent))
                                } else null,
                                maxHeight = 400.dp,
                                modifier = Modifier.widthIn(max = 800.dp),
                                onHashtagClick = onHashtagClick,
                                headerScale = appSettingsControls?.settings?.headerScale ?: 1.0f,
                                reactionOptions = sessionControls?.state?.instanceSettings?.memoRelatedSetting?.reactions
                                    ?: emptyList())

                        }
                    }

                    if (isLoading && memos.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (!isLoading && nextPageToken == null && memos.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.memo_list_end),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }

        memoToDelete?.let { memo ->
            DeleteConfirmationDialog(memo = memo, onDismiss = { memoToDelete = null }, onConfirm = {
                actionControls?.deleteMemo?.invoke(memo.name!!)
                memoToDelete = null
            })
        }
    }
fun resolveResourceUrl(hostUrl: String, relativeUrl: String?): String? {
    if (relativeUrl.isNullOrBlank()) return null
    if (relativeUrl.startsWith("http")) return relativeUrl

    val cleanHost = hostUrl.trimEnd('/')
    val cleanRelative = relativeUrl.trimStart('/')

    val result = "$cleanHost/$cleanRelative"
    android.util.Log.d(
        "MemosDebug", "resolveResourceUrl: host=$hostUrl, relative=$relativeUrl -> $result"
    )
    return result
}
