package org.example.memosm.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow

@Composable
fun rememberScrollContext(
    listState: LazyListState, onScrollDown: () -> Unit = {}, onScrollUp: () -> Unit = {}
): ScrollContext {
    val scrollContext = remember { ScrollContext() }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (currentIndex, currentOffset) ->
            // Only treat user-initiated scrolling as a direction change.
            // Programmatic layout shifts - items inserted above (e.g. the
            // header_section appearing once shortcuts/sync status load) - move
            // the indices without any scroll gesture, and must not flip the
            // direction state and hide the search bar / nav bar.
            if (!listState.isScrollInProgress) {
                previousIndex = currentIndex
                previousScrollOffset = currentOffset
                return@collect
            }
            if (currentIndex > previousIndex) {
                scrollContext.isScrollingDown = true
                onScrollDown()
            } else if (currentIndex < previousIndex) {
                scrollContext.isScrollingDown = false
                onScrollUp()
            } else if (currentOffset > previousScrollOffset + 10) {
                scrollContext.isScrollingDown = true
                onScrollDown()
            } else if (currentOffset < previousScrollOffset - 10) {
                scrollContext.isScrollingDown = false
                onScrollUp()
            }

            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    return scrollContext
}

@Composable
fun rememberStaggeredGridScrollContext(
    listState: LazyStaggeredGridState, onScrollDown: () -> Unit = {}, onScrollUp: () -> Unit = {}
): ScrollContext {
    val scrollContext = remember { ScrollContext() }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (currentIndex, currentOffset) ->
            // Only treat user-initiated scrolling as a direction change.
            // Programmatic layout shifts - items inserted above (e.g. the
            // header_section appearing once shortcuts/sync status load) - move
            // the indices without any scroll gesture, and must not flip the
            // direction state and hide the search bar / nav bar.
            if (!listState.isScrollInProgress) {
                previousIndex = currentIndex
                previousScrollOffset = currentOffset
                return@collect
            }
            if (currentIndex > previousIndex) {
                scrollContext.isScrollingDown = true
                onScrollDown()
            } else if (currentIndex < previousIndex) {
                scrollContext.isScrollingDown = false
                onScrollUp()
            } else if (currentOffset > previousScrollOffset + 10) {
                scrollContext.isScrollingDown = true
                onScrollDown()
            } else if (currentOffset < previousScrollOffset - 10) {
                scrollContext.isScrollingDown = false
                onScrollUp()
            }

            previousIndex = currentIndex
            previousScrollOffset = currentOffset
        }
    }

    return scrollContext
}

class ScrollContext {
    var isScrollingDown by mutableStateOf(false)
}
