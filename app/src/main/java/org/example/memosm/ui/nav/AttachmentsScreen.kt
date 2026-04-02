package org.example.memosm.ui.nav

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.ui.component.ErrorView
import org.example.memosm.ui.component.item.AttachmentCard
import org.example.memosm.ui.component.item.AttachmentCompactMode
import org.example.memosm.ui.component.item.media.FullScreenAttachmentViewer
import org.example.memosm.state.AttachmentControls
import org.example.memosm.state.SessionControls
import org.example.memosm.ui.component.rememberStaggeredGridScrollContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(
    controls: AttachmentControls,
    sessionControls: SessionControls,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    val listState = rememberLazyStaggeredGridState()

    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenInitialIndex by remember { mutableStateOf(0) }

    // Limits for zooming
    val minCellWidth = 100.dp
    val maxCellWidth = 600.dp

    // Animate the cell width changes for a smoother transition
    val animatedCellWidth by animateDpAsState(
        targetValue = controls.state.cellWidth.dp, animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow
        ), label = "CellWidthAnimation"
    )

    // Scroll direction tracking for nav bar visibility
    rememberStaggeredGridScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) })

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 80.dp else 16.dp, label = "BottomPadding"
    )

    LaunchedEffect(Unit) {
        controls.fetch(false)
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            if (totalItemsCount == 0 || controls.state.list.isLoading) return@derivedStateOf false

            val lastVisibleItem =
                listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false

            controls.state.list.nextPageToken != null && !controls.state.list.nextPageToken.isNullOrBlank() && lastVisibleItem.index >= totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            controls.loadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = false, onRefresh = {
            controls.fetch(true)
        }, modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Detect pinch-to-zoom gestures globally using the Initial pass
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressedChanges = event.changes.filter { it.pressed }

                            if (pressedChanges.size >= 2) {
                                val p1 = pressedChanges[0].position
                                val p2 = pressedChanges[1].position
                                val p1Prev = pressedChanges[0].previousPosition
                                val p2Prev = pressedChanges[1].previousPosition

                                val currentDistance = (p1 - p2).getDistance()
                                val previousDistance = (p1Prev - p2Prev).getDistance()

                                if (previousDistance > 0f && currentDistance > 0f) {
                                    val zoomFactor = currentDistance / previousDistance
                                    if (zoomFactor != 1f) {
                                        val newWidth =
                                            (controls.state.cellWidth * zoomFactor).coerceIn(
                                                minCellWidth.value, maxCellWidth.value
                                            )
                                        controls.updateCellWidth(newWidth)
                                        // Consume the event to prevent the list from scrolling while zooming
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                }) {
            if (controls.state.list.items.isEmpty() && controls.state.list.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (controls.state.list.items.isEmpty() && !controls.state.list.isLoading) {
                if (controls.state.list.errorMessage != null) {
                    ErrorView(
                        title = stringResource(R.string.common_error_failed_to_load_attachments),
                        message = controls.state.list.errorMessage!!,
                        onRetry = { controls.fetch(false) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.attachments_none_found))
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(animatedCellWidth),
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, top = 12.dp, end = 12.dp, bottom = bottomPadding
                    ),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = controls.state.list.items,
                        key = { index, it -> "${it.name ?: it.filename}_$index" }) { index, attachment ->
                        val key = attachment.name ?: attachment.filename
                        val ratio = 1.0f

                        AttachmentCard(
                            attachment = attachment,
                            token = sessionControls.state.token,
                            hostUrl = sessionControls.state.hostUrl,
                            compactMode = AttachmentCompactMode.Width,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .animateItem(),
                            onClick = {
                                fullScreenInitialIndex = index
                                showFullScreenViewer = true
                            },
                            onRatioAvailable = { _, _ -> }
                        )
                    }

                    if (controls.state.list.isLoading && controls.state.list.items.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (!controls.state.list.isLoading && controls.state.list.nextPageToken.isNullOrBlank() && controls.state.list.items.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.memo_list_end),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFullScreenViewer) {
        FullScreenAttachmentViewer(
            attachments = controls.state.list.items,
            initialIndex = fullScreenInitialIndex,
            token = sessionControls.state.token,
            hostUrl = sessionControls.state.hostUrl,
            onDismiss = { showFullScreenViewer = false },
            onPageChanged = { index ->
                if (index >= controls.state.list.items.size - 5 && controls.state.list.nextPageToken != null && !controls.state.list.isLoading) {
                    controls.loadMore()
                }
            }
        )
    }
}
