package org.example.memosm.ui.component

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.example.memosm.state.MemosListControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.SessionControls
import org.example.memosm.state.AppSettingsControls

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.ArchivedMemosScreen(
    modifier: Modifier = Modifier,
    controls: MemosListControls,
    actionControls: MemoActionControls?,
    sessionControls: SessionControls?,
    appSettingsControls: AppSettingsControls?,
    onBack: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val listState = rememberLazyListState()

    // Refresh archived memos on start
    LaunchedEffect(Unit) {
        controls.fetch(true)
    }

    Box(modifier = modifier.fillMaxSize()) {
        MemosScaffold(
            controls = controls,
            actionControls = actionControls!!,
            sessionControls = sessionControls!!,
            draftControls = null,
            appSettingsControls = appSettingsControls,
            memos = controls.state.list.items,
            listState = listState,
            onToggleNavBar = { onToggleNavBar?.invoke(it) },
            topBar = { isDetailVisible, isDualPane ->
                if (!isDetailVisible || isDualPane) {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.profile_archived),
                                modifier = Modifier.sharedBounds(
                                    rememberSharedContentState(key = "archive_text"),
                                    animatedVisibilityScope = animatedVisibilityScope,
                                    boundsTransform = { _, _ ->
                                        tween(durationMillis = 300)
                                    }
                                )
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                    )
                }
            },
            listPane = { onMemoClick ->
                GenericMemosListPane(
                    controls = controls,
                    actionControls = actionControls,
                    sessionControls = sessionControls,
                    appSettingsControls = appSettingsControls,
                    memos = controls.state.list.items,
                    isLoading = controls.state.list.isLoading,
                    isRefreshing = false, // TODO uiState.isRefreshing
                    nextPageToken = controls.state.list.nextPageToken,
                    onLoadMore = { controls.loadMore() },
                    onRefresh = { controls.fetch(true) },
                    onMemoClick = onMemoClick,
                    listState = listState,
                    userProvider = { sessionControls.state.currUser },
                    contentPadding = PaddingValues(16.dp),
                    isOffline = controls.state.list.isOffline,
                    errorMessage = controls.state.list.errorMessage
                )
            })
    }
}
