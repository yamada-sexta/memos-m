import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.model.Account
import kotlin.math.abs
import kotlin.math.roundToInt

// Custom swipe anchor states
enum class SwipeState {
    EditTriggered,  // Fully swiped to right (edit)
    Settled,        // Not swiped
    Dismissed       // Fully swiped to left (delete)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsList(
    modifier: Modifier = Modifier,
    accounts: List<Account>,
    onSwitchAccount: (Account) -> Unit,
    onLogoutAccount: (Account) -> Unit,
    onEditAccount: ((Account) -> Unit)? = null,
    onAddAccount: () -> Unit,
) {
    var accountToRemove by remember { mutableStateOf<Account?>(null) }
    val scope = rememberCoroutineScope()
    LocalDensity.current

    if (accountToRemove != null) {
        AlertDialog(
            onDismissRequest = { accountToRemove = null },
            title = { Text(stringResource(R.string.profile_remove_account_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.profile_remove_account_confirm,
                        accountToRemove?.name ?: stringResource(R.string.memo_unknown_user)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    accountToRemove?.let { onLogoutAccount(it) }
                    accountToRemove = null
                }) {
                    Text(
                        stringResource(R.string.common_remove),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { accountToRemove = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            })
    }

    Column(modifier = modifier.padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.profile_accounts),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onAddAccount) {
                Icon(Icons.Outlined.Add, contentDescription = null)
            }
        }
        Column {
            accounts.forEachIndexed { index, account ->
                // Pill shape for when swiping
                val pillShape = RoundedCornerShape(28.dp)

                // Track component width for calculating swipe progress
                var componentWidth by remember { mutableFloatStateOf(1f) }

                // Create anchored draggable state with bidirectional support
                val anchoredDraggableState = remember {
                    AnchoredDraggableState(
                        initialValue = SwipeState.Settled,
                        anchors = DraggableAnchors {
                            SwipeState.EditTriggered at 1f // Will be updated to +componentWidth
                            SwipeState.Settled at 0f
                            SwipeState.Dismissed at -1f // Will be updated to -componentWidth
                        }
                    )
                }

                // Create fling behavior with custom thresholds (new API)
                val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                    state = anchoredDraggableState,
                    // Require 90% swipe to trigger action
                    positionalThreshold = { distance: Float -> distance * 0.9f },
                    animationSpec = tween(durationMillis = 200)
                )

                // Update anchors when width changes
                LaunchedEffect(componentWidth) {
                    if (componentWidth > 0) {
                        val newAnchors = DraggableAnchors {
                            if (onEditAccount != null) {
                                SwipeState.EditTriggered at componentWidth
                            }
                            SwipeState.Settled at 0f
                            SwipeState.Dismissed at -componentWidth
                        }
                        anchoredDraggableState.updateAnchors(newAnchors)
                    }
                }

                // Track if deletion/edit has been triggered
                var pendingDeletion by remember { mutableStateOf(false) }
                var pendingEdit by remember { mutableStateOf(false) }

                // Monitor for when the swipe reaches the dismissed or edit state
                LaunchedEffect(anchoredDraggableState.settledValue) {
                    when (anchoredDraggableState.settledValue) {
                        SwipeState.Dismissed -> {
                            pendingDeletion = true
                            accountToRemove = account
                        }

                        SwipeState.EditTriggered -> {
                            pendingEdit = true
                            onEditAccount?.invoke(account)
                            // Reset immediately after triggering edit
                            scope.launch {
                                anchoredDraggableState.snapTo(SwipeState.Settled)
                            }
                            pendingEdit = false
                        }

                        else -> {}
                    }
                }

                // Reset state when dialog is dismissed without confirming
                LaunchedEffect(accountToRemove) {
                    if (accountToRemove == null && pendingDeletion) {
                        pendingDeletion = false
                        scope.launch {
                            anchoredDraggableState.snapTo(SwipeState.Settled)
                        }
                    }
                }

                // Calculate swipe progress (0 to 1) for both directions
                val currentOffset by remember {
                    derivedStateOf {
                        try {
                            anchoredDraggableState.requireOffset()
                        } catch (e: IllegalStateException) {
                            0f
                        }
                    }
                }

                val swipeProgress by remember {
                    derivedStateOf {
                        if (componentWidth > 0) {
                            (abs(currentOffset) / componentWidth).coerceIn(0f, 1f)
                        } else 0f
                    }
                }

                val isSwipingRight by remember {
                    derivedStateOf { currentOffset > 0 }
                }

                // Icon animation threshold - triggers early (at ~10% swipe progress)
                val isIconAnimationTriggered by remember {
                    derivedStateOf { swipeProgress >= 0.1f }
                }

                // Determine if there's ACTIVE swiping happening
                val isActivelySwiping by remember {
                    derivedStateOf { swipeProgress > 0f }
                }

                // Animate the min corner (4dp -> 28dp) for the edges that need to change
                val animatedMinCorner by animateDpAsState(
                    targetValue = if (isActivelySwiping) 28.dp else 4.dp,
                    label = "corner_morph"
                )

                // Current animated shape for the card
                val currentShape = when {
                    accounts.size == 1 -> pillShape
                    index == 0 -> RoundedCornerShape(
                        topStart = 28.dp,
                        topEnd = 28.dp,
                        bottomStart = animatedMinCorner,
                        bottomEnd = animatedMinCorner
                    )

                    index == accounts.size - 1 -> RoundedCornerShape(
                        topStart = animatedMinCorner,
                        topEnd = animatedMinCorner,
                        bottomStart = 28.dp,
                        bottomEnd = 28.dp
                    )

                    else -> RoundedCornerShape(animatedMinCorner)
                }

                // Background animations - different colors for edit vs delete
                val backgroundColor by animateColorAsState(
                    when {
                        isSwipingRight && isIconAnimationTriggered -> MaterialTheme.colorScheme.primaryContainer
                        !isSwipingRight && isIconAnimationTriggered -> MaterialTheme.colorScheme.errorContainer
                        else -> Color.Transparent
                    },
                    label = "bg_color"
                )
                val iconColor by animateColorAsState(
                    when {
                        isSwipingRight && isIconAnimationTriggered -> MaterialTheme.colorScheme.primary
                        !isSwipingRight && isIconAnimationTriggered -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                    label = "icon_color"
                )
                val iconScale by animateFloatAsState(
                    if (isIconAnimationTriggered) 1.25f else 1.0f,
                    label = "icon_scale"
                )

                Box(
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .onSizeChanged { componentWidth = it.width.toFloat() }
                ) {
                    // Background layer with icons (edit on left, delete on right)
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clip(currentShape)
                            .background(backgroundColor)
                            .padding(horizontal = 24.dp)
                    ) {
                        // Edit icon on the left (for right swipe)
                        if (swipeProgress > 0 && isSwipingRight && onEditAccount != null) {
                            Box(
                                modifier = Modifier.align(Alignment.CenterStart)
                            ) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.scale(iconScale)
                                )
                            }
                        }
                        // Delete icon on the right (for left swipe)
                        if (swipeProgress > 0 && !isSwipingRight) {
                            Box(
                                modifier = Modifier.align(Alignment.CenterEnd)
                            ) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.scale(iconScale)
                                )
                            }
                        }
                    }

                    // Foreground card with swipe gesture
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset {
                                IntOffset(currentOffset.roundToInt(), 0)
                            }
                            .anchoredDraggable(
                                state = anchoredDraggableState,
                                orientation = Orientation.Horizontal,
                                flingBehavior = flingBehavior,
                                enabled = true
                            ),
                        shape = currentShape,
                        colors = CardDefaults.cardColors(
                            containerColor = if (account.isActive)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        onClick = { if (!account.isActive) onSwitchAccount(account) }
                    ) {
                        val avatarUrl = account.avatarUrl
                        val fullAvatarUrl =
                            if (!avatarUrl.isNullOrBlank() && !avatarUrl.startsWith("http")) {
                                "${account.hostUrl.trimEnd('/')}$avatarUrl"
                            } else avatarUrl

                        ListItem(
                            headlineContent = {
                                val displayName = account.displayName
                                val name = account.name
                                val text = when {
                                    !displayName.isNullOrBlank() -> displayName
                                    !name.isNullOrBlank() -> "@$name"
                                    else -> stringResource(R.string.memo_unknown_user)
                                }
                                Text(
                                    text,
                                    fontWeight = if (account.isActive) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            supportingContent = {
                                val name = account.name
                                val text =
                                    if (!account.displayName.isNullOrBlank() && !name.isNullOrBlank()) {
                                        "@$name • ${account.hostUrl}"
                                    } else {
                                        account.hostUrl
                                    }
                                Text(text)
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = fullAvatarUrl,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentScale = ContentScale.Crop
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}