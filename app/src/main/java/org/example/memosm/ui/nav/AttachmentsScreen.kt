package org.example.memosm.ui.nav

import android.text.format.DateFormat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import org.example.memosm.R
import org.example.memosm.ui.component.ErrorView
import org.example.memosm.ui.component.item.AttachmentCard
import org.example.memosm.ui.component.item.AttachmentCompactMode
import org.example.memosm.ui.component.item.media.AttachmentOrigins
import org.example.memosm.ui.component.item.media.FullScreenAttachmentViewer
import org.example.memosm.ui.component.item.media.LocalAccountMediaIdentity
import org.example.memosm.ui.component.item.media.attachmentOrigin
import org.example.memosm.ui.component.rememberScrollContext
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.isOffline
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class TimelineLayout(
    val days: List<AttachmentDay>,
    val ratios: Map<String, Float>,
    val width: Float,
    val targetHeight: Float,
) {
    fun entries() = attachmentTimelineEntries(days, ratios, width, targetHeight)
}

@Composable
private fun rememberTimelineEntries(
    listState: LazyListState,
    days: List<AttachmentDay>,
    ratios: Map<String, Float>,
    width: Float,
    targetHeight: Float,
): List<AttachmentTimelineEntry> {
    val requestedLayout by rememberUpdatedState(TimelineLayout(days, ratios, width, targetHeight))
    val initialLayout = remember(listState) { requestedLayout }
    var entries by remember(listState) { mutableStateOf(initialLayout.entries()) }

    LaunchedEffect(listState) {
        var displayedLayout = initialLayout
        snapshotFlow { requestedLayout to listState.isScrollInProgress }.collect { (requested, scrolling) ->
            // Thumbnail dimensions arrive independently. Keep the current proportions during
            // dragging/flinging, while still allowing fetched pages to extend the list.
            val nextLayout = if (scrolling) requested.copy(ratios = displayedLayout.ratios) else requested
            if (nextLayout == displayedLayout) return@collect
            val nextEntries = nextLayout.entries()
            val visibleKey =
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == listState.firstVisibleItemIndex }
                    ?.key
            val anchor = entries.firstOrNull { it.key == visibleKey }

            // LazyColumn preserves surviving keys itself. Only restore an attachment anchor
            // when idle regrouping removes its row key; requestScrollToItem cancels scrolling.
            if (!scrolling && anchor != null && nextEntries.none { it.key == anchor.key }) {
                val newIndex =
                    when (anchor) {
                        is AttachmentTimelineEntry.Row -> timelineRowIndex(nextEntries, anchor.attachments.first().key)
                        else -> null
                    }
                if (newIndex != null) {
                    listState.requestScrollToItem(newIndex, listState.firstVisibleItemScrollOffset)
                }
            }
            entries = nextEntries
            displayedLayout = nextLayout
        }
    }
    return entries
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentsScreen(
    viewModel: MemosViewModel,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    isNavBarVisible: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val attachmentPages = viewModel.attachments.collectAsLazyPagingItems()
    val attachmentItems = attachmentPages.itemSnapshotList.items
    val loading = attachmentPages.loadState.refresh is LoadState.Loading || attachmentPages.loadState.append is LoadState.Loading
    val loadError =
        (attachmentPages.loadState.refresh as? LoadState.Error)?.error
            ?: (attachmentPages.loadState.append as? LoadState.Error)?.error
    val accountIdentity = LocalAccountMediaIdentity.current
    val listState = key(accountIdentity) { rememberLazyListState() }
    val zone = ZoneId.systemDefault()
    val days =
        remember(attachmentItems, zone) {
            attachmentDays(attachmentItems, zone)
        }
    val orderedItems = remember(days) { days.flatMap { it.attachments } }
    val orderedAttachments = remember(orderedItems) { orderedItems.map { it.attachment } }
    val attachmentKeys = remember(orderedItems) { orderedItems.map { it.key } }
    val latestCellWidth by rememberUpdatedState(uiState.attachmentList.cellWidth)
    val locale = LocalConfiguration.current.locales[0]
    val currentYear = LocalDate.now(zone).year
    val monthFormatter =
        remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMM"), locale)
        }
    val yearMonthFormatter =
        remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)
        }
    val dayFormatter =
        remember(locale) {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMEd"), locale)
        }

    val attachmentOrigins = remember(accountIdentity) { AttachmentOrigins() }
    var showFullScreenViewer by remember(accountIdentity) { mutableStateOf(false) }
    var fullScreenInitialIndex by remember(accountIdentity) { mutableStateOf(0) }

    rememberScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) },
    )

    val bottomPadding by animateDpAsState(
        targetValue = if (isNavBarVisible) 80.dp else 16.dp,
        label = "BottomPadding",
    )

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

    PullToRefreshBox(
        isRefreshing = attachmentPages.loadState.refresh is LoadState.Loading && attachmentPages.itemCount > 0,
        onRefresh = {
            attachmentPages.refresh()
            viewModel.checkConnection()
        },
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
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
                                                (latestCellWidth * zoomFactor).coerceIn(
                                                    100f,
                                                    600f,
                                                )
                                            viewModel.updateAttachmentCellWidth(newWidth)
                                            // Consume the event to prevent the list from scrolling while zooming
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                }
                            }
                        }
                    },
        ) {
            val viewportWidth = maxWidth.value
            val entries =
                rememberTimelineEntries(
                    listState,
                    days,
                    uiState.attachmentList.aspectRatios,
                    viewportWidth,
                    uiState.attachmentList.cellWidth,
                )
            if (attachmentItems.isEmpty() && loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (attachmentItems.isEmpty() && !loading) {
                if (loadError != null || uiState.error != null) {
                    ErrorView(
                        title = stringResource(R.string.common_error_failed_to_load_attachments),
                        message =
                            loadError?.message ?: uiState.error
                                ?.let {
                                    stringResource(it.resourceId, *it.formatArgs.toTypedArray())
                                }.orEmpty(),
                        onRetry = attachmentPages::retry,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(
                                if (uiState.connectionState.isOffline) {
                                    R.string.attachments_offline_empty
                                } else {
                                    R.string.attachments_none_found
                                },
                            ),
                        )
                    }
                }
            } else {
                val attachmentCard: @Composable (TimelineAttachment, Modifier) -> Unit = { item, modifier ->
                    attachmentPages[item.index]
                    val attachment = item.attachment
                    AttachmentCard(
                        attachment = attachment,
                        mediaModifier = Modifier.attachmentOrigin(attachmentOrigins, attachment),
                        token = uiState.session.token,
                        hostUrl = uiState.session.hostUrl,
                        compactMode = AttachmentCompactMode.Width,
                        gallery = true,
                        modifier = modifier,
                        onClick = {
                            fullScreenInitialIndex = item.index
                            showFullScreenViewer = true
                        },
                        onMediaRatioAvailable = { ratio, exact ->
                            if (exact) {
                                viewModel.updateAttachmentAspectRatio(
                                    accountIdentity?.id,
                                    accountIdentity?.generation ?: 0,
                                    attachment.identityKey,
                                    ratio,
                                )
                            }
                        },
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = bottomPadding),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.key }, contentType = {
                        when (it) {
                            is AttachmentTimelineEntry.Month -> "month"
                            is AttachmentTimelineEntry.Day -> "day"
                            is AttachmentTimelineEntry.Row -> "row"
                        }
                    }) { entry ->
                        when (entry) {
                            is AttachmentTimelineEntry.Month -> {
                                Text(
                                    text =
                                        entry.month.atDay(1).format(
                                            if (entry.month.year == currentYear) monthFormatter else yearMonthFormatter,
                                        ),
                                    style = MaterialTheme.typography.headlineSmall,
                                    modifier =
                                        Modifier
                                            .padding(start = 12.dp, end = 12.dp, top = 24.dp, bottom = 8.dp)
                                            .semantics { heading() },
                                )
                            }

                            is AttachmentTimelineEntry.Day -> {
                                Text(
                                    text = entry.date?.format(dayFormatter) ?: stringResource(R.string.attachments_unknown_date),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp).semantics { heading() },
                                )
                            }

                            is AttachmentTimelineEntry.Row -> {
                                Row(
                                    modifier =
                                        Modifier
                                            .width(
                                                (entry.geometry.widths.sum() + 2f * (entry.attachments.size - 1))
                                                    .coerceAtMost(viewportWidth)
                                                    .dp,
                                            ).height(entry.geometry.height.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    entry.attachments.forEachIndexed { column, item ->
                                        key(item.key) {
                                            attachmentCard(item, Modifier.weight(entry.geometry.widths[column]).fillMaxSize())
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (loading && attachmentItems.isNotEmpty()) {
                        item(key = "loading", contentType = "footer") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (!loading && attachmentPages.loadState.append.endOfPaginationReached && attachmentItems.isNotEmpty()) {
                        item(key = "end", contentType = "footer") {
                            Box(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.memo_list_end),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
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
            attachments = orderedAttachments,
            initialIndex = fullScreenInitialIndex,
            token = uiState.session.token,
            hostUrl = uiState.session.hostUrl,
            onDismiss = { showFullScreenViewer = false },
            originBounds = attachmentOrigins::boundsFor,
            attachmentKeys = attachmentKeys,
            onPageChanged = { index ->
                if (index in 0 until attachmentPages.itemCount) attachmentPages[index]
            },
        )
    }
}
