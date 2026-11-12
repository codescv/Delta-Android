package com.codescv.delta.ui.library

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codescv.delta.DeltaApplication
import com.codescv.delta.library.RomEntry
import com.codescv.delta.ui.DeltaNavigator
import com.codescv.delta.ui.saves.SaveStatesMode
import com.codescv.delta.ui.saves.SaveStatesSheet
import com.codescv.delta.ui.theme.DeltaColors
import com.codescv.delta.ui.theme.DeltaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val DestructiveRed = Color(0xFFFF453A)

/** Pending SAF export that targets a specific game. */
private sealed interface PendingExport {
    val rom: RomEntry

    data class SaveRam(override val rom: RomEntry) : PendingExport
    data class State(override val rom: RomEntry, val stateId: String) : PendingExport
}

/**
 * Session memory: the library leaves composition while a game runs (unlike iOS, where LibraryView
 * stays alive under the full-screen cover), so the selected page and last cover lookup survive here.
 */
private object LibrarySession {
    var selectedPageId: String? = null
    var covers: Map<String, File?> = emptyMap()
    var screenshots: Map<String, Thumbnail?> = emptyMap()
}

/** Library root (design §5.1, ui_spec §2): the Delta 2.0.5 game browser. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navigator: DeltaNavigator) {
    val context = LocalContext.current
    val container = remember { DeltaApplication.container }
    val library = container.library
    val state by library.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val haptics = LocalHapticFeedback.current

    // Toast (2 s) and the generic "Delta" alert.
    var toast by remember { mutableStateOf<String?>(null) }
    var lastToast by remember { mutableStateOf("") }
    var toastSerial by remember { mutableIntStateOf(0) }
    var alert by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toastSerial) {
        if (toast != null) {
            delay(2_000)
            toast = null
        }
    }

    val controller = remember {
        LibraryController(
            context = context,
            container = container,
            onToast = { text ->
                toast = text
                lastToast = text
                toastSerial++
            },
            onAlert = { alert = it },
        )
    }

    // Delta: LibraryView.onAppear → library.rescan() (also after returning from a game).
    LaunchedEffect(Unit) { controller.rescan() }

    var searchText by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val isSearching = LibraryLogic.isSearching(searchText)
    fun dismissKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }
    BackHandler(enabled = searchText.isNotEmpty()) {
        searchText = ""
        dismissKeyboard()
    }

    // Pages ------------------------------------------------------------------------------------
    val available = state.availableSystems
    val pages = remember(available) { LibraryLogic.pages(available) }
    var selectedPageId by remember { mutableStateOf(LibrarySession.selectedPageId) }
    LaunchedEffect(selectedPageId) { LibrarySession.selectedPageId = selectedPageId }
    val current = LibraryLogic.currentPage(pages, selectedPageId)
    val currentIndex = pages.indexOf(current).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = currentIndex) { pages.size }
    val latestPages by rememberUpdatedState(pages)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { idx ->
            latestPages.getOrNull(idx)?.let { selectedPageId = it.id }
        }
    }
    LaunchedEffect(currentIndex, pages) {
        if (pagerState.currentPage != currentIndex && currentIndex < pagerState.pageCount) {
            pagerState.animateScrollToPage(currentIndex, animationSpec = tween(250, easing = FastOutSlowInEasing))
        }
    }

    // Off-main-thread lookups: covers and Continue Playing screenshots.
    val covers by produceState(LibrarySession.covers, state.entries, state.coverRevision) {
        val entries = state.entries
        value = withContext(Dispatchers.IO) { entries.associate { it.id to container.covers.coverIfExists(it) } }
        LibrarySession.covers = value
    }
    val continueEntries = remember(state) { LibraryLogic.continuePlaying(state) }
    val screenshots by produceState(LibrarySession.screenshots, continueEntries, state.coverRevision) {
        value = withContext(Dispatchers.IO) {
            continueEntries.associate { rom ->
                rom.id to runCatching { container.saves.latestScreenshot(rom)?.let { Thumbnail(it, it.lastModified()) } }.getOrNull()
            }
        }
        LibrarySession.screenshots = value
    }

    // Context menu / dialogs / sheets.
    var menuKey by remember { mutableStateOf<String?>(null) }
    var menuRom by remember { mutableStateOf<RomEntry?>(null) }
    var menuInfo by remember { mutableStateOf<RomMenuInfo?>(null) }
    LaunchedEffect(menuKey) {
        menuInfo = null
        val rom = menuRom
        if (menuKey != null && rom != null) menuInfo = controller.menuInfo(rom)
    }
    var deleteRom by remember { mutableStateOf<RomEntry?>(null) }
    var renameRom by remember { mutableStateOf<RomEntry?>(null) }
    var statesRom by remember { mutableStateOf<RomEntry?>(null) }
    var pendingRom by remember { mutableStateOf<RomEntry?>(null) }
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }
    var refreshing by remember { mutableStateOf(false) }

    // SAF launchers -----------------------------------------------------------------------------
    val importGames = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) controller.importGames(uris)
    }
    val importSkin = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { controller.importSkin(it) }
    }
    val linkFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { controller.linkFolder(it) }
    }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val rom = pendingRom
        pendingRom = null
        if (uri != null && rom != null) controller.changeCover(rom, uri)
    }
    val importSave = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rom = pendingRom
        pendingRom = null
        if (uri != null && rom != null) controller.importSave(rom, uri)
    }
    val importState = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val rom = pendingRom
        pendingRom = null
        if (uri != null && rom != null) controller.importState(rom, uri)
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val req = pendingExport
        pendingExport = null
        if (uri != null && req != null) {
            when (req) {
                is PendingExport.SaveRam -> controller.exportSave(req.rom, uri)
                is PendingExport.State -> controller.exportState(req.rom, req.stateId, uri)
            }
        }
    }

    fun openMenu(key: String, rom: RomEntry) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuRom = rom
        menuKey = key
    }

    fun onMenuAction(rom: RomEntry, action: GameMenuAction) {
        val info = menuInfo
        when (action) {
            GameMenuAction.PLAY -> controller.play(rom, navigator)
            GameMenuAction.RESUME_AUTO_SAVE -> controller.resume(rom, navigator)
            GameMenuAction.TOGGLE_FAVORITE -> controller.toggleFavorite(rom)
            GameMenuAction.RENAME -> renameRom = rom
            GameMenuAction.FETCH_COVER -> controller.fetchCover(rom)
            GameMenuAction.CHANGE_COVER -> {
                pendingRom = rom
                pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            GameMenuAction.RESET_COVER -> controller.resetCover(rom)
            GameMenuAction.IMPORT_SAVE -> {
                pendingRom = rom
                importSave.launch(arrayOf("*/*"))
            }
            GameMenuAction.EXPORT_SAVE -> {
                pendingExport = PendingExport.SaveRam(rom)
                export.launch(container.saves.suggestedSaveRamFileName(rom))
            }
            GameMenuAction.IMPORT_STATE -> {
                pendingRom = rom
                importState.launch(arrayOf("*/*"))
            }
            GameMenuAction.EXPORT_STATE -> {
                val latest = info?.latestState ?: return
                pendingExport = PendingExport.State(rom, latest.id)
                export.launch(container.saves.suggestedStateFileName(rom, latest))
            }
            GameMenuAction.VIEW_STATES -> statesRom = rom
            GameMenuAction.SHARE_ROM -> controller.shareRom(rom)
            GameMenuAction.DELETE -> deleteRom = rom
        }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            controller.rescan().join()
            refreshing = false
        }
    }

    val dismissOnScroll = remember(keyboard, focusManager) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f && source == NestedScrollSource.UserInput) {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
                return Offset.Zero
            }
        }
    }

    @Composable
    fun CardMenu(rom: RomEntry, key: String) {
        GameContextMenu(
            expanded = menuKey == key,
            isFavorite = rom.id in state.favorites,
            info = menuInfo,
            onDismiss = { if (menuKey == key) menuKey = null },
            onAction = { onMenuAction(rom, it) },
        )
    }

    @Composable
    fun Grid(roms: List<RomEntry>, keyPrefix: String, empty: @Composable () -> Unit) {
        val ptrState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            state = ptrState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = ptrState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = DeltaColors.Capsule,
                    color = DeltaColors.Purple,
                )
            },
        ) {
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 150.dp + navBottom),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.fillMaxSize().nestedScroll(dismissOnScroll),
            ) {
                if (roms.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { empty() }
                } else {
                    items(roms, key = { it.id }) { rom ->
                        val key = "$keyPrefix:${rom.id}"
                        Box {
                            LibraryBoxArtCard(
                                rom = rom,
                                cover = covers[rom.id],
                                coverRevision = state.coverRevision,
                                onClick = { controller.play(rom, navigator) },
                                onLongClick = { openMenu(key, rom) },
                            )
                            CardMenu(rom, key)
                        }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(DeltaColors.LibraryBackground)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(
                title = LibraryLogic.topTitle(searchText, current),
                onSettings = { navigator.openSettings() },
                onImportGames = { importGames.launch(arrayOf("*/*")) },
                onImportSkin = { importSkin.launch(arrayOf("*/*")) },
                onLinkFolder = { linkFolder.launch(null) },
                onRescan = { controller.rescan() },
            )
            if (LibraryLogic.showContinuePlaying(state, searchText)) {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Continue Playing",
                        style = DeltaType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = DeltaColors.SecondaryText,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().height(104.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        items(continueEntries, key = { it.id }) { rom ->
                            val key = "continue:${rom.id}"
                            Box {
                                ContinueCard(
                                    rom = rom,
                                    thumbnail = screenshots[rom.id],
                                    onClick = { controller.resume(rom, navigator) },
                                    onLongClick = { openMenu(key, rom) },
                                )
                                CardMenu(rom, key)
                            }
                        }
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (isSearching) {
                    val results = remember(state.entries, searchText) { LibraryLogic.searchResults(state, searchText) }
                    Grid(results, "search") { NoResults(searchText) }
                } else {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { idx -> pages.getOrNull(idx)?.id ?: idx },
                        beyondViewportPageCount = 1,
                    ) { idx ->
                        val page = pages.getOrNull(idx)
                        if (page != null) {
                            val roms = remember(state, page) { LibraryLogic.entries(page, state) }
                            Grid(roms, "page-${page.id}") { EmptyPage(page) }
                        }
                    }
                }
            }
        }

        // Floating bottom controls: toast, page switcher, search bar.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) { LibraryToast(toast ?: lastToast) }
            if (LibraryLogic.showPageSwitcher(pages, searchText)) {
                PageSwitcher(pages, current) { page -> selectedPageId = page.id }
            }
            LibrarySearchBar(
                text = searchText,
                onTextChange = { searchText = it },
                focusRequester = searchFocus,
                onClear = {
                    searchText = ""
                    dismissKeyboard()
                },
                onSubmit = { keyboard?.hide() },
            )
        }
    }

    // Dialogs & sheets ------------------------------------------------------------------------
    deleteRom?.let { rom ->
        DeleteGameDialog(
            title = rom.title,
            onDelete = { includingSaves ->
                deleteRom = null
                controller.delete(rom, includingSaves)
            },
            onDismiss = { deleteRom = null },
        )
    }
    renameRom?.let { rom ->
        RenameDialog(
            initial = rom.title,
            onRename = { name ->
                renameRom = null
                controller.rename(rom, name)
            },
            onDismiss = { renameRom = null },
        )
    }
    statesRom?.let { rom ->
        SaveStatesSheet(
            rom = rom,
            mode = SaveStatesMode.MANAGE,
            session = null,
            onDismiss = { statesRom = null },
            onLaunch = { stateId ->
                statesRom = null
                controller.playState(rom, stateId, navigator)
            },
        )
    }
    alert?.let { message ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text("Delta") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text("OK", color = DeltaColors.Purple) } },
            containerColor = DeltaColors.GroupedCard,
            titleContentColor = Color.White,
            textContentColor = Color.White,
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    onSettings: () -> Unit,
    onImportGames: () -> Unit,
    onImportSkin: () -> Unit,
    onLinkFolder: () -> Unit,
    onRescan: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryGlassCircle(Icons.Outlined.Settings, "Settings", onSettings)
        AnimatedContent(
            targetState = title,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
            modifier = Modifier.weight(1f),
            label = "pageTitle",
        ) { text ->
            Text(
                text,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box {
            LibraryGlassCircle(Icons.Filled.Add, "Import Games") { menu = true }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                shape = RoundedCornerShape(14.dp),
                containerColor = Color(0xFF2C2C2E),
            ) {
                val colors = MenuDefaults.itemColors(textColor = Color.White, trailingIconColor = Color.White)
                listOf(
                    Triple("Import Games (.zip/.gba/.nes/.sfc/.gbc/.nds/.z64)…", Icons.Outlined.SaveAlt, onImportGames),
                    Triple("Import Controller Skin (.deltaskin)…", Icons.Outlined.Palette, onImportSkin),
                    Triple("Link ROM Folder…", Icons.Outlined.CreateNewFolder, onLinkFolder),
                    Triple("Rescan Library", Icons.Filled.Refresh, onRescan),
                ).forEach { (label, icon, action) ->
                    DropdownMenuItem(
                        text = { Text(label, style = DeltaType.Body) },
                        trailingIcon = { Icon(icon, contentDescription = null) },
                        colors = colors,
                        onClick = {
                            menu = false
                            action()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPage(page: LibraryPage) {
    Column(
        Modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 140.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("No Games", style = DeltaType.Title2, color = Color.White)
        Text(LibraryLogic.emptySubtitle(page), style = DeltaType.Footnote, color = DeltaColors.SecondaryText, textAlign = TextAlign.Center)
    }
}

@Composable
private fun NoResults(searchText: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("No Results", style = DeltaType.Title3.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Text(LibraryLogic.noResultsMessage(searchText), style = DeltaType.Footnote, color = DeltaColors.SecondaryText, textAlign = TextAlign.Center)
    }
}

/** iOS confirmation dialog `Delete <title>?` → `Delete Game` / `Delete Game and Saves` / Cancel. */
@Composable
private fun DeleteGameDialog(title: String, onDelete: (includingSaves: Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $title?", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        containerColor = DeltaColors.GroupedCard,
        titleContentColor = Color.White,
        confirmButton = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { onDelete(false) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete Game", color = DestructiveRed, style = DeltaType.Body)
                }
                TextButton(onClick = { onDelete(true) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete Game and Saves", color = DestructiveRed, style = DeltaType.Body)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel", color = DeltaColors.Purple, style = DeltaType.Headline)
                }
            }
        },
    )
}

@Composable
private fun RenameDialog(initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Game") },
        containerColor = DeltaColors.GroupedCard,
        titleContentColor = Color.White,
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = DeltaColors.Purple,
                    cursorColor = DeltaColors.Purple,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onRename(text) }) { Text("Save", color = DeltaColors.Purple) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DeltaColors.Purple) } },
    )
}
