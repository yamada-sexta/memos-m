package org.example.memosm.ui.nav

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Shortcut
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.data.sync.PreDownloadState
import org.example.memosm.model.Memo
import org.example.memosm.ui.component.GenericMemosListPane
import org.example.memosm.ui.component.MemoSearchBar
import org.example.memosm.ui.component.MemosScaffold
import org.example.memosm.ui.component.SyncStatusBar
import org.example.memosm.ui.component.composer.EditorRequest
import org.example.memosm.ui.component.composer.rememberMemoEditorLauncher
import org.example.memosm.ui.profile.DraftsActivity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import org.example.memosm.ui.component.item.DraftsCard
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.RefreshSource

@Composable
fun MemosScreen(
    viewModel: MemosViewModel,
    pagerState: PagerState,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
    openComposer: Boolean = false,
    onComposerOpened: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val memosListState = rememberLazyListState()
    val exploreListState = rememberLazyListState()
    val activeFeed = MemoFeed.entries[pagerState.currentPage]
    val listState = if (activeFeed == MemoFeed.MEMOS) memosListState else exploreListState
    val activeMemos = if (activeFeed == MemoFeed.MEMOS) {
        uiState.userMemoList.list.items
    } else {
        uiState.exploreMemoList.list.items
    }
    var isSearchExpanded by remember(activeFeed) { mutableStateOf(false) }
    val density = LocalDensity.current
    var feedHeaderHeight by remember { mutableStateOf(64.dp) }
    val context = LocalContext.current
    val openEditor = rememberMemoEditorLauncher(viewModel)
    val draftsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterEditor()
    }
    val composeNew: () -> Unit = {
        uiState.accounts.firstOrNull { it.isActive }?.let { account ->
            openEditor(EditorRequest(account.id, titleRes = R.string.memo_composer_fab_new_memo,
                visibility = uiState.session.userSettings?.memoVisibility ?: org.example.memosm.model.Visibility.PRIVATE))
        }
    }
    var showDraftPrompt by remember { mutableStateOf(false) }
    var isFabExpanded by remember { mutableStateOf(true) }


    // FAB expansion based on scroll direction
    val scrollContext = rememberScrollContext(listState = listState)

    // Explicitly handle initial state or non-scroll updates if needed
    LaunchedEffect(listState, scrollContext.isScrollingDown) {
        isFabExpanded = !scrollContext.isScrollingDown
    }

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 80.dp else 16.dp, label = "BottomPadding"
    )

    // Handle external composer open request (e.g. from widget)
    LaunchedEffect(openComposer) {
        if (openComposer) {
            // Same logic as FAB click
            if (uiState.draft.drafts.isNotEmpty()) {
                showDraftPrompt = true
            } else {
                composeNew()
            }
            onComposerOpened()
        }
    }

    // Double tap refresh logic: scroll to top
    var lastProcessedTrigger by remember { mutableLongStateOf(uiState.refreshTrigger) }
    LaunchedEffect(uiState.refreshTrigger) {
        val activeRefreshSource = if (activeFeed == MemoFeed.MEMOS) {
            RefreshSource.USerMemos
        } else {
            RefreshSource.ExploreMemos
        }
        if (uiState.refreshTrigger > lastProcessedTrigger && uiState.refreshSource == activeRefreshSource) {
            listState.animateScrollToItem(0)
        }
        lastProcessedTrigger = uiState.refreshTrigger
    }

    MemosScaffold(
        viewModel = viewModel,
        memos = activeMemos,
        listState = listState,
        onToggleNavBar = { onToggleNavBar?.invoke(it) },
        isNavBarVisible = isNavBarVisible,
        navigationKey = activeFeed,
        listHeader = { showSearchBar, searchExpanded ->
            AnimatedVisibility(
                visible = showSearchBar && !searchExpanded,
                enter = slideInVertically { -it } + expandVertically() + fadeIn(),
                exit = slideOutVertically { -it } + shrinkVertically() + fadeOut()
            ) {
                MemoFeedTabs(
                    pagerState,
                    Modifier
                        .testTag("memo_feed_tabs")
                        .onSizeChanged {
                            feedHeaderHeight = with(density) { it.height.toDp() }
                        }
                        .padding(bottom = 16.dp)
                )
            }
        },
        listPane = { onMemoClick ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag("memo_feed_pager"),
                userScrollEnabled = !isSearchExpanded && !showDraftPrompt,
                key = { MemoFeed.entries[it] }
            ) { page ->
                val feed = MemoFeed.entries[page]
                val contentPadding = PaddingValues(
                    start = 16.dp, top = 88.dp + feedHeaderHeight, end = 16.dp, bottom = bottomPadding
                )
                when (feed) {
                    MemoFeed.MEMOS -> MemosListPane(
                        viewModel = viewModel,
                        listState = memosListState,
                        onMemoClick = onMemoClick,
                        contentPadding = contentPadding,
                        onDraftsCardClick = { draftsLauncher.launch(Intent(context, DraftsActivity::class.java)) },
                        onHashtagClick = { tag -> viewModel.shortcutDelegate.toggleHashtagFilter(tag) },
                        isActive = activeFeed == feed
                    )
                    MemoFeed.EXPLORE -> GenericMemosListPane(
                        viewModel = viewModel,
                        modifier = Modifier.testTag("memo_feed_explore"),
                        memos = uiState.exploreMemoList.list.items,
                        isLoading = uiState.exploreMemoList.list.isLoading,
                        isRefreshing = uiState.isRefreshing && uiState.refreshSource == RefreshSource.ExploreMemos,
                        nextPageToken = uiState.exploreMemoList.list.nextPageToken,
                        onLoadMore = { viewModel.loadMoreExploreMemos() },
                        onRefresh = { viewModel.fetchExploreMemos(refresh = true) },
                        onMemoClick = onMemoClick,
                        listState = exploreListState,
                        contentPadding = contentPadding,
                        userProvider = { memo -> uiState.users[memo.creator] },
                        errorTitle = stringResource(R.string.common_error_failed_to_load_explore),
                        isActive = activeFeed == feed
                    )
                }
            }
        },
        overlay = { onMemoClick, showSearchBar, searchExpanded, onSearchExpandedChange, isDualPane, isDetailVisible ->
            AnimatedVisibility(
                visible = (showSearchBar || searchExpanded) && (!searchExpanded || isDualPane || !isDetailVisible),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)) {
                key(activeFeed) {
                    MemoSearchBar(
                        viewModel = viewModel,
                        isExplore = activeFeed == MemoFeed.EXPLORE,
                        placeholder = stringResource(
                            if (activeFeed == MemoFeed.EXPLORE) R.string.memo_search_explore_placeholder
                            else R.string.memo_search_placeholder
                        ),
                        onMemoClick = onMemoClick,
                        onExpandedChange = {
                            isSearchExpanded = it
                            onSearchExpandedChange(it)
                        }
                    )
                }
            }

            // FAB for creating new memo
            if (uiState.session.currUser != null && !searchExpanded) {
                // Animate FAB position only if nav bar can be toggled (onToggleNavBar provided)
                val fabBottomPadding by animateDpAsState(
                    targetValue = if (onToggleNavBar != null && isNavBarVisible) 96.dp else 16.dp,
                    label = "fabBottomPadding"
                )

                ExtendedFloatingActionButton(
                    onClick = {
                        // If drafts exist, show prompt; otherwise show composer directly
                        if (uiState.draft.drafts.isNotEmpty()) {
                            showDraftPrompt = true
                        } else {
                            // Start fresh with a new draft session ID
                            composeNew()
                        }
                    },
                    expanded = isFabExpanded,
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Add,
                            contentDescription = if (isFabExpanded) null
                                else stringResource(R.string.memo_composer_fab_new_memo)
                        )
                    },
                    text = {
                        Text(text = stringResource(R.string.memo_composer_fab_new_memo))
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .testTag("memo_feed_compose")
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = fabBottomPadding)
                )
            }
        })

    // Draft prompt dialog
    if (showDraftPrompt) {
        AlertDialog(
            onDismissRequest = { showDraftPrompt = false },
            title = { Text(stringResource(R.string.drafts_prompt_title)) },
            text = { Text(stringResource(R.string.drafts_prompt_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDraftPrompt = false
                        // Load latest draft
                        val latestDraft = viewModel.draftDelegate.getLatestDraft()
                        val account = uiState.accounts.firstOrNull { it.isActive }
                        if (latestDraft != null && account != null) {
                            openEditor(EditorRequest(account.id, titleRes = R.string.memo_composer_fab_new_memo,
                                draft = latestDraft))
                        } else composeNew()
                    }) {
                    Text(stringResource(R.string.drafts_prompt_continue))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDraftPrompt = false
                        // Start fresh with a new draft session ID
                        composeNew()
                    }) {
                    Text(stringResource(R.string.drafts_prompt_start_fresh))
                }
            })
    }

}

