package com.jt.notes.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyStaggeredGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.Filter
import com.jt.notes.MainActivity
import com.jt.notes.NoteItem
import com.jt.notes.NotesViewModel
import com.jt.notes.Sort
import com.jt.notes.data.LinkPreview
import com.jt.notes.data.LinkPreviews
import com.jt.notes.data.UpdateCheck
import com.jt.notes.data.WhatsNew
import com.jt.notes.data.parseItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: NotesViewModel,
    gridState: LazyStaggeredGridState,
    selectedId: Long?,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val notes by vm.items.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()
    val lockOn by vm.lockEnabled.collectAsStateWithLifecycle()
    val autoFolder by vm.autoBackupFolder.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val templates by vm.templates.collectAsStateWithLifecycle()
    val allNotes by vm.allNotes.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var expandedMenuGroup by remember { mutableStateOf<String?>(null) }
    var showManageLabels by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAutoBackupDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showReminders by remember { mutableStateOf(false) }
    var showTemplates by remember { mutableStateOf(false) }
    var showCalendar by remember { mutableStateOf(false) }
    // Self-updates: the release on offer, while its APK downloads, and while a check runs.
    var updateOffer by remember { mutableStateOf<UpdateCheck.Update?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var checkingUpdate by remember { mutableStateOf(false) }
    // What's new: the version and notes to show once after an update, if any are due.
    var whatsNew by remember { mutableStateOf<Pair<String, List<String>>?>(null) }
    val updateScope = rememberCoroutineScope()
    val themeId by vm.theme.collectAsStateWithLifecycle()
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(setOf<Long>()) }
    // Set while the person is dragging notes around: the order being shown.
    var arrangeList by remember { mutableStateOf<List<NoteItem>?>(null) }
    LaunchedEffect(filter, query) { arrangeList = null }
    // "Select notes" in the menu: taps pick notes for bulk actions. It ends when the picks are cleared.
    var selectMode by remember { mutableStateOf(false) }
    var pickedAny by remember { mutableStateOf(false) }
    LaunchedEffect(selection) {
        if (selection.isNotEmpty()) pickedAny = true
        else if (pickedAny) { pickedAny = false; selectMode = false }
    }
    LaunchedEffect(filter, query) { selectMode = false }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    // A check the person asked for always says what it found; the launch check stays silent.
    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        updateScope.launch {
            try {
                if (manual) {
                    when (val result = UpdateCheck.manualCheck(context)) {
                        is UpdateCheck.ManualResult.Available -> updateOffer = result.update
                        UpdateCheck.ManualResult.UpToDate -> toast("You're up to date")
                        UpdateCheck.ManualResult.Failed ->
                            toast("Couldn't check for updates. Check your connection and try again.")
                    }
                } else {
                    UpdateCheck.autoCheck(context)?.let { updateOffer = it }
                }
            } finally {
                checkingUpdate = false
            }
        }
    }

    LaunchedEffect(Unit) { vm.refreshLastBackup() }
    LaunchedEffect(Unit) { checkForUpdates(manual = false) }
    LaunchedEffect(Unit) { whatsNew = WhatsNew.pendingNotes(context) }

    // Undo stays on offer for a few seconds.
    val undoId = undo?.id
    LaunchedEffect(undoId) {
        if (undoId != null) {
            delay(6000)
            vm.dismissUndo(undoId)
        }
    }

    val exportPlain = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> if (uri != null) vm.exportTo(uri, null) { toast(it) } }

    val exportEncrypted = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> if (uri != null) vm.exportTo(uri, exportPassword) { toast(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.importFrom(uri, onNeedPassword = { showPasswordDialog = true }, onDone = { toast(it) })
    }

    val keepLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importKeep(uri) { toast(it) } }

    val vaultExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.exportVault(uri) { toast(it) } }

    val vaultImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) vm.importVault(uri) { toast(it) } }

    val enexLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importEvernote(uri) { toast(it) } }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                vm.setAutoBackupFolder(uri)
                vm.backupNow { toast("Automatic backup is on. $it") }
            } catch (e: Throwable) {
                toast("That folder can't be used: ${e.message}")
            }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) vm.createAndOpen(isChecklist = false, body = spoken)
    }

    fun startSpeech() {
        try {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            )
        } catch (e: ActivityNotFoundException) {
            toast("No voice typing app was found on this phone")
        }
    }

    fun startBackup(password: String?) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (password == null) {
            exportPlain.launch("notes-backup-$day.zip")
        } else {
            exportPassword = password
            exportEncrypted.launch("notes-backup-$day.notesbackup")
        }
    }

    fun toggleLock() {
        if (lockOn) {
            vm.setLock(false)
            toast("App lock is off")
        } else {
            val ready = BiometricManager.from(context).canAuthenticate(MainActivity.LOCK_AUTHENTICATORS) ==
                BiometricManager.BIOMETRIC_SUCCESS
            if (ready) {
                vm.setLock(true)
                toast("App lock is on")
            } else {
                toast("Set up a screen lock or fingerprint on the phone first")
            }
        }
    }

    fun stopSearch() {
        searching = false
        vm.query.value = ""
    }

    val selectedNotes = notes.filter { it.note.id in selection }.map { it.note }
    val selecting = selectedNotes.isNotEmpty()

    BackHandler(enabled = selecting || searching || query.isNotEmpty() || filter != Filter.Notes) {
        if (selecting) {
            selection = emptySet()
        } else {
            stopSearch()
            vm.filter.value = Filter.Notes
        }
    }

    val inTrash = filter == Filter.Trash && query.isEmpty()
    val showCaptureBar = !searching && !selecting && (filter == Filter.Notes || filter is Filter.ByLabel)

    fun toggle(id: Long) {
        selection = if (id in selection) selection - id else selection + id
    }

    CompositionLocalProvider(LocalContentColor provides p.ink) {
        Box(modifier.fillMaxHeight().background(p.ground)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            ) {
                if (selecting) {
                    SelectionBar(
                        count = selectedNotes.size,
                        inTrash = selectedNotes.all { it.trashedAt != null },
                        allPinned = selectedNotes.all { it.pinned },
                        allArchived = selectedNotes.all { it.archived },
                        onClose = { selection = emptySet() },
                        onPin = { vm.setPinned(selectedNotes, !selectedNotes.all { it.pinned }); selection = emptySet() },
                        onArchive = {
                            vm.setArchived(selectedNotes, !selectedNotes.all { it.archived })
                            selection = emptySet()
                        },
                        onCopy = { vm.duplicate(selectedNotes); selection = emptySet() },
                        onTrash = { vm.trash(selectedNotes); selection = emptySet() },
                        onRestore = { vm.restore(selectedNotes); selection = emptySet() },
                        onDeleteForever = { vm.deleteForever(selectedNotes); selection = emptySet() },
                    )
                } else if (searching) {
                    SearchField(query = query, onQueryChange = { vm.query.value = it }, onClose = { stopSearch() })
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LauncherIcon(
                            cornerRadius = 10.dp,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp)),
                        )
                        BackupStatus(lastBackup = lastBackup, onClick = { showBackupDialog = true })
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreHoriz, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                MenuGroupHeader(
                                    text = "Sort by",
                                    icon = Icons.Outlined.SwapVert,
                                    expanded = expandedMenuGroup == "sort",
                                    onClick = { expandedMenuGroup = if (expandedMenuGroup == "sort") null else "sort" },
                                )
                                if (expandedMenuGroup == "sort") {
                                    Sort.entries.forEach { option ->
                                        DropdownMenuItem(
                                            text = { Text(option.label) },
                                            leadingIcon = { Icon(Icons.Outlined.SwapVert, contentDescription = null) },
                                            trailingIcon = {
                                                if (sort == option) Icon(Icons.Outlined.Check, contentDescription = "Selected")
                                            },
                                            onClick = { menuOpen = false; vm.setSort(option) },
                                        )
                                    }
                                }
                                HorizontalDivider()
                                MenuGroupHeader(
                                    text = "View",
                                    icon = if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                                    expanded = expandedMenuGroup == "view",
                                    onClick = { expandedMenuGroup = if (expandedMenuGroup == "view") null else "view" },
                                )
                                if (expandedMenuGroup == "view") {
                                    MenuRow(
                                        if (grid) "Show as list" else "Show as grid",
                                        if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                                    ) { menuOpen = false; vm.setGridView(!grid) }
                                    if (notes.isNotEmpty()) {
                                        MenuRow("Select notes", Icons.Outlined.Check) {
                                            menuOpen = false
                                            arrangeList = null
                                            selectMode = true
                                        }
                                    }
                                    MenuRow("Reminders", Icons.Outlined.Alarm) {
                                        menuOpen = false; showReminders = true
                                    }
                                    MenuRow("Calendar", Icons.Outlined.CalendarMonth) {
                                        menuOpen = false; showCalendar = true
                                    }
                                    MenuRow("Today's note", Icons.Outlined.Today) {
                                        menuOpen = false; vm.openDailyNote()
                                    }
                                }
                                HorizontalDivider()
                                MenuGroupHeader(
                                    text = "Organize",
                                    icon = Icons.Outlined.Edit,
                                    expanded = expandedMenuGroup == "organize",
                                    onClick = { expandedMenuGroup = if (expandedMenuGroup == "organize") null else "organize" },
                                )
                                if (expandedMenuGroup == "organize") {
                                    MenuRow("Edit labels", Icons.Outlined.Edit) { menuOpen = false; showManageLabels = true }
                                    MenuRow("Templates", Icons.Outlined.Description) { menuOpen = false; showTemplates = true }
                                    MenuRow("Theme", Icons.Outlined.Palette) { menuOpen = false; showThemeDialog = true }
                                }
                                HorizontalDivider()
                                MenuGroupHeader(
                                    text = "Backup & import",
                                    icon = Icons.Outlined.CloudUpload,
                                    expanded = expandedMenuGroup == "backup",
                                    onClick = { expandedMenuGroup = if (expandedMenuGroup == "backup") null else "backup" },
                                )
                                if (expandedMenuGroup == "backup") {
                                    MenuRow("Back up to a file", Icons.Outlined.CloudUpload) {
                                        menuOpen = false; showBackupDialog = true
                                    }
                                    MenuRow("Restore from a file", Icons.Outlined.CloudDownload) {
                                        menuOpen = false; importLauncher.launch(arrayOf("*/*"))
                                    }
                                    MenuRow(
                                        if (autoFolder == null) "Automatic backup: off" else "Automatic backup: on",
                                        Icons.Outlined.Schedule,
                                    ) {
                                        menuOpen = false
                                        if (autoFolder == null) folderLauncher.launch(null) else showAutoBackupDialog = true
                                    }
                                    MenuRow("Import from Google Keep", Icons.Outlined.FileDownload) {
                                        menuOpen = false
                                        toast("Pick the Google Takeout zip that contains your Keep notes")
                                        keepLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                                    }
                                    MenuRow("Export as Markdown vault", Icons.Outlined.Description) {
                                        menuOpen = false
                                        toast("Pick a folder for one .md file per note")
                                        vaultExportLauncher.launch(null)
                                    }
                                    MenuRow("Import Markdown or Obsidian vault", Icons.Outlined.FileDownload) {
                                        menuOpen = false
                                        toast("Pick the folder with the .md files")
                                        vaultImportLauncher.launch(null)
                                    }
                                    MenuRow("Import from Evernote (.enex)", Icons.Outlined.NoteAdd) {
                                        menuOpen = false
                                        toast("Pick the .enex file exported from Evernote")
                                        enexLauncher.launch(arrayOf("application/xml", "text/xml", "*/*"))
                                    }
                                }
                                HorizontalDivider()
                                MenuRow(if (lockOn) "App lock: on" else "App lock: off", Icons.Outlined.Lock) {
                                    menuOpen = false; toggleLock()
                                }
                                MenuRow("Trash", Icons.Outlined.Delete) { menuOpen = false; vm.filter.value = Filter.Trash }
                                MenuRow("Version " + appVersion(), Icons.Outlined.Info) { menuOpen = false; checkForUpdates(manual = true) }
                            }
                        }
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
                ) {
                    item(key = "all") {
                        FilterChipPill("All", filter == Filter.Notes) { vm.filter.value = Filter.Notes }
                    }
                    items(labels, key = { it.id }) { label ->
                        FilterChipPill(label.name, filter == Filter.ByLabel(label.id)) {
                            vm.filter.value = Filter.ByLabel(label.id)
                        }
                    }
                    item(key = "archive") {
                        FilterChipPill("Archive", filter == Filter.Archive) { vm.filter.value = Filter.Archive }
                    }
                }

                val shownNotes = arrangeList ?: notes
                val arranging = arrangeList != null
                // Holding a note starts arranging, except in the Trash, in search, or while notes are selected.
                val canReorder = arranging || (!inTrash && query.isEmpty() && !selecting && !selectMode)
                // Swiping archives or trashes a note, except in the Trash, while arranging, or while notes are selected.
                val swipeEnabled = !arranging && !selecting && !selectMode && !inTrash
                val pinned = if (inTrash) emptyList() else shownNotes.filter { it.note.pinned }
                val others = if (inTrash) shownNotes else shownNotes.filter { !it.note.pinned }

                val onCardClick: (NoteItem) -> Unit = { item ->
                    if (selectMode || selecting || item.note.trashedAt != null) toggle(item.note.id) else onOpen(item.note.id)
                }

                if (arranging || (selectMode && !selecting)) {
                    Surface(
                        color = p.accent.copy(alpha = 0.14f),
                        contentColor = p.ink,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (arranging) Icons.Outlined.DragIndicator else Icons.Outlined.Check,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                if (arranging) "Drag a note where you want it. Your order is saved." else "Tap the notes you want to select.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { if (arranging) arrangeList = null else selectMode = false }) {
                                Text(if (arranging) "Done" else "Cancel")
                            }
                        }
                    }
                }

                if (notes.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = 96.dp), contentAlignment = Alignment.Center) {
                        Text(
                            when {
                                query.isNotEmpty() -> "No matching notes"
                                filter == Filter.Notes -> "Your notes will appear here"
                                filter == Filter.Archive -> "Archived notes appear here"
                                filter == Filter.Trash -> "Trash is empty"
                                else -> "No notes with this label yet"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = p.muted,
                        )
                    }
                } else {
                    val reorderState = rememberReorderableLazyStaggeredGridState(gridState) { from, to ->
                        val list = arrangeList
                        if (list != null) {
                            val a = list.indexOfFirst { it.note.id == from.key }
                            val b = list.indexOfFirst { it.note.id == to.key }
                            if (a >= 0 && b >= 0 && list[a].note.pinned == list[b].note.pinned) {
                                arrangeList = list.toMutableList().apply { add(b, removeAt(a)) }
                            }
                        }
                    }
                    val haptic = LocalHapticFeedback.current
                    LazyVerticalStaggeredGrid(
                        columns = if (grid) StaggeredGridCells.Adaptive(160.dp) else StaggeredGridCells.Fixed(1),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 160.dp),
                        verticalItemSpacing = 10.dp,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (inTrash) {
                            item(key = "trash-header", span = StaggeredGridItemSpan.FullLine) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "Notes in Trash are deleted after 30 days. Tap a note to select it.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = p.muted,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { confirmEmptyTrash = true }) { Text("Empty Trash") }
                                }
                            }
                        }
                        if (pinned.isNotEmpty()) {
                            item(key = "header-pinned", span = StaggeredGridItemSpan.FullLine) {
                                SectionHeader("Pinned", showPin = true)
                            }
                            gridItems(pinned, key = { it.note.id }) { item ->
                                SwipeableNoteCard(
                                    enabled = swipeEnabled,
                                    archived = item.note.archived,
                                    onSwipeRight = { vm.setArchived(listOf(item.note), !item.note.archived) },
                                    onSwipeLeft = { vm.trash(listOf(item.note)) },
                                ) {
                                    ReorderableItem(reorderState, key = item.note.id, enabled = canReorder) { isDragging ->
                                        NoteCard(
                                            item = item,
                                            open = item.note.id == selectedId,
                                            checked = item.note.id in selection,
                                            onClick = { onCardClick(item) },
                                            onLongClick = {
                                                if (canReorder) {
                                                    if (arrangeList == null) {
                                                        arrangeList = notes
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    }
                                                } else toggle(item.note.id)
                                            },
                                            jiggle = arranging,
                                            arrange = arranging,
                                            dragging = isDragging,
                                            handle = Modifier.longPressDraggableHandle(
                                                enabled = canReorder,
                                                onDragStarted = {
                                                    if (arrangeList == null) arrangeList = notes
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragStopped = {
                                                    val l = arrangeList
                                                    if (l != null && l.map { it.note.id } != notes.map { it.note.id }) vm.saveOrder(l.map { it.note.id })
                                                },
                                            ),
                                        )
                                    }
                                }
                            }
                            if (others.isNotEmpty()) {
                                item(key = "header-others", span = StaggeredGridItemSpan.FullLine) {
                                    SectionHeader("Others", showPin = false)
                                }
                            }
                        }
                        gridItems(others, key = { it.note.id }) { item ->
                            SwipeableNoteCard(
                                enabled = swipeEnabled,
                                archived = item.note.archived,
                                onSwipeRight = { vm.setArchived(listOf(item.note), !item.note.archived) },
                                onSwipeLeft = { vm.trash(listOf(item.note)) },
                            ) {
                                ReorderableItem(reorderState, key = item.note.id, enabled = canReorder) { isDragging ->
                                    NoteCard(
                                        item = item,
                                        open = item.note.id == selectedId,
                                        checked = item.note.id in selection,
                                        onClick = { onCardClick(item) },
                                        onLongClick = {
                                            if (canReorder) {
                                                if (arrangeList == null) {
                                                    arrangeList = notes
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            } else toggle(item.note.id)
                                        },
                                        jiggle = arranging,
                                        arrange = arranging,
                                        dragging = isDragging,
                                        handle = Modifier.longPressDraggableHandle(
                                            enabled = canReorder,
                                            onDragStarted = {
                                                if (arrangeList == null) arrangeList = notes
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            },
                                            onDragStopped = {
                                                val l = arrangeList
                                                if (l != null && l.map { it.note.id } != notes.map { it.note.id }) vm.saveOrder(l.map { it.note.id })
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val pending = undo
                if (pending != null) {
                    UndoBar(message = pending.message, onUndo = { vm.performUndo() })
                }
                if (showCaptureBar) {
                    CaptureBar(
                        onNote = { vm.createAndOpen(isChecklist = false) },
                        onChecklist = { vm.createAndOpen(isChecklist = true) },
                        onSpeak = { startSpeech() },
                    )
                }
            }
        }
    }

    if (showManageLabels) {
        ManageLabelsDialog(vm = vm, labels = labels, onDismiss = { showManageLabels = false })
    }
    if (showReminders) {
        RemindersListDialog(
            notes = reminders,
            onOpen = { id -> showReminders = false; onOpen(id) },
            onClear = { id -> vm.clearReminder(id) },
            onDismiss = { showReminders = false },
        )
    }
    if (showTemplates) {
        TemplatesDialog(
            templates = templates,
            onApply = { template -> showTemplates = false; vm.applyTemplate(template) },
            onDelete = { template -> vm.deleteTemplate(template) },
            onDismiss = { showTemplates = false },
        )
    }
    if (showCalendar) {
        CalendarDialog(
            notes = allNotes,
            onOpen = { id -> showCalendar = false; onOpen(id) },
            onDismiss = { showCalendar = false },
        )
    }
    if (showBackupDialog) {
        BackupDialog(
            onDismiss = { showBackupDialog = false },
            onConfirm = { password ->
                showBackupDialog = false
                startBackup(password)
            },
        )
    }
    if (showPasswordDialog) {
        PasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onConfirm = { password ->
                showPasswordDialog = false
                vm.finishImport(password) { toast(it) }
            },
        )
    }
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Theme") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Themes.all.forEach { spec ->
                        val sw = spec.palette ?: Themes.classicPreview
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.setTheme(spec.id); showThemeDialog = false }
                                .heightIn(min = 52.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                                listOf(sw.ground, sw.surface, sw.accent).forEach { color ->
                                    Box(
                                        Modifier
                                            .size(26.dp)
                                            .background(color, CircleShape)
                                            .border(1.dp, Color(0x44808080), CircleShape),
                                    )
                                }
                            }
                            Text(spec.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            if (spec.id == themeId) Text("\u2713", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showThemeDialog = false }) { Text("Close") } },
        )
    }
    if (showAutoBackupDialog) {
        AlertDialog(
            onDismissRequest = { showAutoBackupDialog = false },
            title = { Text("Automatic backup") },
            text = {
                Text(
                    "Once a day, a full backup is written to the folder you picked, " +
                        "replacing the previous one. It is not password protected."
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        showAutoBackupDialog = false
                        vm.backupNow { toast(it) }
                    }) { Text("Back up now") }
                    TextButton(onClick = {
                        showAutoBackupDialog = false
                        folderLauncher.launch(null)
                    }) { Text("Change folder") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutoBackupDialog = false
                    vm.setAutoBackupFolder(null)
                    toast("Automatic backup is off")
                }) { Text("Turn off") }
            },
        )
    }
    if (confirmEmptyTrash) {
        AlertDialog(
            onDismissRequest = { confirmEmptyTrash = false },
            title = { Text("Empty Trash?") },
            text = { Text("All notes in Trash will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.emptyTrash()
                    confirmEmptyTrash = false
                }) { Text("Empty Trash") }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("Cancel") } },
        )
    }
    updateOffer?.let { update ->
        UpdateAvailableDialog(
            version = update.version,
            downloading = updateDownloading,
            onDownload = {
                if (!updateDownloading) {
                    updateDownloading = true
                    updateScope.launch {
                        val file = UpdateCheck.download(context, update)
                        updateDownloading = false
                        if (file != null) {
                            updateOffer = null
                            if (!UpdateCheck.install(context, file)) {
                                toast("Couldn't open the installer.")
                            }
                        } else {
                            toast("Download failed. Try again later.")
                        }
                    }
                }
            },
            onDismiss = {
                UpdateCheck.dismissVersion(context, update.version)
                updateOffer = null
            },
        )
    }
    // The update offer wins: the two dialogs never stack. The card waits until the offer is gone.
    if (updateOffer == null) {
        whatsNew?.let { (version, notes) ->
            WhatsNewDialog(
                version = version,
                notes = notes,
                onDismiss = {
                    WhatsNew.markSeen(context)
                    whatsNew = null
                },
            )
        }
    }
}

