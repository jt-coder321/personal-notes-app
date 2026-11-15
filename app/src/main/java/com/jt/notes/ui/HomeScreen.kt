package com.jt.notes.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.Filter
import com.jt.notes.NoteItem
import com.jt.notes.NotesViewModel
import com.jt.notes.data.parseItems
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    vm: NotesViewModel,
    gridState: LazyStaggeredGridState,
    onOpen: (Long) -> Unit,
    onCreate: (isChecklist: Boolean) -> Unit,
) {
    val notes by vm.items.collectAsStateWithLifecycle()
    val labels by vm.labels.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val grid by vm.gridView.collectAsStateWithLifecycle()
    val lastBackup by vm.lastBackup.collectAsStateWithLifecycle()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showManageLabels by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportTo(uri) { toast(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importFrom(uri) { toast(it) } }

    fun go(target: Filter) {
        vm.filter.value = target
        vm.query.value = ""
        scope.launch { drawerState.close() }
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = drawerState.isClosed && (query.isNotEmpty() || filter != Filter.Notes)) {
        vm.query.value = ""
        vm.filter.value = Filter.Notes
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Notes",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                    )
                    DrawerItem("Notes", Icons.Outlined.Lightbulb, filter == Filter.Notes) { go(Filter.Notes) }
                    labels.forEach { label ->
                        DrawerItem(
                            label.name,
                            Icons.AutoMirrored.Outlined.Label,
                            filter == Filter.ByLabel(label.id),
                        ) { go(Filter.ByLabel(label.id)) }
                    }
                    DrawerItem("Edit labels", Icons.Outlined.Edit, false) {
                        showManageLabels = true
                        scope.launch { drawerState.close() }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DrawerItem("Archive", Icons.Outlined.Archive, filter == Filter.Archive) { go(Filter.Archive) }
                    DrawerItem("Trash", Icons.Outlined.Delete, filter == Filter.Trash) { go(Filter.Trash) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DrawerItem("Back up to a file", Icons.Outlined.CloudUpload, false) {
                        scope.launch { drawerState.close() }
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        exportLauncher.launch("notes-backup-$day.json")
                    }
                    DrawerItem("Restore from a file", Icons.Outlined.CloudDownload, false) {
                        scope.launch { drawerState.close() }
                        importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
                    }
                    Text(
                        if (lastBackup == 0L) "No backup yet"
                        else "Last backup: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(lastBackup)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                NotesSearchBar(
                    query = query,
                    onQueryChange = { vm.query.value = it },
                    placeholder = when (val f = filter) {
                        Filter.Notes -> "Search your notes"
                        Filter.Archive -> "Archive"
                        Filter.Trash -> "Trash"
                        is Filter.ByLabel -> labels.firstOrNull { it.id == f.labelId }?.name ?: "Label"
                    },
                    grid = grid,
                    onToggleGrid = { vm.setGridView(!grid) },
                    onMenu = { scope.launch { drawerState.open() } },
                )
            },
            floatingActionButton = {
                if (filter == Filter.Notes || filter is Filter.ByLabel) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SmallFloatingActionButton(onClick = { onCreate(true) }) {
                            Icon(Icons.Outlined.CheckBox, contentDescription = "New checklist")
                        }
                        FloatingActionButton(onClick = { onCreate(false) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "New note")
                        }
                    }
                }
            },
        ) { padding ->
            val inTrash = filter == Filter.Trash && query.isEmpty()
            val pinned = if (inTrash) emptyList() else notes.filter { it.note.pinned }
            val others = if (inTrash) notes else notes.filter { !it.note.pinned }

            if (notes.isEmpty()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            query.isNotEmpty() -> "No matching notes"
                            filter == Filter.Notes -> "Notes you add appear here"
                            filter == Filter.Archive -> "Your archived notes appear here"
                            filter == Filter.Trash -> "No notes in Trash"
                            else -> "No notes with this label yet"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = if (grid) StaggeredGridCells.Adaptive(160.dp) else StaggeredGridCells.Fixed(1),
                    state = gridState,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 140.dp),
                    verticalItemSpacing = 8.dp,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { confirmEmptyTrash = true }) { Text("Empty Trash") }
                            }
                        }
                    }
                    if (pinned.isNotEmpty()) {
                        item(key = "header-pinned", span = StaggeredGridItemSpan.FullLine) { SectionHeader("Pinned") }
                        items(pinned, key = { it.note.id }) { item ->
                            NoteCard(item, vm, onOpen)
                        }
                        if (others.isNotEmpty()) {
                            item(key = "header-others", span = StaggeredGridItemSpan.FullLine) {
                                SectionHeader("Others")
                            }
                        }
                    }
                    items(others, key = { it.note.id }) { item ->
                        NoteCard(item, vm, onOpen)
                    }
                }
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
private fun DrawerItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun NotesSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    grid: Boolean,
    onToggleGrid: () -> Unit,
    onMenu: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, contentDescription = "Menu") }
            PlainTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = placeholder,
                textStyle = MaterialTheme.typography.bodyLarge,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                }
            }
            IconButton(onClick = onToggleGrid) {
                if (grid) Icon(Icons.Outlined.ViewAgenda, contentDescription = "Show as list")
                else Icon(Icons.Outlined.GridView, contentDescription = "Show as grid")
            }
        }
    }
}

@Composable
private fun NoteCard(item: NoteItem, vm: NotesViewModel, onOpen: (Long) -> Unit) {
    val note = item.note
    val inTrash = note.trashedAt != null
    var menuOpen by remember { mutableStateOf(false) }
    val background = noteBackground(note.color)
    val shape = RoundedCornerShape(12.dp)

    Box {
        Surface(
            shape = shape,
            color = background ?: MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (background == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(
                    onClick = { if (inTrash) menuOpen = true else onOpen(note.id) },
                    onLongClick = { menuOpen = true },
                ),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (note.title.isNotBlank()) {
                    Text(
                        note.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.isChecklist) {
                    val all = parseItems(note.body).filter { it.text.isNotBlank() }
                    val open = all.filter { !it.checked }
                    val done = all.size - open.size
                    open.take(8).forEach { checkItem ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                checkItem.text,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    val hidden = open.size - open.take(8).size
                    if (hidden > 0 || done > 0) {
                        val parts = buildList {
                            if (hidden > 0) add("+$hidden more")
                            if (done > 0) add("$done checked")
                        }
                        Text(
                            parts.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                        )
                    }
                } else if (note.body.isNotBlank()) {
                    Text(
                        note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
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

@Composable
fun LabelChip(name: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = LocalContentColor.current.copy(alpha = 0.1f),
        contentColor = LocalContentColor.current,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
