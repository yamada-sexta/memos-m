package org.example.memosm.ui.nav

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.example.memosm.R
import org.example.memosm.model.MapScope
import org.example.memosm.model.MapPlace
import org.example.memosm.model.Memo
import org.example.memosm.model.memosAtMapPlace
import org.example.memosm.model.ownMapLocation
import org.example.memosm.model.sharedMapLabel
import org.example.memosm.ui.component.MemosScaffold
import org.example.memosm.ui.component.MemoSearchBar
import org.example.memosm.ui.component.MemoPreviewItem
import org.example.memosm.ui.component.composer.EditorRequest
import org.example.memosm.ui.component.composer.rememberMemoEditorLauncher
import org.example.memosm.data.resolveLocationName
import kotlinx.coroutines.launch
import org.example.memosm.ui.component.map.MemoMapController
import org.example.memosm.ui.component.map.NativeMemoMap
import org.example.memosm.ui.component.map.mapPinLabelColor
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
    val allController = rememberSaveable(saver = MemoMapController.Saver) { MemoMapController() }
    val controller = when (map.scope) {
        MapScope.MEMOS -> ownController
        MapScope.EXPLORE -> exploreController
        MapScope.ALL -> allController
    }
    val listState = rememberLazyListState()
    var searchResults by remember { mutableStateOf<List<Memo>?>(null) }
    var searchExpanded by remember { mutableStateOf(false) }
    var requestedTag by remember { mutableStateOf<String?>(null) }
    var place by remember(map.scope) { mutableStateOf<MapPlace?>(null) }
    var mapBounds by remember { mutableStateOf(Rect.Zero) }
    var panelBounds by remember(map.scope) { mutableStateOf<Rect?>(null) }
    val openEditor = rememberMemoEditorLauncher(viewModel)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDrafting by remember { mutableStateOf(false) }
    var tileError by remember { mutableStateOf(false) }
    var tileRetry by remember { mutableIntStateOf(0) }
    var showViews by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableIntStateOf(0) }
    val controlsBottom = with(LocalDensity.current) { controlsHeight.toDp() } + 12.dp
    val configuration = LocalConfiguration.current
    val bottom = if (configuration.screenWidthDp < 600 && isNavBarVisible && !searchExpanded) 80.dp else 0.dp
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    val visible = remember(map.memos, searchResults) {
        val current = map.memos.associateBy { it.name }
        searchResults?.mapNotNull { current[it.name] } ?: map.memos
    }
    val selected = remember(visible, place) {
        place?.let { memosAtMapPlace(visible, it) }.orEmpty()
    }
    DisposableEffect(viewModel) {
        viewModel.memoMapManager.open()
        onDispose { viewModel.memoMapManager.close() }
    }
    BackHandler(place != null && !searchExpanded && ui.detailPane.selectedMemo == null) { place = null }
    MemosScaffold(
        viewModel = viewModel,
        memos = map.memos,
        listState = listState,
        navigationKey = "map-${map.scope}",
        onToggleNavBar = { onToggleNavBar?.invoke(it) },
        isNavBarVisible = isNavBarVisible,
        listPane = { onMemoClick ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = bottom).testTag("memo_map")) {
                val desktop = maxWidth >= 600.dp
                val sheetWidth = if (desktop) 380.dp else maxWidth
                val sheetMaxHeight = maxHeight * 0.7f
                Box(Modifier.fillMaxSize()) {
                    key(map.scope) {
                        NativeMemoMap(controller, visible, dark, MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxSize().testTag("memo_map_canvas")
                                .onGloballyPositioned { mapBounds = it.boundsInWindow() }, retry = tileRetry,
                            labelColor = mapPinLabelColor(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
                            initialFitReady = !map.isLoading && (map.complete || map.isOffline || map.loadFailed),
                            selection = place?.location,
                            panelBounds = if (place != null && !searchExpanded)
                                panelBounds?.translate(-mapBounds.left, -mapBounds.top) else null,
                            onPlace = { place = it }, onTileError = { tileError = it })
                    }
                    Box(Modifier.fillMaxSize().padding(top = controlsBottom)) {
                        Column(Modifier.align(Alignment.TopStart).padding(horizontal = 12.dp).widthIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (map.isLoading && !searchExpanded) MapNotice(stringResource(R.string.map_loaded, map.memos.size))
                            if (map.isOffline && !searchExpanded) MapNotice(stringResource(R.string.map_offline))
                            if (map.filterUnavailable) MapNotice(stringResource(R.string.map_view_offline))
                            if (map.loadFailed) MapNotice(stringResource(R.string.map_load_error),
                                stringResource(R.string.map_retry)) { viewModel.memoMapManager.load() }
                            if (tileError) MapNotice(stringResource(R.string.map_tile_error),
                                stringResource(R.string.map_retry)) { tileRetry++ }
                        }
                        if (desktop || place == null) {
                            Surface(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                                shape = RoundedCornerShape(50), shadowElevation = 3.dp) {
                                IconButton(onClick = controller::fitAll, enabled = visible.isNotEmpty(),
                                    modifier = Modifier.testTag("map_fit_all")) {
                                    Icon(Icons.Outlined.MyLocation, stringResource(R.string.map_fit_all))
                                }
                            }
                        }
                        if ((map.complete || map.isOffline) && visible.isEmpty() && place == null && !map.filterUnavailable && !map.loadFailed) {
                            MapNotice(stringResource(if (map.memos.isNotEmpty() || map.savedView != null)
                                R.string.map_no_results else R.string.map_empty), modifier = Modifier.align(Alignment.Center).padding(24.dp))
                        }
                        place?.takeIf { !searchExpanded }?.let { selection ->
                            val point = selection.location
                            val sheetState = rememberBottomSheetState(initialValue = SheetValue.PartiallyExpanded)
                            LaunchedEffect(sheetState.currentValue) {
                                if (sheetState.currentValue == SheetValue.Hidden) place = null
                            }
                            BottomSheetScaffold(
                                modifier = Modifier.fillMaxSize(),
                                scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState),
                                sheetPeekHeight = 280.dp,
                                sheetMaxWidth = sheetWidth,
                                sheetDragHandle = { BottomSheetDefaults.DragHandle(Modifier.testTag("map_place_drag_handle")) },
                                containerColor = Color.Transparent,
                                sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                sheetContent = {
                                Column(Modifier.fillMaxWidth()
                                    .heightIn(max = sheetMaxHeight)
                                    .onGloballyPositioned { panelBounds = it.boundsInWindow() }
                                    .testTag("map_place_panel").padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(sharedMapLabel(selected).takeIf { selection.memoLocations.distinct().size == 1 }
                                        ?: "${point.latitude}, ${point.longitude}",
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        maxLines = 2, style = MaterialTheme.typography.titleMedium)
                                    Text(stringResource(R.string.map_place_count, selected.size),
                                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
                                    if (ui.session.currUser != null) {
                                        FilledTonalButton(onClick = {
                                            ui.accounts.firstOrNull { it.isActive }?.let { account ->
                                                isDrafting = true
                                                scope.launch {
                                                    try {
                                                        val location = resolveLocationName(context,
                                                            ownMapLocation(point, selected, ui.session.currUser?.name))
                                                        openEditor(EditorRequest(account.id, titleRes = R.string.map_draft_here,
                                                            location = location,
                                                            visibility = ui.session.userSettings?.memoVisibility ?: org.example.memosm.model.Visibility.PRIVATE))
                                                    } finally {
                                                        isDrafting = false
                                                    }
                                                }
                                            }
                                        }, enabled = !isDrafting, shape = RoundedCornerShape(50), modifier = Modifier.testTag("map_new_here")) {
                                            if (isDrafting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                            else Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.map_draft_here))
                                        }
                                    }
                                    LazyColumn(Modifier.weight(1f, fill = false).testTag("map_place_memos"),
                                        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (selected.isEmpty()) item {
                                            Text(stringResource(R.string.map_place_empty), modifier = Modifier.padding(12.dp))
                                        }
                                        items(selected, key = { it.name!! }) { memo ->
                                            MemoPreviewItem(viewModel = viewModel, memo = memo,
                                                user = if (map.scope == MapScope.MEMOS) null else ui.users[memo.creator],
                                                onClick = { onMemoClick(memo) }, onHashtagClick = { requestedTag = it })
                                        }
                                    }
                                }
                            }) {}
                        }
                    }
                    Column(Modifier.align(Alignment.TopCenter)
                        .then(if (searchExpanded) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(top = 12.dp))
                        .onSizeChanged { controlsHeight = it.height }) {
                      MemoSearchBar(viewModel = viewModel, onMemoClick = onMemoClick,
                        localMemos = map.memos, onLocalResultsChanged = { searchResults = it },
                        onExpandedChange = { searchExpanded = it; onToggleNavBar?.invoke(!it) },
                        filterActionLabel = stringResource(R.string.map_show_results),
                        requestedTag = requestedTag, onTagHandled = { requestedTag = null },
                        extraFilters = {
                            if (map.scope == MapScope.MEMOS && ui.userMemoList.shortcuts.isNotEmpty()) {
                                Box(Modifier.padding(horizontal = 16.dp)) {
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
                        })
                      if (!searchExpanded) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp).testTag("map_scopes"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MapScope.entries.forEach { scope ->
                                val shape = RoundedCornerShape(50)
                                FilterChip(selected = map.scope == scope,
                                    onClick = { if (map.scope != scope) viewModel.memoMapManager.selectScope(scope) },
                                    shape = shape, border = null,
                                    colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
                                    modifier = Modifier.testTag("map_scope_${scope.name.lowercase()}"),
                                    leadingIcon = { Icon(when (scope) {
                                        MapScope.MEMOS -> Icons.Outlined.Description
                                        MapScope.EXPLORE -> Icons.Outlined.Explore
                                        MapScope.ALL -> Icons.Outlined.Layers
                                    }, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                    label = { Text(stringResource(when (scope) {
                                        MapScope.MEMOS -> R.string.nav_memos
                                        MapScope.EXPLORE -> R.string.nav_explore
                                        MapScope.ALL -> R.string.map_all
                                    })) })
                            }
                        }
                      }
                    }
                }
            }
        }
    )
}

@Composable
private fun MapNotice(text: String, action: String? = null, modifier: Modifier = Modifier, onAction: () -> Unit = {}) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp) {
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
