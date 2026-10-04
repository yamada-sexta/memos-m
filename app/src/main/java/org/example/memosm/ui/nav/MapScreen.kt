package org.example.memosm.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.Location
import org.example.memosm.model.MapScope
import org.example.memosm.model.ownMapLocation
import org.example.memosm.model.sharedMapLabel
import org.example.memosm.ui.component.MemosScaffold
import org.example.memosm.ui.component.composer.MemoComposerScreen
import org.example.memosm.ui.component.map.MemoMapController
import org.example.memosm.ui.component.map.NativeMemoMap
import org.example.memosm.viewmodel.MemosViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MemosViewModel,
    isNavBarVisible: Boolean = true,
    onToggleNavBar: ((Boolean) -> Unit)? = null
) {
    val ui by viewModel.uiState.collectAsState()
    val map = ui.memoMap
    val ownController = rememberSaveable(saver = MemoMapController.Saver) { MemoMapController() }
    val exploreController = rememberSaveable(saver = MemoMapController.Saver) { MemoMapController() }
    val controller = if (map.scope == MapScope.MEMOS) ownController else exploreController
    val listState = rememberLazyListState()
    var query by rememberSaveable(map.scope) { mutableStateOf("") }
    var tag by rememberSaveable(map.scope) { mutableStateOf("") }
    var place by remember(map.scope) { mutableStateOf<Location?>(null) }
    var panelSize by remember(map.scope) { mutableStateOf(IntSize.Zero) }
    var composeLocation by remember { mutableStateOf<Location?>(null) }
    var tileError by remember { mutableStateOf(false) }
    var tileRetry by remember { mutableIntStateOf(0) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var showViews by remember { mutableStateOf(false) }
    val desktop = LocalConfiguration.current.screenWidthDp >= 600
    val bottom = if (!desktop && isNavBarVisible) 80.dp else 0.dp
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    val visible = remember(map.memos, query, tag) {
        map.memos.filter { memo ->
            (query.isBlank() || memo.content.contains(query, ignoreCase = true)) &&
                (tag.isBlank() || memo.tags.orEmpty().contains(tag.trim().removePrefix("#")))
        }
    }
    val selected = remember(visible, place) {
        if (place == null) emptyList() else visible.filter {
            it.location?.latitude == place?.latitude && it.location?.longitude == place?.longitude
        }
    }
    DisposableEffect(viewModel) {
        viewModel.memoMapManager.open()
        onDispose { viewModel.memoMapManager.close() }
    }
    BackHandler(place != null && composeLocation == null) { place = null }
    MemosScaffold(
        viewModel = viewModel,
        memos = map.memos,
        listState = listState,
        navigationKey = "map-${map.scope}",
        onToggleNavBar = { onToggleNavBar?.invoke(it) },
        isNavBarVisible = isNavBarVisible,
        listPane = { onMemoClick ->
            Box(Modifier.fillMaxSize().padding(bottom = bottom).testTag("memo_map")) {
                Column(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.nav_map), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showFilters = !showFilters }) { Text(stringResource(R.string.map_filters)) }
                                IconButton(onClick = { viewModel.memoMapManager.load() }) {
                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.map_retry))
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MapScope.entries.forEach { scope ->
                                    FilterChip(selected = map.scope == scope,
                                        onClick = { if (map.scope != scope) viewModel.memoMapManager.selectScope(scope) },
                                        label = { Text(stringResource(if (scope == MapScope.MEMOS) R.string.nav_memos else R.string.nav_explore)) })
                                }
                                if (map.scope == MapScope.MEMOS) {
                                    Box {
                                        FilterChip(selected = map.savedView != null, onClick = { showViews = true },
                                            label = { Text(map.savedView?.title ?: stringResource(R.string.map_all_views)) })
                                        DropdownMenu(expanded = showViews, onDismissRequest = { showViews = false }) {
                                            DropdownMenuItem(text = { Text(stringResource(R.string.map_all_views)) }, onClick = {
                                                showViews = false; viewModel.memoMapManager.selectView(null)
                                            })
                                            ui.userMemoList.shortcuts.forEach { view ->
                                                DropdownMenuItem(text = { Text(view.title.orEmpty()) }, onClick = {
                                                    showViews = false; viewModel.memoMapManager.selectView(view)
                                                })
                                            }
                                        }
                                    }
                                }
                            }
                            if (showFilters) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                                        label = { Text(stringResource(R.string.map_search)) }, modifier = Modifier.weight(1f))
                                    OutlinedTextField(value = tag, onValueChange = { tag = it }, singleLine = true,
                                        label = { Text(stringResource(R.string.map_tag)) }, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        key(map.scope) {
                            NativeMemoMap(controller, visible, dark, MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxSize().testTag("memo_map_canvas"), retry = tileRetry,
                                selection = place, panelSize = panelSize, desktop = desktop,
                                onPlace = { place = it }, onDismiss = { place = null }, onTileError = { tileError = it })
                        }
                        Column(Modifier.align(Alignment.TopStart).padding(12.dp).widthIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (map.isLoading) MapNotice(stringResource(R.string.map_loaded, map.memos.size))
                            if (map.isOffline) MapNotice(stringResource(R.string.map_offline))
                            if (map.filterUnavailable) MapNotice(stringResource(R.string.map_view_offline))
                            if (map.loadFailed) MapNotice(stringResource(R.string.map_load_error),
                                stringResource(R.string.map_retry)) { viewModel.memoMapManager.load() }
                            if (tileError) MapNotice(stringResource(R.string.map_tile_error),
                                stringResource(R.string.map_retry)) { tileRetry++ }
                        }
                        FilledIconButton(onClick = controller::fitAll, enabled = visible.isNotEmpty(),
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).testTag("map_fit_all")) {
                            Icon(Icons.Outlined.MyLocation, stringResource(R.string.map_fit_all))
                        }
                        if ((map.complete || map.isOffline) && visible.isEmpty() && place == null && !map.filterUnavailable && !map.loadFailed) {
                            MapNotice(stringResource(if (query.isNotBlank() || tag.isNotBlank() || map.savedView != null)
                                R.string.map_no_results else R.string.map_empty), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                        }
                        place?.let { point ->
                            Surface(modifier = Modifier
                                .align(if (desktop) Alignment.CenterEnd else Alignment.BottomCenter)
                                .padding(12.dp).widthIn(max = 380.dp).fillMaxWidth()
                                .heightIn(max = if (desktop) 480.dp else 300.dp).onSizeChanged { panelSize = it }.testTag("map_place_panel"),
                                shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 6.dp) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(sharedMapLabel(selected)
                                            ?: "${point.latitude}, ${point.longitude}",
                                            modifier = Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleMedium)
                                        IconButton(onClick = { place = null }) { Icon(Icons.Outlined.Close, stringResource(R.string.map_close)) }
                                    }
                                    if (ui.session.currUser != null) {
                                        Button(onClick = {
                                            viewModel.draftDelegate.initializeNewDraftSession()
                                            composeLocation = ownMapLocation(point, selected, ui.session.currUser?.name)
                                        }, modifier = Modifier.fillMaxWidth().testTag("map_new_here")) {
                                            Text(stringResource(R.string.map_new_here))
                                        }
                                    }
                                    LazyColumn(Modifier.weight(1f, fill = false)) {
                                        items(selected, key = { it.name!! }) { memo ->
                                            ListItem(
                                                headlineContent = { Text(memo.content, maxLines = 3) },
                                                supportingContent = { Text(ui.users[memo.creator]?.displayName ?: memo.creator.orEmpty()) },
                                                trailingContent = { TextButton(onClick = { onMemoClick(memo) }) {
                                                    Text(stringResource(R.string.map_open_memo))
                                                } }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
    composeLocation?.let { location ->
        Box(Modifier.fillMaxSize()) {
            MemoComposerScreen(viewModel = viewModel, hostUrl = ui.session.hostUrl,
                title = stringResource(R.string.map_new_here), initialLocation = location,
                onToggleNavBar = onToggleNavBar,
                onDismiss = { composeLocation = null; viewModel.memoMapManager.refreshIfOpen() })
        }
    }
}

@Composable
private fun MapNotice(text: String, action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp, shadowElevation = 2.dp) {
        Column(Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (action != null) TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview
@Composable
private fun MapNoticePreview() {
    MaterialTheme { MapNotice("Showing cached memo locations") }
}
