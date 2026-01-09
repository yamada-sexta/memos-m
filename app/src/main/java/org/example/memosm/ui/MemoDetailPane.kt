package org.example.memosm.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.example.memosm.model.Attachment
import org.example.memosm.model.Memo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoDetailPane(
    memo: Memo,
    comments: List<Memo>,
    isLoadingComments: Boolean,
    token: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                Box(
                    modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart
                ) {
                    Text("Memo Details", modifier = Modifier.widthIn(max = 600.dp))
                }
            },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
        }, modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 800.dp)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Original memo
                item(key = "original_${memo.name}") {
                    MemoDetailCard(
                        memo = memo,
                        token = token,
                        isOriginal = true,
                        modifier = Modifier.animateItem()
                    )
                }

                // Comments section header
                item(key = "comments_header") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .animateItem()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Forum,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Comments (${comments.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Loading indicator for comments
                if (isLoadingComments) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                                .animateItem(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }

                // Comments list
                if (!isLoadingComments && comments.isEmpty()) {
                    item(key = "empty_comments") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp)
                                .animateItem(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No comments yet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(comments, key = { "comment_${it.name}" }) { comment ->
                    MemoDetailCard(
                        memo = comment,
                        token = token,
                        isOriginal = false,
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoDetailCard(
    memo: Memo, token: String, isOriginal: Boolean, modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(), colors = if (isOriginal) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = memo.content, style = MaterialTheme.typography.bodyLarge
            )

            val attachments = remember(memo.attachments) {
                memo.attachments ?: emptyList()
            }

            if (attachments.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                AttachmentRow(attachments = attachments, token = token)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = memo.displayTime ?: "UNKNOWN",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = getVisibilityIcon(memo.visibility),
                            contentDescription = memo.visibility,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachments: List<Attachment>, token: String, modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        items(attachments, key = { it.externalLink ?: it.filename }) { attachment ->
            val isImage = remember(attachment.displayType) {
                attachment.displayType.startsWith(
                    "image/", ignoreCase = true
                ) || attachment.displayType.contains("image", ignoreCase = true)
            }

            if (isImage) {
                val context = LocalContext.current
                val imageRequest = remember(attachment.externalLink, token) {
                    ImageRequest.Builder(context).data(attachment.externalLink)
                        .addHeader("Authorization", "Bearer $token").crossfade(true).build()
                }

                AsyncImage(
                    model = imageRequest,
                    contentDescription = attachment.filename,
                    modifier = Modifier
                        .size(width = 240.dp, height = 160.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .animateItem(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Card(
                    modifier = Modifier
                        .size(width = 200.dp, height = 100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .animateItem(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = attachment.filename,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Text(
                            text = attachment.displayType,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MemoDetailPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = true, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()
        ) {
            Text(
                text = "Select a memo to view details",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun getVisibilityIcon(visibility: String): ImageVector {
    return when (visibility.uppercase()) {
        "PUBLIC" -> Icons.Default.Public
        "PROTECTED" -> Icons.Default.Group
        "PRIVATE" -> Icons.Default.Lock
        else -> Icons.Default.Lock
    }
}
