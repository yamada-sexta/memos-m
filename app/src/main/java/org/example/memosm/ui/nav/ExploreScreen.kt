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
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.MemosListControls
import org.example.memosm.state.SessionControls
import org.example.memosm.ui.component.MemosCommon

@Composable
fun ExploreScreen(
    controls: MemosListControls,
    actionControls: MemoActionControls,
    sessionControls: SessionControls,
    onToggleNavBar: ((Boolean) -> Unit)?,
    isNavBarVisible: Boolean,
) {
    MemosCommon(
        controls = controls,
        actionControls = actionControls,
        sessionControls = sessionControls,
        appSettingsControls = null,
        draftControls = null,
        title = stringResource(R.string.nav_explore),
        memoListState = controls.state,
        onLoadMore = { controls.loadMore() },
        onRefresh = { controls.fetch(true) },
        showComposer = false,
        showUserStats = false,
        onToggleNavBar = onToggleNavBar,
        isNavBarVisible = isNavBarVisible,
        openComposer = false,
        onComposerOpened = {}
    )
}
