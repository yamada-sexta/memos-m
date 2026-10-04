package org.example.memosm.ui.component

import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.example.memosm.R
import org.example.memosm.model.User
import org.example.memosm.model.UserStats
import org.example.memosm.ui.component.item.media.MemoImage

@Composable
fun ProfileHeader(
    user: User?,
    onClick: () -> Unit,
    onEditClick: (() -> Unit)? = null,
    userStats: UserStats? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, onClick = onClick
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MemoImage(
                        uri = user?.avatarUrl?.toUri() ?: Uri.EMPTY,
                        token = user?.token,
                        modifier = Modifier.size(64.dp),
                        isRound = true,
                        placeholderIcon = Icons.Outlined.AccountCircle
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user?.displayName ?: stringResource(R.string.memo_unknown_user),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (!user?.username.isNullOrBlank()) "@${user.username}" else stringResource(
                                R.string.memo_unknown_user
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        user?.name?.let { name ->
                            val id = name.removePrefix("users/")
                            Text(
                                text = "${stringResource(R.string.profile_user_id)}: $id",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                val description = user?.description
                if (!description.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = description, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                ProfileStatsRow(userStats)
            }

            // Edit button in top-right corner
            if (onEditClick != null) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.profile_edit_account),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileStatsRow(stats: UserStats?) {
    val notAvailable = stringResource(R.string.common_not_available)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val itemWidth = maxWidth / 6
        // Keep one row, with scrolling only when large counts or fonts need more room.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val itemModifier = Modifier.widthIn(min = itemWidth)
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_memos),
                value = stats?.totalMemoCount?.toString() ?: notAvailable,
                icon = Icons.AutoMirrored.Outlined.LibraryBooks,
                modifier = itemModifier
            )
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_tags),
                value = stats?.tagCount?.size?.toString() ?: notAvailable,
                icon = Icons.Outlined.Tag,
                modifier = itemModifier
            )
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_pinned),
                value = stats?.pinnedMemos?.size?.toString() ?: notAvailable,
                icon = Icons.Outlined.PushPin,
                modifier = itemModifier
            )
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_links),
                value = stats?.memoTypeStats?.linkCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.Link,
                modifier = itemModifier
            )
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_code),
                value = stats?.memoTypeStats?.codeCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.Code,
                modifier = itemModifier
            )
            ProfileStatItem(
                label = stringResource(R.string.profile_stats_todo),
                value = stats?.memoTypeStats?.todoCount?.toString() ?: notAvailable,
                icon = Icons.Outlined.TaskAlt,
                modifier = itemModifier
            )
        }
    }
}

@Composable
private fun ProfileStatItem(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "$label: $value" }
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            softWrap = false
        )
    }
}