@Composable
private fun MenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun MenuGroupHeader(
    text: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text, fontWeight = FontWeight.SemiBold) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        },
        onClick = onClick,
    )
}

@Composable
private fun SelectionBar(
    count: Int,
    inTrash: Boolean,
    allPinned: Boolean,
    allArchived: Boolean,
    onClose: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onCopy: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp).heightIn(min = 104.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Clear selection") }
        Text(
            "$count selected",
            fontFamily = Display,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        if (inTrash) {
            IconButton(onClick = onRestore) {
                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = "Restore")
            }
            IconButton(onClick = onDeleteForever) {
                Icon(Icons.Outlined.DeleteForever, contentDescription = "Delete forever")
            }
        } else {
            IconButton(onClick = onPin) {
                Icon(Icons.Outlined.PushPin, contentDescription = if (allPinned) "Unpin" else "Pin")
            }
            IconButton(onClick = onArchive) {
                Icon(
                    if (allArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                    contentDescription = if (allArchived) "Unarchive" else "Archive",
                )
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Make a copy")
            }
            IconButton(onClick = onTrash) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun UndoBar(message: String, onUndo: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        shape = CircleShape,
        color = p.bar,
        contentColor = p.onBar,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(Modifier.padding(start = 24.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = onUndo) {
                Text("Undo", color = Color(0xFF9DB0FF), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun BackupStatus(lastBackup: Long, onClick: () -> Unit) {
    val p = LocalPalette.current
    val days = ((System.currentTimeMillis() - lastBackup) / 86_400_000L).toInt()
    val stale = lastBackup != 0L && days >= 3
    val text = when {
        lastBackup == 0L -> "Not backed up yet"
        stale -> "Last backup was $days days ago"
        DateUtils.isToday(lastBackup) -> "Backed up today"
        else -> "Backed up " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastBackup))
    }
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (lastBackup == 0L) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone,
            contentDescription = null,
            tint = if (stale) androidx.compose.ui.graphics.Color(0xFFB4380F) else p.muted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (stale) androidx.compose.ui.graphics.Color(0xFFB4380F) else p.muted,
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Surface(
        shape = CircleShape,
        color = p.surface,
        contentColor = p.ink,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).height(56.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(18.dp))
            Icon(Icons.Outlined.Search, contentDescription = null, tint = p.muted)
            Spacer(Modifier.width(12.dp))
            PlainTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "Search your notes",
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close search")
            }
        }
    }
}

@Composable
private fun FilterChipPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) p.ink else p.surface,
        contentColor = if (selected) p.ground else p.ink,
        modifier = Modifier.height(44.dp),
    ) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String, showPin: Boolean) {
    val p = LocalPalette.current
    Row(
        Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showPin) {
            Icon(Icons.Outlined.PushPin, contentDescription = null, tint = p.muted, modifier = Modifier.size(14.dp))
        }
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = p.muted,
        )
    }
}

