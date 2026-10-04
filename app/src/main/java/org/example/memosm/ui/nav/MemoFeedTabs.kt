package org.example.memosm.ui.nav

import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch
import org.example.memosm.R

enum class MemoFeed(val labelRes: Int) {
    MEMOS(R.string.nav_memos),
    EXPLORE(R.string.nav_explore)
}

@Composable
internal fun MemoFeedTabs(pagerState: PagerState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    PrimaryTabRow(
        selectedTabIndex = pagerState.currentPage,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.primary,
        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        MemoFeed.entries.forEach { feed ->
            val selected = pagerState.currentPage == feed.ordinal
            Tab(
                selected = selected,
                onClick = {
                    focusManager.clearFocus()
                    scope.launch { pagerState.animateScrollToPage(feed.ordinal) }
                },
                selectedContentColor = MaterialTheme.colorScheme.onSurface,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = {
                    Text(
                        text = stringResource(feed.labelRes),
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}
