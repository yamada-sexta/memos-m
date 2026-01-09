package org.example.memosm.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.memosm.model.Memo
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ExploreScreen(viewModel: MemosViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    
    val scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()).copy(
        defaultPanePreferredWidth = 600.dp
    )
    
    val navigator = rememberListDetailPaneScaffoldNavigator<MemoKey>(
        scaffoldDirective = scaffoldDirective
    )
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(navigator.currentDestination) {
        val currentMemoKey = navigator.currentDestination?.contentKey
        if (currentMemoKey?.name != uiState.selectedMemo?.name) {
            if (currentMemoKey != null) {
                val memo = uiState.exploreMemos.find { it.name == currentMemoKey.name }
                if (memo != null) {
                    viewModel.selectMemo(memo)
                }
            } else {
                viewModel.clearSelectedMemo()
            }
        }
    }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            ExploreMemosListPane(
                viewModel = viewModel,
                onMemoClick = { memo ->
                    focusManager.clearFocus()
                    scope.launch {
                        memo.name?.let { name ->
                            navigator.navigateTo(
                                ListDetailPaneScaffoldRole.Detail, MemoKey(name)
                            )
                        }
                    }
                })
        },
        detailPane = {
            val selectedMemo = uiState.selectedMemo
            // Tablet mode: both list and detail are visible side-by-side
            val isTabletMode = navigator.scaffoldValue.primary != navigator.scaffoldValue.secondary
            val isVisible = navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] == PaneAdaptedValue.Expanded

            AnimatedVisibility(
                visible = isVisible,
                enter = if (isTabletMode) {
                    // Tablet: slide from right
                    slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300))
                } else {
                    // Mobile: slide from bottom
                    slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(tween(300))
                },
                exit = if (isTabletMode) {
                    // Tablet: slide to right
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(300))
                } else {
                    // Mobile: slide to bottom
                    slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(tween(300))
                }
            ) {
                AnimatedContent(
                    targetState = selectedMemo,
                    transitionSpec = {
                        if (isTabletMode) {
                            // Tablet: slide from right when changing selection
                            (slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn())
                                .togetherWith(slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(300)) + fadeOut())
                        } else {
                            // Mobile: simple fade for memo changes (main animation is on visibility)
                            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                        }
                    },
                    label = "ExploreDetailAnimation"
                ) { memo ->
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
                            })
                    } else {
                        MemoDetailPlaceholder()
                    }
                }
            }
        }
    )
}

@Composable
private fun ExploreMemosListPane(
    viewModel: MemosViewModel,
    onMemoClick: (Memo) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItem =
                listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
            lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadMoreExplore()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                })
            }, contentAlignment = Alignment.TopCenter
    ) {
        if (uiState.isExploring && uiState.exploreMemos.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                items(uiState.exploreMemos, key = { it.name ?: it.content.hashCode() }) { memo ->
                    Box(
                        modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center
                    ) {
                        MemoItem(
                            memo = memo,
                            user = uiState.users[memo.creator],
                            token = uiState.token,
                            isSelected = memo == uiState.selectedMemo,
                            onClick = {
                                focusManager.clearFocus()
                                onMemoClick(memo)
                            },
                            modifier = Modifier.widthIn(max = 800.dp)
                        )
                    }
                }

                if (uiState.isExploring && uiState.exploreMemos.isNotEmpty()) {
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
