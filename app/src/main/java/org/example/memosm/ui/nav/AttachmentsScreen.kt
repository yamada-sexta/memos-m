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
import org.example.memosm.ui.component.rememberStaggeredGridScrollContext
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(
    viewModel: MemosViewModel,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyStaggeredGridState()

    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenInitialIndex by remember { mutableStateOf(0) }

    // Limits for zooming
    val minCellWidth = 100.dp
    val maxCellWidth = 600.dp

    // Animate the cell width changes for a smoother transition
    val animatedCellWidth by animateDpAsState(
        targetValue = uiState.attachmentList.cellWidth.dp, animationSpec = spring(
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
        viewModel.fetchAttachments(refresh = false)
    }

    // Double tap refresh logic: scroll to top
    // We keep track of the last processed trigger to avoid scrolling to top 
    // when just navigating back to this screen.
    var lastProcessedTrigger by rememberSaveable { mutableLongStateOf(uiState.refreshTrigger) }
    LaunchedEffect(uiState.refreshTrigger) {
        if (uiState.refreshTrigger > lastProcessedTrigger) {
            listState.animateScrollToItem(0)
        }
        lastProcessedTrigger = uiState.refreshTrigger
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItemsCount = listState.layoutInfo.totalItemsCount
            if (totalItemsCount == 0 || uiState.attachmentList.list.isLoading) return@derivedStateOf false
            if (!listState.isScrollInProgress) return@derivedStateOf false

            val lastVisibleItem =
                listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false

            !uiState.attachmentList.list.nextPageToken.isNullOrBlank() &&
                lastVisibleItem.index >= totalItemsCount - 5
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            viewModel.loadMoreAttachments()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing, onRefresh = {
            viewModel.fetchAttachments(refresh = true)
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
                                            (uiState.attachmentList.cellWidth * zoomFactor).coerceIn(
                                                minCellWidth.value, maxCellWidth.value
                                            )
                                        viewModel.updateAttachmentCellWidth(newWidth)
                                        // Consume the event to prevent the list from scrolling while zooming
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                    }
                }) {
            if (uiState.attachmentList.list.items.isEmpty() && uiState.attachmentList.list.isLoading && !uiState.isRefreshing) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.attachmentList.list.items.isEmpty() && !uiState.attachmentList.list.isLoading) {
                if (uiState.error != null) {
                    ErrorView(
                        title = stringResource(R.string.common_error_failed_to_load_attachments),
                        message = uiState.error!!,
                        onRetry = { viewModel.fetchAttachments(refresh = false) },
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
                        items = uiState.attachmentList.list.items,
                        key = { index, it -> "${it.name ?: it.filename}_$index" }) { index, attachment ->
                        val key = attachment.name ?: attachment.filename
                        val currentScale = uiState.attachmentList.cellWidth
                        val cachedRatio =
                            uiState.attachmentList.aspectRatios[currentScale]?.get(key)
                        val ratio = cachedRatio ?: 1.0f



                        AttachmentCard(
                            attachment = attachment,
                            token = uiState.session.token,
                            hostUrl = uiState.session.hostUrl,
                            compactMode = AttachmentCompactMode.Width,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(ratio)
                                .animateItem(),
                            onClick = {
                                fullScreenInitialIndex = index
                                showFullScreenViewer = true
                            },
                            onRatioAvailable = { newRatio, isExact ->
                                if (isExact) {
                                    viewModel.updateAttachmentAspectRatio(
                                        currentScale, key, newRatio
                                    )
                                }
                            })
                    }

                    if (uiState.attachmentList.list.isLoading && uiState.attachmentList.list.items.isNotEmpty()) {
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
                    } else if (!uiState.attachmentList.list.isLoading && uiState.attachmentList.list.nextPageToken.isNullOrBlank() && uiState.attachmentList.list.items.isNotEmpty()) {
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
            attachments = uiState.attachmentList.list.items,
            initialIndex = fullScreenInitialIndex,
            token = uiState.session.token,
            hostUrl = uiState.session.hostUrl,
            onDismiss = { showFullScreenViewer = false },
            onPageChanged = { index ->
                if (
                    index >= uiState.attachmentList.list.items.size - 5 &&
                    !uiState.attachmentList.list.nextPageToken.isNullOrBlank() &&
                    !uiState.attachmentList.list.isLoading
                ) {
                    viewModel.loadMoreAttachments()
                }
            }
        )
    }
}
