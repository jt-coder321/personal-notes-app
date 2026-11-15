package com.jt.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Remembers whether the editor already saved on its way out. */
private class ExitState {
    var handled = false
}

/**
 * The note editor. [embedded] is true when it sits beside the list on a wide
 * screen; it then draws as a rounded card instead of a full page.
 */
@Composable
fun EditorScreen(vm: NotesViewModel, noteId: Long, embedded: Boolean, onClose: () -> Unit) {
    val p = LocalPalette.current
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
    var showLabels by remember { mutableStateOf(false) }

    fun edit(changed: Note) {
        loaded = changed.copy(updatedAt = System.currentTimeMillis())
    }

    // Autosave shortly after each change, when the app goes to the background,
    // and whenever the editor goes away for any reason.
    LaunchedEffect(note) {
        delay(400)
        vm.save(note)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.save(note) }

    val exit = remember(noteId) { ExitState() }
    val latest by rememberUpdatedState(note)
    DisposableEffect(noteId) {
        onDispose { if (!exit.handled) vm.finishEditing(latest) }
    }

    /** Leaves the editor, writing [finalNote] (or dropping it if it is empty). */
    fun leave(finalNote: Note) {
        exit.handled = true
        vm.finishEditing(finalNote)
        onClose()
    }
    BackHandler { leave(note) }

    val background = noteBackground(note.color) ?: p.surface
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)
    val frame = if (embedded) {
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(insets)
            .padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(background)
    } else {
        Modifier.fillMaxSize().background(background).windowInsetsPadding(insets)
    }

    CompositionLocalProvider(LocalContentColor provides p.ink) {
        Column(frame) {
            // Top row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { leave(note) }) {
                    if (embedded) Icon(Icons.Outlined.Close, contentDescription = "Close note")
                    else Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = {
                    edit(
                        if (note.isChecklist) {
                            note.copy(isChecklist = false, body = checklistBodyToText(note.body))
                        } else {
                            note.copy(isChecklist = true, body = textToChecklistBody(note.body))
                        }
                    )
                }) {
                    if (note.isChecklist) Icon(Icons.Outlined.Subject, contentDescription = "Switch to text")
                    else Icon(Icons.Outlined.CheckBox, contentDescription = "Switch to checklist")
                }
                IconButton(
                    onClick = { edit(note.copy(pinned = !note.pinned)) },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (note.pinned) p.ink else Color.Transparent,
                        contentColor = if (note.pinned) background else p.ink,
                    ),
                ) {
                    Icon(Icons.Outlined.PushPin, contentDescription = if (note.pinned) "Unpin" else "Pin")
                }
                IconButton(onClick = {
                    leave(
                        note.copy(
                            archived = !note.archived,
                            pinned = false,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }) {
                    if (note.archived) Icon(Icons.Outlined.Unarchive, contentDescription = "Unarchive")
                    else Icon(Icons.Outlined.Archive, contentDescription = "Archive")
                }
            }

            // The note itself
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlainTextField(
                    value = note.title,
                    onValueChange = { edit(note.copy(title = it)) },
                    placeholder = "Title",
                    textStyle = TextStyle(
                        fontFamily = Display,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        lineHeight = 38.sp,
                        letterSpacing = (-0.6).sp,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (note.isChecklist) {
                    val named = parseItems(note.body).filter { it.text.isNotBlank() }
                    if (named.isNotEmpty()) {
                        val done = named.count { it.checked }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ProgressLine(done = done, total = named.size, modifier = Modifier.weight(1f))
                            Text(
                                "$done of ${named.size} done",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = p.ink.copy(alpha = 0.78f),
                            )
                        }
                    }
                    ChecklistEditor(body = note.body, onBodyChange = { edit(note.copy(body = it)) })
                } else {
                    PlainTextField(
                        value = note.body,
                        onValueChange = { edit(note.copy(body = it)) },
                        placeholder = "Start writing",
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
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
            }

            // Bottom: when it was last edited, then the tool bar
            Text(
                "Edited " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(note.updatedAt)),
                style = MaterialTheme.typography.labelSmall,
                color = p.ink.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
            )
            Surface(
                shape = CircleShape,
                color = p.bar,
                contentColor = p.onBar,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                    .height(68.dp),
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NoteColors.indices.forEach { index ->
                            ColorDot(
                                index = index,
                                selected = index == note.color || (index == 0 && note.color !in NoteColors.indices),
                                onClick = { edit(note.copy(color = index)) },
                            )
                        }
                    }
                    IconButton(onClick = { showLabels = true }) {
                        Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = "Labels")
                    }
                    IconButton(onClick = {
                        if (note.isBlank()) {
                            leave(note)
                        } else {
                            leave(
                                note.copy(
                                    trashedAt = System.currentTimeMillis(),
                                    pinned = false,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                    }
                    Spacer(Modifier.width(4.dp))
                    Surface(
                        onClick = { leave(note) },
                        shape = CircleShape,
                        color = p.accent,
                        contentColor = Color.White,
                        modifier = Modifier.height(44.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                            Text("Done", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
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

/** One color choice in the tool bar. Index 0 is "no color". */
@Composable
private fun ColorDot(index: Int, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = if (index == 0) "No color" else "Color $index"
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .then(if (selected) Modifier.border(2.dp, p.onBar, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (index == 0) {
                Icon(
                    Icons.Outlined.FormatColorReset,
                    contentDescription = label,
                    tint = p.onBar,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                // Always the light tint, so the choices stay readable on the dark bar.
                Box(Modifier.size(22.dp).clip(CircleShape).background(NoteColors[index].first))
            }
        }
    }
}

/** A round checkbox with a comfortable touch target. */
@Composable
private fun RoundCheck(checked: Boolean, onToggle: () -> Unit) {
    val ink = LocalContentColor.current
    val p = LocalPalette.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = if (checked) "Mark as not done" else "Mark as done", onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(ink), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Done",
                    tint = if (p.dark) Color(0xFF0B0D12) else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            Box(Modifier.size(26.dp).border(2.dp, ink, CircleShape))
        }
    }
}

@Composable
private fun ChecklistEditor(body: String, onBodyChange: (String) -> Unit) {
    val items = remember(body) { parseItems(body) }
    var focusIndex by remember { mutableIntStateOf(-1) }
    val ink = LocalContentColor.current

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
            RoundCheck(
                checked = item.checked,
                onToggle = {
                    update(items.toMutableList().also { it[index] = item.copy(checked = !item.checked) })
                },
            )
            Spacer(Modifier.width(4.dp))
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
                    fontSize = 17.sp,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                ),
                color = ink.copy(alpha = if (item.checked) 0.65f else 1f),
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            IconButton(onClick = { update(items.toMutableList().also { it.removeAt(index) }) }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "Remove item",
                    tint = ink.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    // Pulled left so the checkboxes line up with the title above.
    Column(Modifier.offset(x = (-11).dp)) {
        items.forEachIndexed { index, item ->
            if (!item.checked) key(index) { ItemRow(index, item) }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    update(items + CheckItem("", false))
                    focusIndex = items.size
                }
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = ink.copy(alpha = 0.7f))
            }
            Spacer(Modifier.width(4.dp))
            Text("Add item", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.7f))
        }
        val checkedCount = items.count { it.checked }
        if (checkedCount > 0) {
            HorizontalDivider(
                color = ink.copy(alpha = 0.16f),
                modifier = Modifier.padding(start = 11.dp, top = 8.dp),
            )
            Text(
                "DONE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = ink.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 11.dp, top = 14.dp, bottom = 4.dp),
            )
            items.forEachIndexed { index, item ->
                if (item.checked) key(index) { ItemRow(index, item) }
            }
        }
    }
}
