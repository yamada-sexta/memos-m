package org.example.memosm.ui.component

import DeleteConfirmationDialog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Memo
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.composer.MemoEditScreen
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.PaginatedListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoDetailView(
    modifier: Modifier = Modifier,
    memo: Memo,
    comments: PaginatedListState<Memo>,

    token: String,
    hostUrl: String = "",
    showBackButton: Boolean,
    onBack: () -> Unit,
    viewModel: MemosViewModel,
    reactionOptions: List<String> = emptyList(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val onCommentMemo = LocalMemoCommenter.current
    val onEditMemo = LocalMemoEditor.current
    var memoToDelete by remember { mutableStateOf<Memo?>(null) }

    val listState = rememberLazyListState()
    var isFabExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousScrollOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, offset) ->
            isFabExpanded = when {
                index == 0 && offset == 0 -> true
                index > previousIndex -> false
                index < previousIndex -> true
                offset > previousScrollOffset + 10 -> false
                offset < previousScrollOffset - 10 -> true
                else -> isFabExpanded
            }
            previousIndex = index
            previousScrollOffset = offset
        }
    }

    val isOwner = remember(memo.creator, uiState.session.currUser?.name) {
        memo.creator == uiState.session.currUser?.name
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
                TopAppBar(
                    title = {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                stringResource(R.string.memo_detail_title),
                                modifier = Modifier.widthIn(max = 600.dp)
                            )
                        }
                    },
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.memo_detail_back)
                                )
                            }
                        }
                    },
                    // Set to empty because parent Scaffolds are already handling system bar insets
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            }, floatingActionButton = {
                if (uiState.session.currUser != null) {
                    ExtendedFloatingActionButton(
                        onClick = { onCommentMemo(memo) },
                        expanded = isFabExpanded,
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Add, contentDescription = null
                            )
                        },
                        text = {
                            Text(text = stringResource(R.string.memo_detail_add_comment))
                        },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }, containerColor = Color.Transparent, modifier = Modifier.fillMaxSize()
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize() // Use fillMaxSize instead of fillMaxHeight
                        .widthIn(max = 800.dp)
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Original memo
                    item(key = "original_${memo.name ?: memo.content.hashCode()}") {
                        MemoItem(
                            memo = memo,
                            user = uiState.users[memo.creator],
                            currentUser = uiState.session.currUser,
                            token = token,
                            hostUrl = hostUrl,
                            colors = CardDefaults.cardColors(),
                            onEdit = if (isOwner) {
                                { onEditMemo(memo) }
                            } else null,
                            onPin = if (isOwner) { pinned ->
                                viewModel.memoActionDelegate.updateMemoPinned(memo, pinned)
                            } else null,
                            onDelete = if (isOwner) {
                                { memoToDelete = memo }
                            } else null,
                            onUpsertReaction = { emoji ->
                                viewModel.memoActionDelegate.upsertMemoReaction(memo, emoji)
                            },
                            onDeleteReaction = { reaction ->
                                viewModel.memoActionDelegate.deleteMemoReaction(memo, reaction)
                            },
                            onContentUpdate = if (isOwner) { newContent ->
                                viewModel.memoActionDelegate.updateMemo(
                                    memo,
                                    newContent,
                                    memo.visibility,
                                    memo.attachments ?: emptyList(),
                                    memo.location
                                )
                            } else null,
                            isDetailView = true,
                            headerScale = uiState.appSettings.headerScale,
                            reactionOptions = reactionOptions)
                    }

                    // Comments section header
                    item(key = "comments_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Forum,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.memo_detail_comments, comments.items.size
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Cached comments hint (offline: showing local cache)
                    if (comments.isOffline && !comments.isLoading) {
                        item(key = "comments_cached_hint") {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.memo_detail_comments_cached),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    // Loading indicator for comments
                    if (comments.isLoading) {
                        item(key = "loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }

                    // Comments list
                    if (!comments.isLoading && comments.items.isEmpty()) {
                        item(key = "empty_comments") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.memo_detail_no_comments),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    itemsIndexed(
                        comments.items,
                        key = { index, it -> "comment_${it.name ?: it.content.hashCode()}_$index" }) { index, comment ->
                        val isCommentOwner = comment.creator == uiState.session.currUser?.name
                        MemoItem(
                            memo = comment,
                            user = uiState.users[comment.creator],
                            currentUser = uiState.session.currUser,
                            token = token,
                            hostUrl = hostUrl,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            onEdit = if (isCommentOwner) {
                                { onEditMemo(comment) }
                            } else null,
                            onDelete = if (isCommentOwner) {
                                { memoToDelete = comment }
                            } else null,
                            onUpsertReaction = { emoji ->
                                viewModel.memoActionDelegate.upsertMemoReaction(comment, emoji)
                            },
                            onDeleteReaction = { reaction ->
                                viewModel.memoActionDelegate.deleteMemoReaction(comment, reaction)
                            },
                            onContentUpdate = if (isCommentOwner) { newContent ->
                                viewModel.memoActionDelegate.updateMemo(
                                    comment,
                                    newContent,
                                    comment.visibility,
                                    comment.attachments ?: emptyList(),
                                    comment.location
                                )
                            } else null,
                            isDetailView = true,
                            headerScale = uiState.appSettings.headerScale,
                            reactionOptions = reactionOptions)
                    }
                }
            }
        }
    }

    memoToDelete?.let { m ->
        DeleteConfirmationDialog(memo = m, onDismiss = { memoToDelete = null }, onConfirm = {
            viewModel.memoActionDelegate.deleteMemo(m) {
                memoToDelete = null
                if (m == memo) onBack()
            }
        })
    }
}
