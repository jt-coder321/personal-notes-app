package com.jt.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.NotesViewModel
import com.jt.notes.data.CheckItem
import com.jt.notes.data.Note
import com.jt.notes.data.checklistBodyToText
import com.jt.notes.data.isBlank
import com.jt.notes.data.itemsToBody
import com.jt.notes.data.parseItems
import com.jt.notes.data.textToChecklistBody
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun EditorScreen(vm: NotesViewModel, noteId: Long, onClose: () -> Unit) {
    var loaded by remember(noteId) { mutableStateOf<Note?>(null) }
    LaunchedEffect(noteId) {
        val fromDb = vm.getNote(noteId)
        if (fromDb == null) onClose() else loaded = fromDb
    }
    val note = loaded ?: return

    val labels by vm.labels.collectAsStateWithLifecycle()
    val links by vm.noteLabels.collectAsStateWithLifecycle()
    val myLabelIds = remember(links, noteId) { links.filter { it.noteId == noteId }.map { it.labelId }.toSet() }
    val myLabels = labels.filter { it.id in myLabelIds }

    var showColors by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }

    fun edit(changed: Note) {
        loaded = changed.copy(updatedAt = System.currentTimeMillis())
    }

    // Autosave shortly after each change, when the app goes to the background, and on exit.
    LaunchedEffect(note) {
        delay(400)
        vm.save(note)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.save(note) }

    fun close() {
        vm.finishEditing(note)
        onClose()
    }
    BackHandler { close() }

    val background = noteBackground(note.color) ?: MaterialTheme.colorScheme.surface

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = background,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { edit(note.copy(pinned = !note.pinned)) }) {
                        if (note.pinned) Icon(Icons.Filled.PushPin, contentDescription = "Unpin")
                        else Icon(Icons.Outlined.PushPin, contentDescription = "Pin")
                    }
                    IconButton(onClick = {
                        vm.finishEditing(
                            note.copy(
                                archived = !note.archived,
                                pinned = false,
                                updatedAt = System.currentTimeMillis(),
                            )
                        )
                        onClose()
                    }) {
                        if (note.archived) Icon(Icons.Outlined.Unarchive, contentDescription = "Unarchive")
                        else Icon(Icons.Outlined.Archive, contentDescription = "Archive")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                actions = {
                    IconButton(onClick = {
                        edit(
                            if (note.isChecklist) {
                                note.copy(isChecklist = false, body = checklistBodyToText(note.body))
                            } else {
                                note.copy(isChecklist = true, body = textToChecklistBody(note.body))
                            }
                        )
                    }) {
                        if (note.isChecklist) Icon(Icons.Outlined.Notes, contentDescription = "Switch to text")
                        else Icon(Icons.Outlined.CheckBox, contentDescription = "Switch to checklist")
                    }
                    IconButton(onClick = { showColors = true }) {
                        Icon(Icons.Outlined.Palette, contentDescription = "Color")
                    }
                    IconButton(onClick = { showLabels = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Labels")
                    }
                    IconButton(onClick = {
                        if (note.isBlank()) vm.finishEditing(note) else vm.trash(note)
                        onClose()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Edited " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                            .format(Date(note.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.7f),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlainTextField(
                value = note.title,
                onValueChange = { edit(note.copy(title = it)) },
                placeholder = "Title",
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.fillMaxWidth(),
            )
            if (note.isChecklist) {
                ChecklistEditor(body = note.body, onBodyChange = { edit(note.copy(body = it)) })
            } else {
                PlainTextField(
                    value = note.body,
                    onValueChange = { edit(note.copy(body = it)) },
                    placeholder = "Note",
                    textStyle = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 240.dp),
                )
            }
            if (myLabels.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.clickable { showLabels = true },
                ) {
                    myLabels.forEach { LabelChip(it.name) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showColors) {
        ColorPickerDialog(
            selected = note.color,
            onSelect = { edit(note.copy(color = it)) },
            onDismiss = { showColors = false },
        )
    }
    if (showLabels) {
        LabelPickerDialog(
            vm = vm,
            noteId = noteId,
            labels = labels,
            selectedIds = myLabelIds,
            onDismiss = { showLabels = false },
        )
    }
}

@Composable
private fun ChecklistEditor(body: String, onBodyChange: (String) -> Unit) {
    val items = remember(body) { parseItems(body) }
    var focusIndex by remember { mutableIntStateOf(-1) }

    fun update(list: List<CheckItem>) = onBodyChange(itemsToBody(list))

    @Composable
    fun ItemRow(index: Int, item: CheckItem) {
        val focusRequester = remember { FocusRequester() }
        val wantsFocus = focusIndex == index
        LaunchedEffect(wantsFocus) {
            if (wantsFocus) {
                runCatching { focusRequester.requestFocus() }
                focusIndex = -1
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.checked,
                onCheckedChange = { checked ->
                    update(items.toMutableList().also { it[index] = item.copy(checked = checked) })
                },
            )
            PlainTextField(
                value = item.text,
                onValueChange = { text ->
                    // Enter (or a multi-line paste) splits into separate items.
                    val parts = text.split("\n")
                    val list = items.toMutableList()
                    list[index] = item.copy(text = parts[0])
                    parts.drop(1).forEachIndexed { offset, part ->
                        list.add(index + 1 + offset, CheckItem(part, false))
                    }
                    update(list)
                    if (parts.size > 1) focusIndex = index + parts.size - 1
                },
                placeholder = "List item",
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = LocalContentColor.current.copy(alpha = if (item.checked) 0.6f else 1f),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            IconButton(onClick = { update(items.toMutableList().also { it.removeAt(index) }) }) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove item")
            }
        }
    }

    Column {
        items.forEachIndexed { index, item ->
            if (!item.checked) key(index) { ItemRow(index, item) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    update(items + CheckItem("", false))
                    focusIndex = items.size
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(12.dp))
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("List item", color = LocalContentColor.current.copy(alpha = 0.6f))
        }
        val checkedCount = items.count { it.checked }
        if (checkedCount > 0) {
            HorizontalDivider()
            Text(
                "$checkedCount checked",
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
            )
            items.forEachIndexed { index, item ->
                if (item.checked) key(index) { ItemRow(index, item) }
            }
        }
    }
}
