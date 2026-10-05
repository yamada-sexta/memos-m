package org.example.memosm.ui.component

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
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
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.memosm.R
import org.example.memosm.api.MemoOrderBy
import org.example.memosm.model.Memo
import org.example.memosm.model.Visibility
import org.example.memosm.ui.component.item.MemoItem
import org.example.memosm.ui.nav.SettingsSection
import org.example.memosm.ui.profile.SettingsActivity
import org.example.memosm.viewmodel.MemosUiState
import org.example.memosm.viewmodel.MemosViewModel
import org.example.memosm.viewmodel.manager.LocalSearchFilter

/** The map has already loaded its history, so search can filter the same pool offline. */
internal fun filterLocalSearchMemos(
    memos: List<Memo>, query: String, tags: Set<String>, start: Long?, end: Long?, order: MemoOrderBy
): List<Memo> {
    val matches = memos.filter { memo ->
        val created = memo.createTime?.toEpochMilliseconds()
        memo.content.contains(query.trim(), ignoreCase = true) && tags.all { it in memo.tags.orEmpty() } &&
            (start == null || (created != null && created >= start)) &&
            (end == null || (created != null && created < end + 86_400_000L))
    }
    return if (order == MemoOrderBy.OLDEST) matches.sortedWith(compareBy<Memo> { it.createTime }.thenBy { it.name })
    else matches.sortedWith(compareByDescending<Memo> { it.createTime }.thenBy { it.name })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoSearchBar(
    modifier: Modifier = Modifier,
    viewModel: MemosViewModel,
    isExplore: Boolean = false,
    onMemoClick: (Memo) -> Unit,
    onExpandedChange: (Boolean) -> Unit = {},
    placeholder: String = stringResource(R.string.memo_search_placeholder),
    localMemos: List<Memo>? = null,
    onLocalResultsChanged: (List<Memo>) -> Unit = {},
    filterActionLabel: String? = null,
    requestedTag: String? = null,
    onTagHandled: () -> Unit = {},
    extraFilters: @Composable () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val cacheSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshAfterProfileDetails()
    }
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searchBarState = rememberSearchBarState()
    val expanded = searchBarState.targetValue == SearchBarValue.Expanded
    val searchScope = rememberCoroutineScope()
    fun collapseSearch() { searchScope.launch { searchBarState.animateToCollapsed() } }
    val focusManager = LocalFocusManager.current
    val containerFocusRequester = remember { FocusRequester() }

    // Report restored expansion too, so the feed header and navigation stay in sync.
    LaunchedEffect(expanded) { onExpandedChange(expanded) }

    // Maintain a set of selected tags for AND filtering within the search context
    var searchSelectedTags by rememberSaveable { mutableStateOf(setOf<String>()) }
    var startDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var endDateMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    var orderBy by rememberSaveable { mutableStateOf(MemoOrderBy.NEWEST) }
    val tagHandled by androidx.compose.runtime.rememberUpdatedState(onTagHandled)
    LaunchedEffect(requestedTag) {
        requestedTag?.let { tag ->
            searchSelectedTags = if (tag in searchSelectedTags) searchSelectedTags - tag else searchSelectedTags + tag
            tagHandled()
        }
    }

    // Search results are shared in the view model. Keep them scoped to the
    // active feed, including while a new query is being debounced.
    val currentUserName = uiState.session.currUser?.name
    val searchMemos = remember(localMemos, query, searchSelectedTags, startDateMillis, endDateMillis, orderBy,
        uiState.searchMemoList.list.items, isExplore, currentUserName) {
        if (localMemos != null) filterLocalSearchMemos(localMemos, query, searchSelectedTags, startDateMillis, endDateMillis, orderBy)
        else if (isExplore) uiState.searchMemoList.list.items.filter {
            it.visibility == Visibility.PUBLIC || it.visibility == Visibility.PROTECTED
        } else uiState.searchMemoList.list.items.filter {
            currentUserName != null && it.creator == currentUserName
        }
    }

    // Aggregate tags from the search pool to be context-accurate
    val availableTags =
        remember(localMemos, searchMemos, uiState.session.userStats, isExplore) {
            if (localMemos != null) {
                localMemos.flatMap { it.tags.orEmpty() }.groupingBy { it }.eachCount()
                    .toList().sortedByDescending { it.second }.toMap()
            } else if (isExplore) {
                val tags = mutableMapOf<String, Int>()
                searchMemos.forEach { memo ->
                    val regex = "#(\\w+)".toRegex()
                    regex.findAll(memo.content).forEach { match ->
                        val tag = match.groupValues[1]
                        tags[tag] = (tags[tag] ?: 0) + 1
                    }
                }
                tags.toList().sortedByDescending { it.second }.toMap()
            } else {
                uiState.session.userStats?.tagCount ?: emptyMap()
            }
        }

    // Effect to trigger server-side search whenever filters change
    val resultsCallback by androidx.compose.runtime.rememberUpdatedState(onLocalResultsChanged)
    LaunchedEffect(localMemos, searchMemos) {
        if (localMemos != null) resultsCallback(searchMemos)
    }
    LaunchedEffect(query, searchSelectedTags, startDateMillis, endDateMillis, orderBy, expanded, isExplore, localMemos) {
        if (expanded && localMemos == null) {
            // Debounce the search to prevent excessive API calls while typing
            delay(300)
            viewModel.userDelegate.refreshUserStats()

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
            viewModel.searchMemos(
                isExplore, filterString, orderBy,
                LocalSearchFilter(
                    query = query,
                    tags = searchSelectedTags.toList(),
                    startMillis = startDateMillis ?: 0L,
                    // End date inclusive: same day-range semantics as the server filter above
                    endMillis = endDateMillis?.plus(86400000L) ?: 0L,
                    // Explore-tab offline search must not surface own/private memos
                    explore = isExplore
                )
            )
        }
    }

    // Capture initial focus on the container to prevent the SearchBar from auto-focusing
    LaunchedEffect(Unit) {
        containerFocusRequester.requestFocus()
    }

    var showSyncPanel by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .then(if (expanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = if (expanded) 0.dp else 16.dp)
            .focusRequester(containerFocusRequester)
            .focusable()
            .zIndex(1f)
    ) {
        val inputField: @Composable () -> Unit = {
            SearchBarDefaults.InputField(
                textFieldState = queryState,
                searchBarState = searchBarState,
                onSearch = {
                    focusManager.clearFocus()
                    if (filterActionLabel != null) collapseSearch()
                },
                placeholder = { Text(placeholder) },
                leadingIcon = {
                    if (expanded) {
                        IconButton(onClick = {
                            collapseSearch()
                            onExpandedChange(false)
                            focusManager.clearFocus()
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.memo_detail_back))
                        }
                    } else {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    }
                },
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (query.isNotEmpty() || searchSelectedTags.isNotEmpty() || startDateMillis != null || endDateMillis != null) {
                            IconButton(onClick = {
                                queryState.setTextAndPlaceCursorAtEnd("")
                                searchSelectedTags = emptySet()
                                startDateMillis = null
                                endDateMillis = null
                            }) {
                                Icon(Icons.Outlined.Clear, contentDescription = null)
                            }
                        }
                        // Sync/cache status icon, hidden while search is expanded.
                        // Lives inside the search bar's own trailing slot so
                        // it is always vertically centered with the input.
                        androidx.compose.animation.AnimatedVisibility(visible = !expanded && localMemos == null) {
                            SyncStatusIconButton(
                                uiState = uiState,
                                onClick = { showSyncPanel = true }
                            )
                        }
                    }
                },
            )
        }
        SearchBar(
            state = searchBarState,
            modifier = Modifier.fillMaxWidth().testTag("memo_search_bar"),
            inputField = inputField
        )
        ExpandedFullScreenSearchBar(
            state = searchBarState,
            inputField = inputField,
            // MemosScaffold already handles status bar padding.
            windowInsets = { WindowInsets(0, 0, 0, 0) }
        ) {
            SearchResultContent(
                query = query,
                selectedTags = searchSelectedTags,
                startDateMillis = startDateMillis,
                endDateMillis = endDateMillis,
                orderBy = orderBy,
                availableTags = availableTags,
                filteredMemos = searchMemos,
                uiState = uiState,
                filtersOnly = localMemos != null,
                extraFilters = extraFilters,
                filterActionLabel = filterActionLabel,
                onApplyFilters = { collapseSearch(); focusManager.clearFocus() },
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
                    viewModel.memoActionDelegate.updateMemo(
                        memo,
                        newContent,
                        memo.visibility,
                        memo.attachments ?: emptyList(),
                        memo.location,
                        null
                    )
                })
        }

        if (showSyncPanel) {
            SyncStatusPanel(
                uiState = uiState,
                onDismiss = { showSyncPanel = false },
                onDeleteOp = { opId -> viewModel.deletePendingOp(opId) },
                onManageCache = {
                    showSyncPanel = false
                    cacheSettings.launch(SettingsActivity.createIntent(context, SettingsSection.OFFLINE))
                }
            )
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
    orderBy: MemoOrderBy,
    availableTags: Map<String, Int>,
    filteredMemos: List<Memo>,
    uiState: MemosUiState,
    onTagClick: (String) -> Unit,
    onStartDateSelected: (Long?) -> Unit,
    onEndDateSelected: (Long?) -> Unit,
    onOrderByChange: (MemoOrderBy) -> Unit,
    onMemoClick: (Memo) -> Unit,
    onContentUpdate: (Memo, String) -> Unit,
    filtersOnly: Boolean = false,
    extraFilters: @Composable () -> Unit = {},
    filterActionLabel: String? = null,
    onApplyFilters: () -> Unit = {}
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
                    shape = MaterialTheme.shapes.large
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
                                        shape = MaterialTheme.shapes.medium,
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
                        MemoOrderBy.NEWEST -> stringResource(R.string.search_sort_newest)
                        MemoOrderBy.OLDEST -> stringResource(R.string.search_sort_oldest)
                        else -> stringResource(R.string.search_sort_title)
                    }
                    val sortIcon = when (orderBy) {
                        MemoOrderBy.NEWEST -> Icons.Outlined.ArrowDownward
                        MemoOrderBy.OLDEST -> Icons.Outlined.ArrowUpward
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
                                onOrderByChange(MemoOrderBy.NEWEST); showSortMenu = false
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
                            onClick = { onOrderByChange(MemoOrderBy.OLDEST); showSortMenu = false },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                        )
                    }
                }
            }
        }

        item { extraFilters() }
        if (filtersOnly) {
            if (filterActionLabel != null) item {
                TextButton(onClick = onApplyFilters, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(filterActionLabel)
                }
            }
        }

        if (!filtersOnly && uiState.searchMemoList.list.isOffline) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = stringResource(R.string.search_offline_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (!filtersOnly && uiState.searchMemoList.list.isLoading) {
            item {
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

        if (filtersOnly) return@LazyColumn
        if (filteredMemos.isEmpty() && !uiState.searchMemoList.list.isLoading) {
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
                // Stable key by memo name (consistent with the main list) so
                // merged/replaced results reuse items instead of rebuilding.
                if (!it.name.isNullOrBlank()) it.name
                else "${it.content.hashCode()}_${it.createTime}_$index"
            }) { index, memo ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    val isOwner = memo.creator == uiState.session.currUser?.name
                    MemoItem(
                        memo = memo,
                        user = uiState.users[memo.creator],
                        currentUser = uiState.session.currUser,
                        token = uiState.session.token,
                        hostUrl = uiState.session.hostUrl,
                        onClick = {
                            onMemoClick(memo)
                        },
                        headerScale = uiState.appSettings.headerScale,
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
    val context = LocalContext.current
    Card(
        modifier = modifier, colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ), shape = MaterialTheme.shapes.large, onClick = onClick
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
                val dateText = dateMillis?.let {
                    DateFormat.getMediumDateFormat(context).format(Date(it))
                }
                Text(
                    text = dateText ?: stringResource(R.string.search_date_any),
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
