package org.example.memosm.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Memo
import org.example.memosm.ui.component.GenericMemosListPane
import org.example.memosm.ui.component.MemoSearchBar
import org.example.memosm.ui.component.MemosScaffold
import org.example.memosm.ui.component.composer.ComposerMode
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.item.DraftsCard
import org.example.memosm.state.AppSettingsControls
import org.example.memosm.state.DraftControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.MemosListControls
import org.example.memosm.state.SessionControls
import org.example.memosm.ui.component.MemosCommon

@Composable
fun MemosScreen(
    controls: MemosListControls,
    actionControls: MemoActionControls,
    sessionControls: SessionControls,
    appSettingsControls: AppSettingsControls,
    draftControls: DraftControls,
    onToggleNavBar: ((Boolean) -> Unit)?,
    isNavBarVisible: Boolean,
    openComposer: Boolean,
    onComposerOpened: () -> Unit
) {
    MemosCommon(
        controls = controls,
        actionControls = actionControls,
        sessionControls = sessionControls,
        appSettingsControls = appSettingsControls,
        draftControls = draftControls,
        title = "Memos",
        memoListState = controls.state,
        onLoadMore = { controls.loadMore() },
        onRefresh = { controls.fetch(true) },
        showComposer = true,
        showUserStats = true,
        onToggleNavBar = onToggleNavBar,
        isNavBarVisible = isNavBarVisible,
        openComposer = openComposer,
        onComposerOpened = onComposerOpened
    )
}


