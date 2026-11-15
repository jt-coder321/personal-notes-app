package com.jt.notes.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.Filter
import com.jt.notes.NoteItem
import com.jt.notes.NotesViewModel
import com.jt.notes.data.parseItems
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
    onCreate: (isChecklist: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    val notes by vm.items.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var searching by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showManageLabels by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportTo(uri) { toast(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importFrom(uri) { toast(it) } }

    fun startBackup() {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        exportLauncher.launch("notes-backup-$day.json")
    }

    fun stopSearch() {
        searching = false
        vm.query.value = ""
    }

    BackHandler(enabled = searching || query.isNotEmpty() || filter != Filter.Notes) {
        stopSearch()
        vm.filter.value = Filter.Notes
    }

    val inTrash = filter == Filter.Trash && query.isEmpty()
    val showCaptureBar = !searching && (filter == Filter.Notes || filter is Filter.ByLabel)

    CompositionLocalProvider(LocalContentColor provides p.ink) {
        Box(modifier.fillMaxHeight().background(p.ground)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            ) {
                if (searching) {
                    SearchField(query = query, onQueryChange = { vm.query.value = it }, onClose = { stopSearch() })
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackupStatus(lastBackup = lastBackup, onClick = { startBackup() })
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreHoriz, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (grid) "Show as list" else "Show as grid") },
                                    leadingIcon = {
                                        Icon(
                                            if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = { menuOpen = false; vm.setGridView(!grid) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit labels") },
                                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                    onClick = { menuOpen = false; showManageLabels = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Back up to a file") },
                                    leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) },
                                    onClick = { menuOpen = false; startBackup() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore from a file") },
                                    leadingIcon = { Icon(Icons.Outlined.CloudDownload, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        importLauncher.launch(
                                            arrayOf("application/json", "application/octet-stream", "text/plain")
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Trash") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                                    onClick = { menuOpen = false; vm.filter.value = Filter.Trash },
                                )
                            }
                        }
                    }
                    Text(
                        when (filter) {
                            Filter.Archive -> "Archive"
                            Filter.Trash -> "Trash"
                            else -> "Notes"
                        },
                        fontFamily = Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 46.sp,
                        lineHeight = 50.sp,
                        letterSpacing = (-0.9).sp,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp),
                    )
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 16.dp),
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

                val pinned = if (inTrash) emptyList() else notes.filter { it.note.pinned }
                val others = if (inTrash) notes else notes.filter { !it.note.pinned }

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
                    LazyVerticalStaggeredGrid(
                        columns = if (grid) StaggeredGridCells.Adaptive(160.dp) else StaggeredGridCells.Fixed(1),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 140.dp),
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
                                        "Notes in Trash are deleted after 30 days",
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
                                NoteCard(item, vm, item.note.id == selectedId, onOpen)
                            }
                            if (others.isNotEmpty()) {
                                item(key = "header-others", span = StaggeredGridItemSpan.FullLine) {
                                    SectionHeader("Recent", showPin = false)
                                }
                            }
                        }
                        gridItems(others, key = { it.note.id }) { item ->
                            NoteCard(item, vm, item.note.id == selectedId, onOpen)
                        }
                    }
                }
            }

            if (showCaptureBar) {
                CaptureBar(
                    onNote = { onCreate(false) },
                    onChecklist = { onCreate(true) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }

    if (showManageLabels) {
        ManageLabelsDialog(vm = vm, labels = labels, onDismiss = { showManageLabels = false })
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
}

@Composable
private fun BackupStatus(lastBackup: Long, onClick: () -> Unit) {
    val p = LocalPalette.current
    val text = when {
        lastBackup == 0L -> "Not backed up yet"
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
            tint = p.muted,
            modifier = Modifier.size(18.dp),
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.muted)
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
private fun CaptureBar(onNote: () -> Unit, onChecklist: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Surface(
        shape = CircleShape,
        color = p.bar,
        contentColor = p.onBar,
        shadowElevation = 12.dp,
        modifier = modifier.fillMaxWidth().height(68.dp),
    ) {
        Row(
            Modifier.padding(start = 24.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable(onClick = onNote),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Take a note…", style = MaterialTheme.typography.bodyLarge, color = p.onBarMuted)
            }
            Surface(
                onClick = onChecklist,
                shape = CircleShape,
                color = p.onBar.copy(alpha = 0.1f),
                contentColor = p.onBar,
                modifier = Modifier.size(48.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.CheckBox, contentDescription = "New checklist")
                }
            }
            Surface(
                onClick = onNote,
                shape = CircleShape,
                color = p.accent,
                contentColor = Color.White,
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
private fun NoteCard(item: NoteItem, vm: NotesViewModel, selected: Boolean, onOpen: (Long) -> Unit) {
    val p = LocalPalette.current
    val note = item.note
    val inTrash = note.trashedAt != null
    var menuOpen by remember { mutableStateOf(false) }
    val background = noteBackground(note.color)
    val shape = RoundedCornerShape(24.dp)

    Box {
        Surface(
            shape = shape,
            color = background ?: p.surface,
            contentColor = p.ink,
            border = if (selected) BorderStroke(2.dp, p.accent) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = { if (inTrash) menuOpen = true else onOpen(note.id) },
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val soft = p.ink.copy(alpha = 0.78f)
                if (note.isChecklist) {
                    val all = parseItems(note.body).filter { it.text.isNotBlank() }
                    val open = all.filter { !it.checked }
                    val done = all.size - open.size
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
                    open.take(4).forEach { checkItem ->
                        Row(
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
                    if (open.size > 4) {
                        Text("+${open.size - 4} more", style = MaterialTheme.typography.bodySmall, color = soft)
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
                            note.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = soft,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (item.labels.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        item.labels.forEach { LabelChip(it.name) }
                    }
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (inTrash) {
                DropdownMenuItem(
                    text = { Text("Restore") },
                    leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null) },
                    onClick = { menuOpen = false; vm.restore(note) },
                )
                DropdownMenuItem(
                    text = { Text("Delete forever") },
                    leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                    onClick = { menuOpen = false; vm.deleteForever(note) },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(if (note.pinned) "Unpin" else "Pin") },
                    leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                    onClick = { menuOpen = false; vm.update(note.copy(pinned = !note.pinned)) },
                )
                DropdownMenuItem(
                    text = { Text(if (note.archived) "Unarchive" else "Archive") },
                    leadingIcon = {
                        Icon(
                            if (note.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        vm.update(note.copy(archived = !note.archived, pinned = false))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; vm.trash(note) },
                )
            }
        }
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
