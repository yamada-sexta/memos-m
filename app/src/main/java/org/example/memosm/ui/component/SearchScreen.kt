package org.example.memosm.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import org.example.memosm.R
import org.example.memosm.model.Memo
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.state.MemosListControls
import org.example.memosm.state.MemoActionControls
import org.example.memosm.state.SessionControls
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoSearchBar(
    modifier: Modifier = Modifier,
    controls: MemosListControls,
    actionControls: MemoActionControls?,
    sessionControls: SessionControls?,
    isExplore: Boolean = false,
    onMemoClick: (Memo) -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
    placeholder: String = stringResource(R.string.memo_search_placeholder)
) {
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val containerFocusRequester = remember { FocusRequester() }

    // Maintain a set of selected tags for AND filtering within the search context
    var searchSelectedTags by rememberSaveable { mutableStateOf(setOf<String>()) }
    var startDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var endDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var orderBy by rememberSaveable { mutableStateOf("display_time desc") }

    // Aggregate tags from the search pool to be context-accurate
    val availableTags =
        remember(controls.state.list.items, sessionControls?.state?.userStats, isExplore) {
            if (isExplore) {
                val tags = mutableMapOf<String, Int>()
                controls.state.list.items.forEach { memo ->
                    val regex = "#(\\w+)".toRegex()
                    regex.findAll(memo.content).forEach { match ->
                        val tag = match.groupValues[1]
                        tags[tag] = (tags[tag] ?: 0) + 1
                    }
                }
                tags.toList().sortedByDescending { it.second }.toMap()
            } else {
                sessionControls?.state?.userStats?.tagCount ?: emptyMap()
            }
        }

    // Effect to trigger server-side search whenever filters change
    LaunchedEffect(query, searchSelectedTags, startDateMillis, endDateMillis, orderBy, expanded) {
        if (expanded) {
            // Debounce the search to prevent excessive API calls while typing
            delay(300)
            // TODO viewModel.userDelegate.refreshUserStats()

            val filters = mutableListOf<String>()

            if (query.isNotBlank()) {
                filters.add("content.contains(\"$query\")")
            }

            searchSelectedTags.forEach { tag ->
                filters.add("tag in [\"$tag\"]")
            }

            if (startDateMillis != null) {
                filters.add("created_ts >= ${startDateMillis!! / 1000}")
            }

            if (endDateMillis != null) {
                // End date inclusive: add one day minus one second
                filters.add("created_ts < ${(endDateMillis!! + 86400000L) / 1000}")
            }

            val filterString = if (filters.isEmpty()) null else filters.joinToString(" && ")

            // To properly refactor search, the SearchState needs to expose a filter update function,
            // but for this UI-only patch we can hook directly into MemosListControls fetch logic if needed.
            // Since SearchScreen is currently tightly coupled to ViewModel's search logic, we will
            // trigger a generic fetch on the provided controls as a basic integration.
            controls.fetch(true)
        }
    }

    // Capture initial focus on the container to prevent the SearchBar from auto-focusing
    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(containerFocusRequester)
            .focusable()
            .zIndex(1f)
    ) {
        SearchBar(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 800.dp)
                .fillMaxWidth(if (expanded) 1f else 0.9f), inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { focusManager.clearFocus() },
                    expanded = expanded,
                    onExpandedChange = {
                        expanded = it
                        onExpandedChange(it)
                    },
                    placeholder = { Text(placeholder) },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty() || searchSelectedTags.isNotEmpty() || startDateMillis != null || endDateMillis != null) {
                            IconButton(onClick = {
                                query = ""
                                searchSelectedTags = emptySet()
                                startDateMillis = null
                                endDateMillis = null
                            }) {
                                Icon(Icons.Outlined.Clear, contentDescription = null)
                            }
                        }
                    },
                )
            }, expanded = expanded, onExpandedChange = {
                expanded = it
                onExpandedChange(it)
            },
            // Reset window insets to zero since MemosScaffold already handles status bar padding
            windowInsets = WindowInsets(0, 0, 0, 0)
        ) {
            SearchResultContent(
                query = query,
                selectedTags = searchSelectedTags,
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                orderBy = orderBy,
                availableTags = availableTags,
                filteredMemos = controls.state.list.items,
                sessionControls = sessionControls,
                actionControls = actionControls,
                onTagClick = { tag ->
                    searchSelectedTags = if (tag in searchSelectedTags) {
                        searchSelectedTags - tag
                    } else {
                        searchSelectedTags + tag
                    }
                },
                onStartDateSelected = { startDateMillis = it },
                onEndDateSelected = { endDateMillis = it },
                onOrderByChange = { orderBy = it },
                onMemoClick = { memo ->
                    onMemoClick(memo)
                },
                onContentUpdate = { memo, newContent ->
                    actionControls?.updateMemo?.invoke(memo, memo.copy(content = newContent))
                })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultContent(
    query: String,
    selectedTags: Set<String>,
    startDateMillis: Long?,
    endDateMillis: Long?,
    orderBy: String,
    availableTags: Map<String, Int>,
    filteredMemos: List<Memo>,
    sessionControls: SessionControls?,
    actionControls: MemoActionControls?,
    onTagClick: (String) -> Unit,
    onStartDateSelected: (Long?) -> Unit,
    onEndDateSelected: (Long?) -> Unit,
    onOrderByChange: (String) -> Unit,
    onMemoClick: (Memo) -> Unit,
    onContentUpdate: (Memo, String) -> Unit
) {
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    if (showStartDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = startDateMillis)
        DatePickerDialog(onDismissRequest = { showStartDatePicker = false }, confirmButton = {
            TextButton(onClick = {
                onStartDateSelected(datePickerState.selectedDateMillis)
                showStartDatePicker = false
            }) {
                Text(stringResource(android.R.string.ok))
            }
        }, dismissButton = {
            TextButton(onClick = {
                onStartDateSelected(null)
                showStartDatePicker = false
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = endDateMillis)
        DatePickerDialog(onDismissRequest = { showEndDatePicker = false }, confirmButton = {
            TextButton(onClick = {
                onEndDateSelected(datePickerState.selectedDateMillis)
                showEndDatePicker = false
            }) {
                Text(stringResource(android.R.string.ok))
            }
        }, dismissButton = {
            TextButton(onClick = {
                onEndDateSelected(null)
                showEndDatePicker = false
            }) {
                Text(stringResource(R.string.common_cancel))
            }
        }) {
            DatePicker(state = datePickerState)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Dates Section
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DateSelectorCard(
                    label = stringResource(R.string.search_date_start),
                    dateMillis = startDateMillis,
                    onClick = { showStartDatePicker = true },
                    onClear = { onStartDateSelected(null) },
                    modifier = Modifier.weight(1f)
                )
                DateSelectorCard(
                    label = stringResource(R.string.search_date_end),
                    dateMillis = endDateMillis,
                    onClick = { showEndDatePicker = true },
                    onClear = { onEndDateSelected(null) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Tag Cloud Section
        if (availableTags.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.profile_stats_tags),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        CompositionLocalProvider(
                            LocalMinimumInteractiveComponentSize provides 0.dp
                        ) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animateContentSize(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                availableTags.forEach { (tag, count) ->
                                    val isSelected = tag in selectedTags
                                    FilterChip(
                                        modifier = Modifier.height(28.dp),
                                        selected = isSelected,
                                        onClick = { onTagClick(tag) },
                                        label = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "#$tag",
                                                    style = MaterialTheme.typography.labelMedium
                                                )
                                                if (count > 0) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = count.toString(),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                            alpha = 0.7f
                                                        )
                                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                            alpha = 0.7f
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        trailingIcon = {
                                            AnimatedVisibility(
                                                visible = isSelected,
                                                enter = fadeIn() + expandHorizontally(),
                                                exit = fadeOut() + shrinkHorizontally()
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Close,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            enabled = true,
                                            selected = isSelected,
                                            borderColor = MaterialTheme.colorScheme.outline.copy(
                                                alpha = 0.5f
                                            ),
                                            selectedBorderColor = MaterialTheme.colorScheme.primary.copy(
                                                alpha = 0.5f
                                            ),
                                            borderWidth = 0.5.dp,
                                            selectedBorderWidth = 0.5.dp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Sort Section
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                ExposedDropdownMenuBox(
                    expanded = showSortMenu, onExpandedChange = { showSortMenu = it }) {
                    val sortLabel = when (orderBy) {
                        "display_time desc" -> stringResource(R.string.search_sort_newest)
                        "display_time asc" -> stringResource(R.string.search_sort_oldest)
                        else -> stringResource(R.string.search_sort_title)
                    }
                    val sortIcon = when (orderBy) {
                        "display_time desc" -> Icons.Outlined.ArrowDownward
                        "display_time asc" -> Icons.Outlined.ArrowUpward
                        else -> Icons.AutoMirrored.Outlined.Sort
                    }

                    Surface(
                        onClick = { showSortMenu = true }, modifier = Modifier.menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryNotEditable, true
                        ), shape = RoundedCornerShape(32.dp), color = Color.Transparent
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .widthIn(min = 160.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = sortIcon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = sortLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showSortMenu)
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.search_sort_newest),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }, leadingIcon = {
                                Icon(
                                    Icons.Outlined.ArrowDownward, null, Modifier.size(18.dp)
                                )
                            }, onClick = {
                                onOrderByChange("display_time desc"); showSortMenu = false
                            }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(R.string.search_sort_oldest),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.ArrowUpward, null, Modifier.size(18.dp)
                                )
                            },
                            onClick = { onOrderByChange("display_time asc"); showSortMenu = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }

        if (filteredMemos.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (query.isBlank() && selectedTags.isEmpty() && startDateMillis == null && endDateMillis == null) stringResource(
                            R.string.memo_search_hint
                        )
                        else stringResource(R.string.memo_search_no_results),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            itemsIndexed(filteredMemos, key = { index, it ->
                val baseKey = it.name.takeUnless { n -> n.isNullOrBlank() }
                    ?: "${it.content.hashCode()}_${it.createTime}"
                "${baseKey}_$index"
            }) { index, memo ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    val isOwner = memo.creator == sessionControls?.state?.currUser?.name
                    MemoItem(
                        memo = memo,
                        user = null, // TODO uiState.users[memo.creator]
                        currentUser = sessionControls?.state?.currUser,
                        token = sessionControls?.state?.token ?: "",
                        hostUrl = sessionControls?.state?.hostUrl ?: "",
                        onClick = {
                            onMemoClick(memo)
                        },
                        headerScale = 1.0f,
                        onContentUpdate = if (isOwner) { newContent ->
                            onContentUpdate(memo, newContent)
                        } else null)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateSelectorCard(
    label: String,
    dateMillis: Long?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ), shape = RoundedCornerShape(12.dp), onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                val dateText = remember(dateMillis) {
                    if (dateMillis != null) {
                        SimpleDateFormat(
                            "MMM dd, yyyy", Locale.getDefault()
                        ).format(Date(dateMillis))
                    } else {
                        "Any" // Will use stringResource(R.string.search_date_any) below
                    }
                }
                Text(
                    text = if (dateMillis != null) dateText else stringResource(R.string.search_date_any),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            if (dateMillis != null) {
                IconButton(
                    onClick = onClear, modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Outlined.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
