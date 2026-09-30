package org.example.memosm.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.model.UserNotification
import org.example.memosm.viewmodel.MemosViewModel
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: MemosViewModel,
    onBack: () -> Unit,
    onToggleNavBar: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    rememberScrollContext(
        listState = listState,
        onScrollDown = { onToggleNavBar?.invoke(false) },
        onScrollUp = { onToggleNavBar?.invoke(true) }
    )

    var notifications by remember { mutableStateOf<List<UserNotification>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showingCached by remember { mutableStateOf(false) }
    var cachedSavedAt by remember { mutableStateOf(0L) }
    var hasLoadError by remember { mutableStateOf(false) }

    suspend fun loadNotifications(isUserRefresh: Boolean = false) {
        if (isUserRefresh) {
            isRefreshing = true
        } else {
            isLoading = true
        }
        hasLoadError = false

        runCatching {
            viewModel.listCurrentUserNotifications()
        }.onSuccess { result ->
            notifications = result.notifications
            showingCached = result.fromCache
            cachedSavedAt = result.savedAt
        }.onFailure {
            notifications = emptyList()
            showingCached = false
            Log.e("NotificationsScreen", "Failed to load notifications", it)
            hasLoadError = true
        }

        isLoading = false
        isRefreshing = false
    }

    LaunchedEffect(Unit) {
        loadNotifications()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.memo_detail_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                scope.launch {
                    loadNotifications(isUserRefresh = true)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                hasLoadError -> {
                    ErrorView(
                        title = stringResource(R.string.common_error_failed_to_load_notifications),
                        message = stringResource(R.string.notification_error_load),
                        onRetry = {
                            scope.launch {
                                loadNotifications()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                notifications.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.profile_notifications_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 16.dp + WindowInsets.statusBars.asPaddingValues()
                                .calculateTopPadding(),
                            end = 16.dp,
                            bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding()
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Offline: last-known snapshot is shown - badge it as
                        // stale (mirrors the cached hints of the other screens).
                        if (showingCached) {
                            item(key = "cached_hint") {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(
                                            horizontal = 12.dp,
                                            vertical = 6.dp
                                        )
                                    ) {
                                        Text(
                                            text = stringResource(R.string.offline_cached_data_message),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        if (cachedSavedAt > 0) {
                                            Text(
                                                text = stringResource(
                                                    R.string.notifications_last_updated,
                                                    formatEpochMillis(cachedSavedAt)
                                                ),
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(notifications, key = { it.name ?: "${it.type}-${it.createTime}" }) {
                            NotificationCard(notification = it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: UserNotification) {
    Card(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = notificationTitle(notification),
                    fontWeight = FontWeight.Medium
                )
            },
            supportingContent = {
                val details = buildList {
                    notification.sender?.substringAfterLast("/")?.takeIf { it.isNotBlank() }?.let {
                        add(it)
                    }
                    notificationStatusResource(notification.status)?.let { add(stringResource(it)) }
                    notification.createTime?.takeIf { it.isNotBlank() }?.let {
                        add(formatNotificationTime(it))
                    }
                }

                if (details.isNotEmpty()) {
                    Text(details.joinToString(" • "))
                }
            },
            leadingContent = {
                Icon(Icons.Outlined.Notifications, contentDescription = null)
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface)
        )
    }
}

@Composable
private fun notificationTitle(notification: UserNotification): String {
    val type = when (notification.type?.uppercase()) {
        "MEMO_COMMENT" -> R.string.notification_type_memo_comment
        "MEMO_MENTION" -> R.string.notification_type_memo_mention
        else -> R.string.notification_default_title
    }
    return stringResource(type) + notification.activityId?.let { " #$it" }.orEmpty()
}

@androidx.annotation.StringRes
internal fun notificationStatusResource(value: String?): Int? = when (value?.uppercase()) {
    "UNREAD" -> R.string.notification_status_unread
    "READ" -> R.string.notification_status_read
    "ARCHIVED" -> R.string.notification_status_archived
    "STATUS_UNSPECIFIED", "UNSPECIFIED", null, "" -> null
    else -> null
}

private fun formatNotificationTime(value: String): String {
    return runCatching {
        OffsetDateTime.parse(value).format(
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        )
    }.getOrDefault(value)
}

private fun formatEpochMillis(epochMillis: Long): String {
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT
    ).format(java.util.Date(epochMillis))
}
