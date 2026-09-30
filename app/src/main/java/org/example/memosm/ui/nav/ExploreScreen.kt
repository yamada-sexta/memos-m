package org.example.memosm.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.ui.component.GenericMemosListPane
import org.example.memosm.ui.component.MemoSearchBar
import org.example.memosm.ui.component.MemosScaffold
import org.example.memosm.viewmodel.MemosViewModel

@Composable
fun ExploreScreen(
    viewModel: MemosViewModel,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 80.dp else 16.dp, label = "BottomPadding"
    )

    // Double tap refresh logic: scroll to top
    var lastProcessedTrigger by rememberSaveable { mutableLongStateOf(uiState.refreshTrigger) }
    LaunchedEffect(uiState.refreshTrigger) {
        if (uiState.refreshTrigger > lastProcessedTrigger) {
            listState.animateScrollToItem(0)
        }
        lastProcessedTrigger = uiState.refreshTrigger
    }

    MemosScaffold(
        viewModel = viewModel,
        memos = uiState.exploreMemoList.list.items,
        listState = listState,
        onToggleNavBar = { onToggleNavBar?.invoke(it) },
        isNavBarVisible = isNavBarVisible,
        listPane = { onMemoClick ->
            GenericMemosListPane(
                viewModel = viewModel,
                memos = uiState.exploreMemoList.list.items,
                isLoading = uiState.exploreMemoList.list.isLoading,
                isRefreshing = uiState.isRefreshing,
                nextPageToken = uiState.exploreMemoList.list.nextPageToken,
                onLoadMore = { viewModel.loadMoreExploreMemos() },
                onRefresh = { viewModel.fetchExploreMemos(refresh = true) },
                onMemoClick = onMemoClick,
                listState = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, top = 88.dp, end = 16.dp, bottom = bottomPadding
                ),
                userProvider = { memo -> uiState.users[memo.creator] },
                errorTitle = stringResource(R.string.common_error_failed_to_load_explore)
            )
        },
        overlay = { onMemoClick, showSearchBar, isSearchExpanded, onSearchExpandedChange, isDualPane, isDetailVisible ->
            AnimatedVisibility(
                visible = showSearchBar && (!isSearchExpanded || isDualPane || !isDetailVisible),
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)) {
                MemoSearchBar(
                    viewModel = viewModel,
                    isExplore = true,
                    onMemoClick = onMemoClick,
                    onExpandedChange = onSearchExpandedChange,
                    placeholder = stringResource(R.string.memo_search_explore_placeholder)
                )
            }
        })
}