/** The one place to start writing: sits at the bottom, within thumb reach. */
@Composable
private fun CaptureBar(onNote: () -> Unit, onChecklist: () -> Unit, onSpeak: () -> Unit) {
    val p = LocalPalette.current
    Surface(
        shape = CircleShape,
        color = p.bar,
        contentColor = p.onBar,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth().height(68.dp),
    ) {
        Row(
            Modifier.padding(start = 24.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onNote),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    "Take a note…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.onBarMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSpeak) {
                Icon(Icons.Outlined.Mic, contentDescription = "Speak a note")
            }
            IconButton(onClick = onChecklist) {
                Icon(Icons.Outlined.CheckBox, contentDescription = "New checklist")
            }
            Surface(
                onClick = onNote,
                shape = CircleShape,
                color = p.accent,
                contentColor = p.onAccent,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = "New note")
                }
            }
        }
    }
}

@Composable
private fun rememberWiggle(active: Boolean, seed: Long): State<Float> =
    if (active) {
        rememberInfiniteTransition(label = "wiggle").animateFloat(
            initialValue = -1.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 170 + (seed % 5).toInt() * 20, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "angle",
        )
    } else {
        remember { mutableStateOf(0f) }
    }

/**
 * A note card that swipes sideways: right archives (or unarchives an archived
 * note), left moves it to Trash. The actions run through the same ViewModel
 * paths as the selection bar, so Undo works the same way. The card itself is
 * untouched: taps, long-press, and the drag handle all behave as before.
 */
