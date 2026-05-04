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
import org.example.memosm.ui.component.composer.MemoEditScreen
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.viewmodel.MemosViewModel

val LocalMemoEditor = compositionLocalOf<(Memo) -> Unit> { { } }
val LocalMemoCommenter = compositionLocalOf<(Memo) -> Unit> { { } }

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemosScaffold(
    viewModel: MemosViewModel,
    memos: List<Memo>,
    listState: LazyListState,
    listPane: @Composable BoxScope.(onMemoClick: (Memo) -> Unit) -> Unit,
    topBar: @Composable (isDetailVisible: Boolean, isDualPane: Boolean) -> Unit = { _, _ -> },
    overlay: @Composable BoxScope.(onMemoClick: (Memo) -> Unit, showSearchBar: Boolean, isSearchExpanded: Boolean, onSearchExpandedChange: (Boolean) -> Unit, isDualPane: Boolean, isDetailVisible: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    onToggleNavBar: (Boolean) -> Unit = {},
    isNavBarVisible: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
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

    // Sync selected memo with navigator
    // We add memos and search items as dependencies to ensure selectedMemo is updated if the items in the list change (e.g. after an edit)
    LaunchedEffect(navigator.currentDestination, memos, uiState.searchMemoList.list.items) {
        val currentMemoKey = navigator.currentDestination?.contentKey
        if (currentMemoKey != null) {
            val selectedId =
                uiState.detailPane.selectedMemo?.let { it.name ?: it.content.hashCode().toString() }
            if (currentMemoKey.id != selectedId || uiState.detailPane.selectedMemo == null) {
                val pool =
                    if (currentMemoKey.fromSearch) uiState.searchMemoList.list.items else memos
                val memo = pool.find {
                    (it.name ?: it.content.hashCode().toString()) == currentMemoKey.id
                }
                if (memo != null) {
                    viewModel.memoActionDelegate.selectMemo(memo)
                }
            }
        } else if (uiState.detailPane.selectedMemo != null) {
            viewModel.memoActionDelegate.clearSelectedMemo()
        }
    }

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
                                    remember(memoKey, memos, uiState.searchMemoList.list.items) {
                                        memoKey?.let { key ->
                                            val pool =
                                                if (key.fromSearch) uiState.searchMemoList.list.items else memos
                                            pool.find {
                                                (it.name ?: it.content.hashCode()
                                                    .toString()) == key.id
                                            }
                                        }
                                    }

                                if (memo != null) {
                                    MemoDetailView(
                                        memo = memo,
                                        comments = uiState.detailPane.comments,
                                        token = uiState.session.token,
                                        hostUrl = uiState.session.hostUrl,
                                        showBackButton = navigator.canNavigateBack(),
                                        onBack = {
                                            focusManager.clearFocus()
                                            scope.launch {
                                                navigator.navigateBack()
                                            }
                                        },
                                        viewModel = viewModel,
                                        reactionOptions = uiState.session.instanceSettings?.memoRelatedSetting?.reactions
                                            ?: emptyList()
                                    )
                                } else if (isDualPane) {
                                    MemoDetailPlaceholder()
                                }
                            }
                        }
                    })
                }

                val enterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
                val exitEasing = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

                AnimatedVisibility(
                    visible = memoToEdit != null,
                    enter = slideInVertically(
                        animationSpec = tween(400, easing = enterEasing),
                        initialOffsetY = { it }) + fadeIn(
                        animationSpec = tween(400, easing = enterEasing)
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(200, easing = exitEasing),
                        targetOffsetY = { it }) + fadeOut(
                        animationSpec = tween(200, easing = exitEasing)
                    )
                ) {
                    activeMemoToEdit?.let { memo ->
                        MemoEditScreen(
                            memo = memo,
                            onDismiss = { memoToEdit = null },
                            viewModel = viewModel,
                            hostUrl = uiState.session.hostUrl,
                            onToggleNavBar = if (isNavBarVisible) onToggleNavBar else null
                        )
                    }
                }

                AnimatedVisibility(
                    visible = memoToComment != null,
                    enter = slideInVertically(
                        animationSpec = tween(400, easing = enterEasing),
                        initialOffsetY = { it }) + fadeIn(
                        animationSpec = tween(400, easing = enterEasing)
                    ),
                    exit = slideOutVertically(
                        animationSpec = tween(200, easing = exitEasing),
                        targetOffsetY = { it }) + fadeOut(
                        animationSpec = tween(200, easing = exitEasing)
                    )
                ) {
                    activeMemoToComment?.let { parentMemo ->
                        MemoComposerScreen(
                            onDismiss = { memoToComment = null },
                            onToggleNavBar = if (isNavBarVisible) onToggleNavBar else null,
                            viewModel = viewModel,
                            hostUrl = uiState.session.hostUrl,
                            title = stringResource(R.string.memo_detail_add_comment),
                            parentMemo = parentMemo,
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun GenericMemosListPane(
        viewModel: MemosViewModel,
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
        val uiState by viewModel.uiState.collectAsState()
        val focusManager = LocalFocusManager.current
        var lastAutoLoadScrollPosition by remember(listState) {
            mutableStateOf<Pair<Int, Int>?>(null)
        }

        val onEditMemo = LocalMemoEditor.current
        var memoToDelete by remember { mutableStateOf<Memo?>(null) }

        LaunchedEffect(listState, isLoading, nextPageToken) {
            snapshotFlow {
                (listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset) to
                    (listState.isScrollInProgress to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index)
            }.collect { (scrollPosition, scrollState) ->
                val (isScrollInProgress, lastIndex) = scrollState
                val isNearEnd =
                    isScrollInProgress &&
                        lastIndex != null &&
                        !isLoading &&
                        !nextPageToken.isNullOrBlank() &&
                        lastIndex >= listState.layoutInfo.totalItemsCount - 5

                if (!isNearEnd) {
                    lastAutoLoadScrollPosition = null
                    return@collect
                }

                if (
                    lastAutoLoadScrollPosition != scrollPosition
                ) {
                    lastAutoLoadScrollPosition = scrollPosition
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
                } else if (uiState.error != null && memos.isEmpty()) {
                    item {
                        ErrorView(
                            title = errorTitle,
                            message = uiState.error!!,
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
                            val isOwner =
                                memo.isUnsynced || memo.creator == uiState.session.currUser?.name
                            MemoItem(
                                memo = memo,
                                user = userProvider(memo),
                                currentUser = uiState.session.currUser,
                                token = uiState.session.token,
                                hostUrl = uiState.session.hostUrl,
                                colors = if (memo.name != null && memo.name == uiState.detailPane.selectedMemo?.name) {
                                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else {
                                    CardDefaults.cardColors()
                                },
                                onClick = {
                                    focusManager.clearFocus()
                                    onMemoClick(memo)
                                },
                                onEdit = if (isOwner) {
                                    { onEditMemo(memo) }
                                } else null,
                                onArchive = if (isOwner && !memo.isUnsynced) {
                                    {
                                        viewModel.memoActionDelegate.updateMemo(
                                            memo,
                                            memo.content,
                                            memo.visibility,
                                            memo.attachments ?: emptyList(),
                                            memo.location,
                                            MemoState.ARCHIVED
                                        )
                                    }
                                } else null,
                                onUnarchive = if (isOwner && !memo.isUnsynced) {
                                    {
                                        viewModel.memoActionDelegate.updateMemo(
                                            memo,
                                            memo.content,
                                            memo.visibility,
                                            memo.attachments ?: emptyList(),
                                            memo.location,
                                            MemoState.NORMAL
                                        )
                                    }
                                } else null,
                                onPin = if (isOwner && !memo.isUnsynced) { pinned ->
                                    viewModel.memoActionDelegate.updateMemoPinned(memo, pinned)
                                } else null,
                                onDelete = if (isOwner) {
                                    {
                                        if (memo.isUnsynced && memo.localId != null) {
                                            viewModel.draftDelegate.deleteDraft(memo.localId)
                                        } else {
                                            memoToDelete = memo
                                        }
                                    }
                                } else null,
                                onUpsertReaction = { emoji ->
                                    if (!memo.isUnsynced) {
                                        viewModel.memoActionDelegate.upsertMemoReaction(memo, emoji)
                                    }
                                },
                                onDeleteReaction = { reaction ->
                                    if (!memo.isUnsynced) {
                                        viewModel.memoActionDelegate.deleteMemoReaction(memo, reaction)
                                    }
                                },
                                onContentUpdate = if (isOwner) { newContent ->
                                    viewModel.memoActionDelegate.updateMemo(
                                        memo,
                                        newContent,
                                        memo.visibility,
                                        memo.attachments ?: emptyList(),
                                        memo.location
                                    )
                                } else null,
                                maxHeight = 400.dp,
                                modifier = Modifier.widthIn(max = 800.dp),
                                onHashtagClick = onHashtagClick,
                                headerScale = uiState.appSettings.headerScale,
                                reactionOptions = uiState.session.instanceSettings?.memoRelatedSetting?.reactions
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
                viewModel.memoActionDelegate.deleteMemo(memo)
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