@Composable
private fun MemosListPane(
    viewModel: MemosViewModel,
    listState: LazyListState,
    onMemoClick: (Memo) -> Unit,
    contentPadding: PaddingValues,
    onDraftsCardClick: () -> Unit,
    onHashtagClick: (String) -> Unit,
    isActive: Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    val hasDrafts = uiState.draft.drafts.isNotEmpty()
    val shortcutListState = rememberLazyListState()
    val draftCount = uiState.draft.drafts.size

    GenericMemosListPane(
        viewModel = viewModel,
        modifier = Modifier.testTag("memo_feed_memos"),
        memos = uiState.userMemoList.list.items,
        isLoading = uiState.userMemoList.list.isLoading,
        isRefreshing = uiState.isRefreshing && uiState.refreshSource == RefreshSource.USerMemos,
        nextPageToken = uiState.userMemoList.list.nextPageToken,
        onLoadMore = { viewModel.loadMoreUserMemos() },
        onRefresh = { viewModel.fetchUserMemos(refresh = true) },
        onMemoClick = onMemoClick,
        listState = listState,
        contentPadding = contentPadding,
        errorTitle = stringResource(R.string.common_error_failed_to_load_memos),
        onHashtagClick = onHashtagClick,
        isActive = isActive,
        header = {
            val hasShortcuts = uiState.userMemoList.shortcuts.isNotEmpty()
            val selectedHashtag = uiState.userMemoList.selectedHashtag
            val showFilterRow = hasShortcuts || selectedHashtag != null

            // Sync status: visible only while an activity is ongoing (syncing,
            // pre-downloading, pending writes, offline). Once everything settles
            // it animates away (shrinks) and the compact status icon at the
            // search bar takes over - the check mark appears there. It lives
            // inside the header_section item (not its own LazyColumn item) so
            // the list's 8dp item spacing does not leave a gap after it hides.
            val syncActive = uiState.isSyncing ||
                uiState.preDownloadState is PreDownloadState.Running ||
                uiState.pendingOpsCount > 0 ||
                !uiState.isOnline ||
                uiState.connectionState == org.example.memosm.viewmodel.ConnectionState.RATE_LIMITED

            if (hasDrafts || showFilterRow || syncActive) {
                item(key = "header_section") {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = syncActive,
                            enter = androidx.compose.animation.fadeIn() +
                                androidx.compose.animation.expandVertically(),
                            exit = androidx.compose.animation.fadeOut() +
                                androidx.compose.animation.shrinkVertically()
                        ) {
                            SyncStatusBar(
                                isOnline = uiState.isOnline,
                                isSyncing = uiState.isSyncing,
                                pendingOpsCount = uiState.pendingOpsCount,
                                preDownloadState = uiState.preDownloadState,
                                cachedCount = uiState.textCacheCount,
                                lastSyncTime = uiState.lastSyncTime,
                                connectionState = uiState.connectionState
                            )
                        }

                        // Drafts Card (shown when drafts exist)
                        AnimatedVisibility(
                            visible = hasDrafts,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            DraftsCard(
                                draftCount = draftCount,
                                viewModel = viewModel,
                                onCardClick = onDraftsCardClick
                            )
                        }

                        // Horizontal Shortcut Row
                        AnimatedVisibility(
                            visible = showFilterRow,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            LazyRow(
                                state = shortcutListState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        val startGradient = Brush.horizontalGradient(
                                            0f to Color.Transparent, 0.15f to Color.Black
                                        )
                                        val endGradient = Brush.horizontalGradient(
                                            0.85f to Color.Black, 1f to Color.Transparent
                                        )
                                        if (shortcutListState.canScrollBackward) {
                                            drawRect(
                                                brush = startGradient, blendMode = BlendMode.DstIn
                                            )
                                        }
                                        if (shortcutListState.canScrollForward) {
                                            drawRect(
                                                brush = endGradient, blendMode = BlendMode.DstIn
                                            )
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (selectedHashtag != null) {
                                    item {
                                        FilterChip(
                                            selected = true,
                                            onClick = {
                                                viewModel.shortcutDelegate.toggleHashtagFilter(
                                                    selectedHashtag
                                                )
                                            },
                                            label = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Tag,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(selectedHashtag.removePrefix("#"))
                                                }
                                            },
                                            shape = RoundedCornerShape(16.dp),
                                            trailingIcon = {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        )
                                    }
                                }

                                itemsIndexed(uiState.userMemoList.shortcuts, key = { index, it ->
                                    val baseKey = it.name.takeUnless { n -> n.isNullOrBlank() }
                                        ?: "${it.title?.hashCode() ?: 0}_${it.filter?.hashCode() ?: 0}"
                                    "${baseKey}_$index"
                                }) { index, shortcut ->
                                    val isSelected =
                                        uiState.userMemoList.selectedShortcut?.name == shortcut.name
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = {
                                            viewModel.shortcutDelegate.toggleShortcutFilter(
                                                shortcut
                                            )
                                        },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Outlined.Shortcut,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(shortcut.title ?: "")
                                            }
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        trailingIcon = if (isSelected) {
                                            {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        } else null)
                                }
                            }
                        }
                    }
                }
            }
        })

}