@Composable
private fun SwipeableNoteCard(
    enabled: Boolean,
    archived: Boolean,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    card: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onSwipeRight(); false }
                SwipeToDismissBoxValue.EndToStart -> { onSwipeLeft(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackground(direction = state.dismissDirection, archived = archived) },
        modifier = Modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        content = { card() },
    )
}

/**
 * The icon and label revealed behind a card while it is swiped. It carries no
 * semantics, so the tests still see exactly one "Archive" and one "Trash".
 */
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue, archived: Boolean) {
    if (direction == SwipeToDismissBoxValue.Settled) return
    val p = LocalPalette.current
    val toEnd = direction == SwipeToDismissBoxValue.StartToEnd
    val background = if (toEnd) p.accent else MaterialTheme.colorScheme.error
    val onBackground = if (toEnd) p.onAccent else MaterialTheme.colorScheme.onError
    val icon = if (toEnd) {
        if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive
    } else {
        Icons.Outlined.Delete
    }
    val label = if (toEnd) {
        if (archived) "Unarchive" else "Archive"
    } else {
        "Trash"
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(p.cardRadius))
            .background(background)
            .clearAndSetSemantics {},
        contentAlignment = if (toEnd) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = onBackground)
            Text(
                label,
                color = onBackground,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun NoteCard(
    item: NoteItem,
    open: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    jiggle: Boolean = false,
    arrange: Boolean = false,
    dragging: Boolean = false,
    handle: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val note = item.note
    val background = noteBackground(note.color)
    // The first URL's preview, fetched once per body. Locked notes fetch nothing.
    val firstUrl = remember(note.body, note.locked) {
        if (note.locked) null else LinkPreviews.firstUrl(note.body)
    }
    val preview by produceState<LinkPreview?>(initialValue = null, firstUrl) {
        value = firstUrl?.let { LinkPreviews.fetch(it) }
    }
    val shape = RoundedCornerShape(p.cardRadius)
    // While arranging, every note jiggles like icons on a home screen, except the one being dragged.
    val wiggle = rememberWiggle(active = jiggle && !dragging, seed = note.id)
    val border = when {
        dragging -> BorderStroke(3.dp, p.accent)
        checked -> BorderStroke(3.dp, p.ink)
        open -> BorderStroke(2.dp, p.accent)
        p.border > 0.dp -> BorderStroke(p.border, p.borderColor)
        else -> null
    }

    Surface(
        shape = shape,
        color = background ?: p.surface,
        contentColor = p.ink,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationZ = wiggle.value
                if (dragging) {
                    scaleX = 1.05f
                    scaleY = 1.05f
                    alpha = 0.92f
                }
            }
            .clip(shape)
            .then(if (arrange) handle else Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)),
    ) {
        Column {
            // A locked note shows no picture and no preview: title and lock only.
            if (!note.locked) {
                item.images.firstOrNull()?.let { name ->
                    NoteImage(name = name, maxPx = 480, modifier = Modifier.fillMaxWidth().height(120.dp))
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val soft = p.ink.copy(alpha = 0.78f)
                if (note.locked) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Lock, contentDescription = "Locked note", modifier = Modifier.size(18.dp))
                        Text(
                            note.title.ifBlank { "Locked note" },
                            fontFamily = Display,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else if (note.isChecklist) {
                    val all = remember(note.body) { parseItems(note.body).filter { it.text.isNotBlank() } }
                    val openItems = all.filter { !it.checked }
                    val done = all.size - openItems.size
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            note.title.ifBlank { "Checklist" },
                            fontFamily = Display,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (all.isNotEmpty()) {
                            Text(
                                "$done of ${all.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = soft,
                            )
                        }
                    }
                    if (all.isNotEmpty()) {
                        ProgressLine(done = done, total = all.size)
                    }
                    openItems.take(4).forEach { checkItem ->
                        Row(
                            Modifier.padding(start = if (checkItem.indent > 0) 16.dp else 0.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(Modifier.size(16.dp).border(2.dp, p.ink, CircleShape))
                            Text(
                                checkItem.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (openItems.size > 4) {
                        Text("+${openItems.size - 4} more", style = MaterialTheme.typography.bodySmall, color = soft)
                    }
                } else {
                    if (note.title.isNotBlank()) {
                        Text(
                            note.title,
                            fontFamily = Display,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            lineHeight = 22.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (note.body.isNotBlank()) {
                        Text(
                            remember(note.body) { displayMarkup(note.body.take(600)) },
                            style = MaterialTheme.typography.bodyMedium,
                            color = soft,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                preview?.let { LinkPreviewRow(it) }
                val reminder = note.reminderAt
                if (reminder != null && !note.locked) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.Alarm, contentDescription = "Reminder", modifier = Modifier.size(14.dp))
                        Text(formatReminder(reminder), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (!note.locked && item.labels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.labels.forEach { LabelChip(it.name) }
                    }
                }
            }
        }
    }
}

fun formatReminder(at: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

/** The fetched title and image for the first URL in a note, shown under the card text. */
@Composable
private fun LinkPreviewRow(preview: LinkPreview) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalContentColor.current.copy(alpha = 0.06f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview.image?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
        Text(
            preview.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A thin bar showing how much of a checklist is done. */
@Composable
fun ProgressLine(done: Int, total: Int, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val fraction = if (total <= 0) 0f else done.toFloat() / total
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(ink.copy(alpha = 0.12f))
    ) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(CircleShape).background(ink))
    }
}

@Composable
fun LabelChip(name: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = LocalContentColor.current.copy(alpha = 0.08f),
        contentColor = LocalContentColor.current,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun appVersion(): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }
}




