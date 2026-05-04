package org.example.memosm.ui.component.item

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.network.httpHeaders
import org.example.memosm.R
import org.example.memosm.model.Memo
import org.example.memosm.model.MemoRelationType
import org.example.memosm.model.MemoState
import org.example.memosm.model.Reaction
import org.example.memosm.model.User
import org.example.memosm.ui.VisibilityIcon
import org.example.memosm.ui.component.item.markdown.NativeComposeMarkdown
import org.example.memosm.ui.component.item.media.FullScreenAttachmentViewer
import org.example.memosm.ui.component.resolveResourceUrl


@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MemoItem(
    modifier: Modifier = Modifier,
    memo: Memo,
    user: User? = null,
    currentUser: User? = null,
    token: String,
    hostUrl: String = "",
    colors: CardColors = CardDefaults.cardColors(),
    onClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    onUnarchive: (() -> Unit)? = null,
    onPin: ((Boolean) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onUpsertReaction: ((String) -> Unit)? = null,
    onDeleteReaction: ((Reaction) -> Unit)? = null,
    onContentUpdate: ((String) -> Unit)? = null,
    maxHeight: Dp = Dp.Unspecified,
    isDetailView: Boolean = false,
    reactionOptions: List<String> = emptyList(),
    headerScale: Float,
    onHashtagClick: ((String) -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showRawTextDialog by remember { mutableStateOf(false) }
    var showFullScreenViewer by remember { mutableStateOf(false) }
    var fullScreenInitialIndex by remember { mutableStateOf(0) }
    val context = LocalContext.current

    val unknownTime = stringResource(R.string.memo_unknown_time)

    val formattedTime = remember(memo.displayTime) {
        memo.displayTime?.let { instant ->
            try {
                DateUtils.getRelativeTimeSpanString(
                    instant.toEpochMilliseconds(),
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString()
            } catch (_: Exception) {
                instant.toString()
            }
        } ?: unknownTime
    }



    Card(
        modifier = modifier.fillMaxWidth(), colors = colors
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(
                    start = 12.dp,
                    top = if (user != null) 12.dp else 4.dp,
                    end = 4.dp,
                    bottom = 12.dp
                )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)
                ) {
                    if (user != null) {
                        val avatarUrl = remember(user.avatarUrl, hostUrl) {
                            resolveResourceUrl(hostUrl, user.avatarUrl)
                        }
                        if (avatarUrl != null) {
                            val imageRequest = remember(avatarUrl, token) {
                                val headers = coil3.network.NetworkHeaders.Builder().apply {
                                    if (token.isNotEmpty()) {
                                        set("Authorization", "Bearer $token")
                                    }
                                }.build()

                                coil3.request.ImageRequest.Builder(context).data(avatarUrl)
                                    .httpHeaders(headers).build()
                            }
                            AsyncImage(
                                model = imageRequest,
                                contentDescription = stringResource(R.string.profile_avatar_description),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(verticalArrangement = Arrangement.Center) {
                            Text(
                                text = user.displayName ?: user.username
                                ?: stringResource(R.string.memo_unknown_user),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                lineHeight = MaterialTheme.typography.labelSmall.lineHeight
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = formattedTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                if (memo.pinned == true) {
                                    Icon(
                                        imageVector = Icons.Outlined.PushPin,
                                        contentDescription = stringResource(R.string.profile_stats_pinned),
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                VisibilityIcon(
                                    visibility = memo.visibility,
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                if (memo.isUnsynced) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Outlined.SyncProblem,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                val commentCount = remember(memo.relations) {
                                    memo.relations?.count { it.type == MemoRelationType.COMMENT }
                                        ?: 0
                                }
                                if (commentCount > 0 && !isDetailView) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.Comment,
                                        contentDescription = stringResource(
                                            R.string.memo_detail_comments, commentCount
                                        ),
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        text = commentCount.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                            alpha = 0.7f
                                        )
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = formattedTime,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            if (memo.pinned == true) {
                                Icon(
                                    imageVector = Icons.Outlined.PushPin,
                                    contentDescription = stringResource(R.string.profile_stats_pinned),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            VisibilityIcon(
                                visibility = memo.visibility,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                            if (memo.isUnsynced) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Outlined.SyncProblem,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            val commentCount = remember(memo.relations) {
                                memo.relations?.count { it.type == MemoRelationType.COMMENT } ?: 0
                            }
                            if (commentCount > 0 && !isDetailView) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.Comment,
                                    contentDescription = stringResource(
                                        R.string.memo_detail_comments, commentCount
                                    ),
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = commentCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                if (onEdit != null || onDelete != null || onUpsertReaction != null || memo.name != null) {
                    Box {
                        IconButton(
                            onClick = { showMenu = true }, modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(R.string.memo_action_more),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            if (memo.name != null && hostUrl.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_open_web)) },
                                    onClick = {
                                        showMenu = false
                                        val memoId = memo.name.removePrefix("memos/")
                                        val baseUrl =
                                            if (hostUrl.endsWith("/")) hostUrl else "$hostUrl/"
                                        val webUrl = "${baseUrl}memos/$memoId"
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, webUrl.toUri())
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            Log.e(
                                                "MemoItem", "Failed to open web URL: $webUrl", e
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Language, contentDescription = null)
                                    })
                            }

                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.memo_action_show_raw)) },
                                onClick = {
                                    showMenu = false
                                    showRawTextDialog = true
                                },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Description, contentDescription = null)
                                })


                            if (onUpsertReaction != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_add_reaction)) },
                                    onClick = {
                                        showMenu = false
                                        showReactionPicker = true
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AddReaction, contentDescription = null)
                                    })
                            }
                            if (onEdit != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_edit)) },
                                    onClick = {
                                        showMenu = false
                                        onEdit()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Edit, contentDescription = null
                                        )
                                    })
                            }
                            if (onPin != null && memo.state == MemoState.NORMAL) {
                                val isPinned = memo.pinned == true
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (isPinned) R.string.memo_action_unpin else R.string.memo_action_pin)) },
                                    onClick = {
                                        showMenu = false
                                        onPin(!isPinned)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.PushPin, contentDescription = null
                                        )
                                    })
                            }
                            if (onArchive != null && memo.state == MemoState.NORMAL) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_archive)) },
                                    onClick = {
                                        showMenu = false
                                        onArchive()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Archive, contentDescription = null
                                        )
                                    })
                            }
                            if (onUnarchive != null && memo.state == MemoState.ARCHIVED) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_unarchive)) },
                                    onClick = {
                                        showMenu = false
                                        onUnarchive()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Unarchive, contentDescription = null
                                        )
                                    })
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.memo_action_delete)) },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.Delete, contentDescription = null
                                        )
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = MaterialTheme.colorScheme.error,
                                        leadingIconColor = MaterialTheme.colorScheme.error
                                    )
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (user != null) 10.dp else 2.dp))

            Column(modifier = Modifier.padding(start = 0.dp, end = 0.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (maxHeight != Dp.Unspecified) {
                                Modifier
                                    .heightIn(max = maxHeight)
                                    .clip(RectangleShape)
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.Offscreen
                                    }
                                    .drawWithContent {
                                        drawContent()
                                        if (size.height >= maxHeight.toPx() - 1.dp.toPx()) {
                                            drawRect(
                                                brush = Brush.verticalGradient(
                                                    0.7f to Color.Black, 1.0f to Color.Transparent
                                                ), blendMode = BlendMode.DstIn
                                            )
                                        }
                                    }
                            } else {
                                Modifier
                            })) {
                    NativeComposeMarkdown(
                        content = memo.content,
                        token = token,
                        hostUrl = hostUrl,
                        selectable = isDetailView,
                        onContentChange = onContentUpdate,
                        onHashtagClick = onHashtagClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 16.dp, bottom = 8.dp)
                            .then(
                                if (maxHeight != Dp.Unspecified) Modifier.wrapContentHeight(
                                    unbounded = true,
                                    align = Alignment.Top
                                ) else Modifier
                            ),
                        headerScale = headerScale
                    )
                }

                memo.location?.let { loc ->
                    val isClickable = loc.latitude != null && loc.longitude != null
                    Surface(
                        onClick = {
                            if (isClickable) {
                                val label = loc.placeholder ?: ""
                                val geoUri =
                                    "geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}${
                                        if (label.isNotEmpty()) "(${
                                            Uri.encode(label)
                                        })" else ""
                                    }"
                                val intent = Intent(Intent.ACTION_VIEW, geoUri.toUri())
                                context.startActivity(intent)
                            }
                        },
                        enabled = isClickable,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Place,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = loc.placeholder ?: "${loc.latitude}, ${loc.longitude}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                val attachments = remember(memo.attachments) {
                    memo.attachments ?: emptyList()
                }

                if (attachments.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (isDetailView) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 8.dp)
                        ) {
                            attachments.forEachIndexed { index, attachment ->
                                var aspectRatio by remember(
                                    attachment.name ?: attachment.filename
                                ) {
                                    mutableFloatStateOf(16f / 9f)
                                }
                                AttachmentCard(
                                    attachment = attachment,
                                    token = token,
                                    hostUrl = hostUrl,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(aspectRatio),
                                    compactMode = AttachmentCompactMode.Never,
                                    onClick = {
                                        fullScreenInitialIndex = index
                                        showFullScreenViewer = true
                                    },
                                    onRatioAvailable = { ratio, _ -> aspectRatio = ratio })
                            }
                        }
                    } else {
                        val scrollState = rememberLazyListState()
                        LazyRow(
                            state = scrollState,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.Offscreen
                                }
                                .drawWithContent {
                                    drawContent()
                                    val canScrollBackward = scrollState.canScrollBackward
                                    val canScrollForward = scrollState.canScrollForward

                                    if (canScrollBackward || canScrollForward) {
                                        val leftFade =
                                            if (canScrollBackward) Color.Transparent else Color.Black
                                        val rightFade =
                                            if (canScrollForward) Color.Transparent else Color.Black

                                        drawRect(
                                            brush = Brush.horizontalGradient(
                                                0f to leftFade,
                                                0.05f to Color.Black,
                                                0.95f to Color.Black,
                                                1f to rightFade
                                            ), blendMode = BlendMode.DstIn
                                        )
                                    }
                                }) {
                            itemsIndexed(
                                attachments,
                                key = { index, it -> "${it.externalLink ?: "link"}_${it.filename}_${it.createTime ?: 0}_$index" }) { index, attachment ->
                                AttachmentCard(
                                    attachment = attachment,
                                    token = token,
                                    hostUrl = hostUrl,
                                    modifier = Modifier.size(width = 240.dp, height = 160.dp),
                                    showInfo = false,
                                    compactMode = AttachmentCompactMode.Area,
                                    onClick = {
                                        fullScreenInitialIndex = index
                                        showFullScreenViewer = true
                                    }
                                )
                            }
                        }
                    }
                }

                // Reactions
                val reactions = remember(memo.reactions) {
                    memo.reactions ?: emptyList()
                }
                if (reactions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val groupedReactions = remember(reactions) {
                        reactions.groupBy { it.reactionType }
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        groupedReactions.forEach { (type, reactionList) ->
                            val myReaction = reactionList.find { it.creator == currentUser?.name }
                            Surface(
                                shape = RoundedCornerShape(12.dp), color = if (myReaction != null) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                }, border = if (myReaction != null) {
                                    null
                                } else {
                                    null
                                }, onClick = {
                                    if (myReaction != null) {
                                        // Pass the emoji type or the reaction name to the viewmodel
                                        onDeleteReaction?.invoke(myReaction)
                                    } else {
                                        onUpsertReaction?.invoke(type)
                                    }
                                }) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = type, style = MaterialTheme.typography.labelMedium
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = reactionList.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (myReaction != null) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showReactionPicker) {
        ReactionPickerDialog(
            reactionOptions = reactionOptions,
            onDismiss = { showReactionPicker = false },
            onReactionSelected = { emoji ->
                onUpsertReaction?.invoke(emoji)
                showReactionPicker = false
            })
    }

    if (showRawTextDialog) {
        AlertDialog(
            onDismissRequest = { showRawTextDialog = false },
            title = { Text(stringResource(R.string.memo_dialog_raw_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = memo.content,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRawTextDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            })
    }

    if (showFullScreenViewer) {
        val attachments = remember(memo.attachments) { memo.attachments ?: emptyList() }
        FullScreenAttachmentViewer(
            attachments = attachments,
            initialIndex = fullScreenInitialIndex,
            token = token,
            hostUrl = hostUrl,
            onDismiss = { showFullScreenViewer = false }
        )
    }
}

