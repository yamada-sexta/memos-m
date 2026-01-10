package org.example.memosm.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.memosm.model.Memo
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun MemosListScreen(viewModel: MemosViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val tagListState = rememberLazyListState()

    // Double tap refresh logic: scroll to top
    // We keep track of the last processed trigger to avoid scrolling to top 
    // when just navigating back to this screen.
    var lastProcessedTrigger by remember { mutableLongStateOf(uiState.refreshTrigger) }
    LaunchedEffect(uiState.refreshTrigger) {
        if (uiState.refreshTrigger > lastProcessedTrigger) {
            listState.animateScrollToItem(0)
        }
        lastProcessedTrigger = uiState.refreshTrigger
    }

    val scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).copy(
        defaultPanePreferredWidth = 600.dp
    )

    val navigator = rememberListDetailPaneScaffoldNavigator<MemoKey>(
        scaffoldDirective = scaffoldDirective
    )
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Sync selected memo with navigator
    LaunchedEffect(navigator.currentDestination) {
        // Clear focus whenever navigation happens to prevent unwanted keyboard/focus
        focusManager.clearFocus()

        val currentMemoKey = navigator.currentDestination?.contentKey
        if (currentMemoKey != null) {
            val selectedId =
                uiState.selectedMemo?.let { it.name ?: it.content.hashCode().toString() }
            if (currentMemoKey.id != selectedId) {
                // Find the memo in current list if possible, or just use the name to fetch
                val memo = uiState.memos.find {
                    (it.name ?: it.content.hashCode().toString()) == currentMemoKey.id
                }
                if (memo != null) {
                    viewModel.selectMemo(memo)
                }
            }
        } else if (uiState.selectedMemo != null) {
            viewModel.clearSelectedMemo()
        }
    }

    // Aggressively clear focus when this screen is entered or returned to
    LaunchedEffect(Unit) {
        repeat(5) {
            focusManager.clearFocus()
            delay(100)
        }
    }

    NavigableListDetailPaneScaffold(navigator = navigator, listPane = {
        AnimatedPane {
            MemosListPane(
                viewModel = viewModel,
                listState = listState,
                tagListState = tagListState,
                onMemoClick = { memo ->
                    focusManager.clearFocus()
                    scope.launch {
                        val id = memo.name ?: memo.content.hashCode().toString()
                        navigator.navigateTo(
                            ListDetailPaneScaffoldRole.Detail, MemoKey(id)
                        )
                    }
                })
        }
    }, detailPane = {
        AnimatedPane {
            val currentMemoKey = navigator.currentDestination?.contentKey
            val isListVisible =
                navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Expanded
            val isDetailVisible =
                navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded
            val isDualPane = isListVisible && isDetailVisible

            AnimatedContent(
                targetState = currentMemoKey, transitionSpec = {
                    if (isDualPane) {
                        if (initialState == null) {
                            // First time appearing: scale + fade
                            (fadeIn(animationSpec = tween(220, delayMillis = 90)) + scaleIn(
                                initialScale = 0.92f, animationSpec = tween(220, delayMillis = 90)
                            )).togetherWith(fadeOut(animationSpec = tween(90)))
                        } else {
                            // Switching between memos: smooth crossfade
                            fadeIn(animationSpec = tween(300)).togetherWith(
                                fadeOut(animationSpec = tween(300))
                            )
                        }
                    } else {
                        // Mobile: swipe up (slide from bottom)
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
                val memo = remember(memoKey, uiState.memos) {
                    memoKey?.let { key ->
                        uiState.memos.find {
                            (it.name ?: it.content.hashCode().toString()) == key.id
                        }
                    }
                }

                if (memo != null) {
                    MemoDetailPane(
                        memo = memo,
                        comments = uiState.selectedMemoComments,
                        isLoadingComments = uiState.isLoadingComments,
                        token = uiState.token,
                        showBackButton = navigator.canNavigateBack(),
                        onBack = {
                            focusManager.clearFocus()
                            scope.launch {
                                navigator.navigateBack()
                            }
                        },
                        viewModel = viewModel
                    )
                } else if (isDualPane) {
                    MemoDetailPlaceholder()
                }
            }
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemosListPane(
    viewModel: MemosViewModel,
    listState: LazyListState,
    tagListState: LazyListState,
    onMemoClick: (Memo) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current

    var memoToEdit by remember { mutableStateOf<Memo?>(null) }
    var memoToDelete by remember { mutableStateOf<Memo?>(null) }

    // Use a SideEffect or LaunchedEffect with listState to trigger loads
    val shouldLoadMore = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItemsNumber = layoutInfo.totalItemsCount
            val lastVisibleItemIndex = (layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) + 1

            // Trigger when within 5 items of the end, and we have items, and we're not already loading
            totalItemsNumber > 0 && lastVisibleItemIndex >= totalItemsNumber - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = {
            viewModel.refreshAll()
        },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }) {
        if (uiState.isLoading && uiState.memos.isEmpty() && !uiState.isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top input card - Show even if there is an error
                item {
                    if (uiState.isDraftLoaded) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 16.dp), contentAlignment = Alignment.Center
                        ) {
                            Card(modifier = Modifier.widthIn(max = 800.dp)) {
                                // Key the composer by whether a draft exists. 
                                // When a publish is successful, draftMemo becomes null, 
                                // triggering a reset of the internal composer state.
                                key(uiState.draftMemo == null) {
                                    MemoComposer(
                                        onPublish = { content, visibility, attachments, location ->
                                        viewModel.createMemo(content, visibility, attachments, location)
                                    },
                                        onUploadFile = { uri, context ->
                                            viewModel.uploadAttachment(uri, context)
                                        },
                                        onDraftChanged = { content, visibility, attachments, location ->
                                            viewModel.saveDraft(content, visibility, attachments, location)
                                        },
                                        availableTags = uiState.userStats?.tagCount?.keys
                                            ?: emptySet(),
                                        token = uiState.token,
                                        modifier = Modifier.padding(16.dp),
                                        isPosting = uiState.isPosting,
                                        initialContent = uiState.draftMemo?.content ?: "",
                                        initialAttachments = uiState.draftMemo?.attachments
                                            ?: emptyList(),
                                        initialVisibility = uiState.draftMemo?.visibility
                                            ?: uiState.userSettings?.memoVisibility ?: "PRIVATE",
                                        initialLocation = uiState.draftMemo?.location,
                                        submitLabel = "Publish"
                                    )
                                }
                            }
                        }
                    }
                }

                // Horizontal Tag Row - Edge to edge with fading hint
                val tagMap = uiState.userStats?.tagCount ?: emptyMap()
                item(key = "tag_row") {
                    AnimatedVisibility(
                        visible = tagMap.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        val sortedTags = remember(tagMap) {
                            tagMap.keys.toList().sortedByDescending { tagMap[it] ?: 0 }
                        }

                        LazyRow(
                            state = tagListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .drawWithContent {
                                    drawContent()
                                    // Fading edge hint - more aggressive (15% fade)
                                    val startGradient = Brush.horizontalGradient(
                                        0f to Color.Transparent, 0.15f to Color.Black
                                    )
                                    val endGradient = Brush.horizontalGradient(
                                        0.85f to Color.Black, 1f to Color.Transparent
                                    )
                                    if (tagListState.canScrollBackward) {
                                        drawRect(brush = startGradient, blendMode = BlendMode.DstIn)
                                    }
                                    if (tagListState.canScrollForward) {
                                        drawRect(brush = endGradient, blendMode = BlendMode.DstIn)
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            items(sortedTags, key = { it }) { tag ->
                                val count = tagMap[tag] ?: 0
                                val isSelected = tag in uiState.selectedTags
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.toggleTagFilter(tag) },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("#$tag")
                                            if (count > 0) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = count.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                        alpha = 0.7f
                                                    )
                                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                        alpha = 0.7f
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                    trailingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null)
                            }
                        }
                    }
                }

                if (uiState.error != null && uiState.memos.isEmpty()) {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = uiState.error!!,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                            Button(onClick = { viewModel.refreshAll() }) {
                                Text("Retry")
                            }
                        }
                    }
                } else {
                    items(uiState.memos, key = { it.name ?: it.content.hashCode() }) { memo ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val isOwner = memo.creator == uiState.user?.name
                            MemoItem(
                                memo = memo, user = null, // Profile pic removed from Memos tab
                                token = uiState.token, colors = if (memo == uiState.selectedMemo) {
                                    CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                } else {
                                    CardDefaults.cardColors()
                                }, onClick = {
                                    focusManager.clearFocus()
                                    onMemoClick(memo)
                                }, onEdit = if (isOwner) {
                                    { memoToEdit = memo }
                                } else null, onDelete = if (isOwner) {
                                    { memoToDelete = memo }
                                } else null, modifier = Modifier.widthIn(max = 800.dp))
                        }
                    }

                    if (uiState.isLoading && uiState.memos.isNotEmpty()) {
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
                    }
                }
            }
        }
    }

    memoToEdit?.let { memo ->
        MemoEditDialog(
            memo = memo, onDismiss = { memoToEdit = null }, viewModel = viewModel
        )
    }

    memoToDelete?.let { memo ->
        DeleteConfirmationDialog(memo = memo, onDismiss = { memoToDelete = null }, onConfirm = {
            viewModel.deleteMemo(memo)
            memoToDelete = null
        })
    }
}
