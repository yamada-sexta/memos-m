package org.example.memosm.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedMemosScreen(
    modifier: Modifier = Modifier,
    viewModel: MemosViewModel,
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Refresh archived memos on start
    LaunchedEffect(uiState.accountGeneration, uiState.isOnline) {
        viewModel.fetchArchivedMemos(refresh = true)
    }

    Box(modifier = modifier.fillMaxSize()) {
        MemosScaffold(
            viewModel = viewModel,
            memos = uiState.archivedMemoList.list.items,
            listState = listState,
            isNavBarVisible = false,
            topBar = { isDetailVisible, isDualPane ->
                if (!isDetailVisible || isDualPane) {
                    TopAppBar(
                        title = {
                            Text(stringResource(R.string.profile_archived))
                        },
                        navigationIcon = {
                            ProfileBackButton(onClick = onBack)
                        },
                    )
                }
            },
            listPane = { onMemoClick ->
                GenericMemosListPane(
                    viewModel = viewModel,
                    memos = uiState.archivedMemoList.list.items,
                    isLoading = uiState.archivedMemoList.list.isLoading,
                    isRefreshing = uiState.isRefreshing,
                    nextPageToken = uiState.archivedMemoList.list.nextPageToken,
                    onLoadMore = { viewModel.loadMoreArchivedMemos() },
                    onRefresh = { viewModel.fetchArchivedMemos(refresh = true) },
                    onMemoClick = onMemoClick,
                    listState = listState,
                    userProvider = { uiState.session.currUser },
                    contentPadding = PaddingValues(16.dp)
                )
            })
    }
}
