package com.jt.notes.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.NotesViewModel
import com.jt.notes.Undo
import com.jt.notes.data.CheckItem
import com.jt.notes.data.Images
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.Version
import com.jt.notes.data.checklistBodyToText
import com.jt.notes.data.isBlank
import com.jt.notes.data.itemsToBody
import com.jt.notes.data.parseItems
import com.jt.notes.data.textToChecklistBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Calendar
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember(noteId) { mutableStateOf<Note?>(null) }
    var original by remember(noteId) { mutableStateOf<Note?>(null) }
    var bodyField by remember(noteId) { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(noteId) {
        val fromDb = vm.getNote(noteId)
        if (fromDb == null) {
            onClose()
        } else {
            original = fromDb
            bodyField = TextFieldValue(fromDb.body, TextRange(fromDb.body.length))
            loaded = fromDb
        }
    }
    val note = loaded ?: return

    val labels by vm.labels.collectAsStateWithLifecycle()
    val links by vm.noteLabels.collectAsStateWithLifecycle()
    val allAttachments by vm.attachments.collectAsStateWithLifecycle()
    val myLabelIds = remember(links, noteId) { links.filter { it.noteId == noteId }.map { it.labelId }.toSet() }
    val myLabels = labels.filter { it.id in myLabelIds }
    val pictures = allAttachments.filter { it.noteId == noteId }

    // A brand-new note is ready to type into straight away.
    val startedBlank = remember(noteId) { note.isBlank() }
    val bodyFocus = remember { FocusRequester() }
    LaunchedEffect(noteId) {
        if (startedBlank && !note.isChecklist) {
            delay(150)
            runCatching { bodyFocus.requestFocus() }
        }
    }

    var showLabels by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf<List<Version>?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun edit(changed: Note) {
        loaded = changed.copy(updatedAt = System.currentTimeMillis())
    }

    /** Replaces the whole body, keeping the text field in step. */
    fun replaceContent(changed: Note) {
        bodyField = TextFieldValue(changed.body, TextRange(changed.body.length))
        edit(changed)
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
    val latestOriginal by rememberUpdatedState(original)
    DisposableEffect(noteId) {
        onDispose { if (!exit.handled) vm.finishEditing(latest, latestOriginal) }
    }

    /** Leaves the editor, writing [finalNote] (or dropping it if it is empty). */
    fun leave(finalNote: Note, offerUndo: Undo? = null) {
        exit.handled = true
        vm.finishEditing(finalNote, original, offerUndo)
        onClose()
    }
    BackHandler { leave(note) }

    // ----- things that open other screens -----

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImage(noteId, uri)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val name = pendingPhoto
        if (saved && name != null) vm.addImageFile(noteId, name)
        pendingPhoto = null
    }
    val savePdf = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri -> if (uri != null) vm.exportPdf(latest, uri) { toast(it) } }
    val askNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Notifications are off for Notes, so the reminder cannot pop up")
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            val current = latest
            if (current.isChecklist) {
                val items = parseItems(current.body).filter { it.text.isNotBlank() } + CheckItem(spoken, false)
                edit(current.copy(body = itemsToBody(items)))
            } else {
                val joined = if (current.body.isBlank()) spoken else current.body.trimEnd() + "\n" + spoken
                replaceContent(current.copy(body = joined))
            }
        }
    }

    fun startCamera() {
        val name = Images.newName()
        pendingPhoto = name
        try {
            val uri = FileProvider.getUriForFile(context, "com.jt.notes.files", Images.file(context, name))
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("The camera could not be opened")
        }
    }

    fun startSpeech() {
        try {
            speech.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            )
        } catch (e: ActivityNotFoundException) {
            toast("No voice typing app was found on this phone")
        }
    }

    fun pickReminder() {
        val start = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                TimePickerDialog(
                    context,
                    { _, hour, minute ->
                        val at = Calendar.getInstance().apply {
                            set(year, month, day, hour, minute, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (at <= System.currentTimeMillis()) {
                            toast("Pick a time in the future")
                        } else {
                            edit(latest.copy(reminderAt = at))
                            vm.setReminder(noteId, at)
                            if (Build.VERSION.SDK_INT >= 33) {
                                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    },
                    start.get(Calendar.HOUR_OF_DAY),
                    0,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            },
            start.get(Calendar.YEAR),
            start.get(Calendar.MONTH),
            start.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    fun shareAsText() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, note.title)
            .putExtra(Intent.EXTRA_TEXT, NoteExport.toMarkdown(note))
        try {
            context.startActivity(Intent.createChooser(send, "Share note"))
        } catch (e: Exception) {
            toast("Nothing on this phone can receive the text")
        }
    }

    fun openLink(link: DetectedLink) {
        val intent = when (link.kind) {
            LinkKind.WEB -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse(if (link.text.contains("://")) link.text else "https://" + link.text),
            )
            LinkKind.EMAIL -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + link.text))
            LinkKind.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + link.text.filter { it.isDigit() || it == '+' }))
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            toast("No app can open that")
        }
    }

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
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    val edited = Date(note.updatedAt)
                    Text(
                        "Edited " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = p.ink.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(edited),
                        style = MaterialTheme.typography.labelSmall,
                        color = p.ink.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                IconButton(onClick = {
                    replaceContent(
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
                        note.copy(archived = !note.archived, pinned = false, updatedAt = System.currentTimeMillis()),
                        Undo(if (note.archived) "Note unarchived" else "Note archived", listOf(note)),
                    )
                }) {
                    if (note.archived) Icon(Icons.Outlined.Unarchive, contentDescription = "Unarchive")
                    else Icon(Icons.Outlined.Archive, contentDescription = "Archive")
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        EditorMenuRow(
                            if (note.reminderAt == null) "Remind me" else "Change reminder",
                            Icons.Outlined.Alarm,
                        ) { menuOpen = false; pickReminder() }
                        EditorMenuRow("Add picture", Icons.Outlined.Image) {
                            menuOpen = false
                            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        EditorMenuRow("Take photo", Icons.Outlined.PhotoCamera) { menuOpen = false; startCamera() }
                        EditorMenuRow("Dictate", Icons.Outlined.Mic) { menuOpen = false; startSpeech() }
                        HorizontalDivider()
                        EditorMenuRow("Make a copy", Icons.Outlined.ContentCopy) {
                            menuOpen = false
                            vm.duplicate(listOf(note)) { toast("Copy made") }
                        }
                        if (note.isChecklist) {
                            EditorMenuRow("Copy with boxes cleared", Icons.Outlined.LibraryAddCheck) {
                                menuOpen = false
                                vm.duplicate(listOf(note), clearBoxes = true) { toast("Fresh copy made") }
                            }
                        }
                        EditorMenuRow("Version history", Icons.Outlined.History) {
                            menuOpen = false
                            scope.launch { versions = vm.versions(noteId) }
                        }
                        HorizontalDivider()
                        EditorMenuRow("Share as text", Icons.Outlined.Share) { menuOpen = false; shareAsText() }
                        EditorMenuRow("Save as PDF", Icons.Outlined.PictureAsPdf) {
                            menuOpen = false
                            savePdf.launch((note.title.ifBlank { "note" }).take(40) + ".pdf")
                        }
                    }
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

                val reminder = note.reminderAt
                if (reminder != null) {
                    Surface(
                        shape = CircleShape,
                        color = p.ink.copy(alpha = 0.08f),
                        contentColor = p.ink,
                    ) {
                        Row(
                            Modifier.padding(start = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                formatReminder(reminder),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { pickReminder() },
                            )
                            IconButton(onClick = {
                                edit(note.copy(reminderAt = null))
                                vm.setReminder(noteId, null)
                            }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove reminder",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (pictures.isNotEmpty()) {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pictures.forEach { picture ->
                            key(picture.id) {
                                Box {
                                    NoteImage(
                                        name = picture.fileName,
                                        maxPx = 1080,
                                        modifier = Modifier.size(200.dp).clip(RoundedCornerShape(20.dp)),
                                    )
                                    Surface(
                                        onClick = { vm.removeImage(picture) },
                                        shape = CircleShape,
                                        color = Color(0xCC0E1116),
                                        contentColor = Color.White,
                                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(36.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                contentDescription = "Remove picture",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

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
                    ChecklistEditor(
                        body = note.body,
                        focusFirst = startedBlank,
                        onBodyChange = { edit(note.copy(body = it)) },
                    )
                } else {
                    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp)
                    val dim = p.ink.copy(alpha = 0.35f)
                    val transformation = remember(dim) { MarkupTransformation(dim) }
                    BasicTextField(
                        value = bodyField,
                        onValueChange = { value ->
                            val next = continueList(bodyField, value)
                            bodyField = next
                            if (next.text != note.body) edit(note.copy(body = next.text))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 240.dp)
                            .focusRequester(bodyFocus),
                        textStyle = bodyStyle.copy(color = p.ink),
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        visualTransformation = transformation,
                        decorationBox = { inner ->
                            Box {
                                if (bodyField.text.isEmpty()) {
                                    Text("Start writing", style = bodyStyle, color = p.ink.copy(alpha = 0.45f))
                                }
                                inner()
                            }
                        },
                    )
                }

                val found = remember(note.title, note.body) { detectLinks(note.title + "\n" + note.body) }
                if (found.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        found.forEach { link ->
                            Surface(
                                onClick = { openLink(link) },
                                shape = CircleShape,
                                color = p.ink.copy(alpha = 0.08f),
                                contentColor = p.ink,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 260.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        when (link.kind) {
                                            LinkKind.WEB -> Icons.Outlined.Link
                                            LinkKind.EMAIL -> Icons.Outlined.Email
                                            LinkKind.PHONE -> Icons.Outlined.Phone
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        link.text,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
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

            // Bottom: formatting for text notes and when it was last edited, then the tool bar
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!note.isChecklist) {
                    fun format(change: (TextFieldValue) -> TextFieldValue) {
                        val changed = change(bodyField)
                        bodyField = changed
                        edit(note.copy(body = changed.text))
                    }
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        IconButton(onClick = { format { toggleLinePrefix(it, "# ") } }) {
                            Icon(Icons.Outlined.Title, contentDescription = "Heading")
                        }
                        IconButton(onClick = { format { toggleLinePrefix(it, "## ") } }) {
                            Text("H2", style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(onClick = { format { toggleBold(it) } }) {
                            Icon(Icons.Outlined.FormatBold, contentDescription = "Bold")
                        }
                        IconButton(onClick = { format { toggleItalic(it) } }) {
                            Icon(Icons.Outlined.FormatItalic, contentDescription = "Italic")
                        }
                        IconButton(onClick = { format { toggleStrike(it) } }) {
                            Icon(Icons.Outlined.FormatStrikethrough, contentDescription = "Strikethrough")
                        }
                        IconButton(onClick = { format { toggleLinePrefix(it, "- ") } }) {
                            Icon(Icons.Outlined.FormatListBulleted, contentDescription = "Bullet list")
                        }
                        IconButton(onClick = { format { toggleLinePrefix(it, "1. ") } }) {
                            Icon(Icons.Outlined.FormatListNumbered, contentDescription = "Numbered list")
                        }
                        IconButton(onClick = { format { toggleLinePrefix(it, "> ") } }) {
                            Icon(Icons.Outlined.FormatQuote, contentDescription = "Quote")
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
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
                        if (note.isBlank() && pictures.isEmpty()) {
                            leave(note)
                        } else {
                            vm.setReminder(noteId, null)
                            leave(
                                note.copy(
                                    trashedAt = System.currentTimeMillis(),
                                    pinned = false,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                                Undo("Moved to Trash", listOf(note)),
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
    val history = versions
    if (history != null) {
        VersionHistoryDialog(
            versions = history,
            onRestore = { version ->
                versions = null
                replaceContent(note.copy(title = version.title, body = version.body, isChecklist = version.isChecklist))
                toast("Earlier version restored")
            },
            onDismiss = { versions = null },
        )
    }
}

@Composable
private fun EditorMenuRow(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
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
            .size(44.dp)
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

/** Tracks one drag of a checklist handle. */
private class DragTracker {
    var index = -1
    var x = 0f
    var y = 0f
}

@Composable
private fun ChecklistEditor(body: String, focusFirst: Boolean, onBodyChange: (String) -> Unit) {
    val items = remember(body) { parseItems(body) }
    var focusIndex by remember { mutableIntStateOf(if (focusFirst) 0 else -1) }
    val ink = LocalContentColor.current
    val density = LocalDensity.current
    val rowStep = with(density) { 48.dp.toPx() }
    val indentStep = with(density) { 36.dp.toPx() }
    val drag = remember { DragTracker() }

    fun update(list: List<CheckItem>) = onBodyChange(itemsToBody(list))

    // Dragging a handle up or down moves the item past its neighbours;
    // dragging it right or left makes it a sub-item or a normal item.
    val onDragBy by rememberUpdatedState<(Float, Float) -> Unit> { dx, dy ->
        val from = drag.index
        if (from in items.indices) {
            drag.x += dx
            drag.y += dy
            val list = items.toMutableList()
            var changed = false
            if (drag.y > rowStep) {
                val to = (from + 1..items.lastIndex).firstOrNull { !items[it].checked }
                if (to != null) {
                    list[from] = items[to]
                    list[to] = items[from]
                    drag.index = to
                    changed = true
                }
                drag.y = 0f
            } else if (drag.y < -rowStep) {
                val to = (from - 1 downTo 0).firstOrNull { !items[it].checked }
                if (to != null) {
                    list[from] = items[to]
                    list[to] = items[from]
                    drag.index = to
                    changed = true
                }
                drag.y = 0f
            } else if (drag.x > indentStep) {
                if (from > 0 && items[from].indent == 0) {
                    list[from] = items[from].copy(indent = 1)
                    changed = true
                }
                drag.x = 0f
            } else if (drag.x < -indentStep) {
                if (items[from].indent > 0) {
                    list[from] = items[from].copy(indent = 0)
                    changed = true
                }
                drag.x = 0f
            }
            if (changed) update(list)
        }
    }

    @Composable
    fun ItemRow(index: Int, item: CheckItem) {
        val focusRequester = remember { FocusRequester() }
        val currentIndex by rememberUpdatedState(index)
        val wantsFocus = focusIndex == index
        LaunchedEffect(wantsFocus) {
            if (wantsFocus) {
                runCatching { focusRequester.requestFocus() }
                focusIndex = -1
            }
        }
        Row(
            Modifier.padding(start = if (item.indent > 0) 28.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!item.checked) {
                Box(
                    Modifier
                        .size(width = 28.dp, height = 44.dp)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = {
                                    drag.index = currentIndex
                                    drag.x = 0f
                                    drag.y = 0f
                                },
                                onDragEnd = { drag.index = -1 },
                                onDragCancel = { drag.index = -1 },
                            ) { change, amount ->
                                change.consume()
                                onDragBy(amount.x, amount.y)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.DragIndicator,
                        contentDescription = "Drag to move. Drag right to make a sub-item.",
                        tint = ink.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }
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
                        list.add(index + 1 + offset, CheckItem(part, false, item.indent))
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

    Column(Modifier.offset(x = (-8).dp)) {
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
            Spacer(Modifier.width(28.dp))
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, contentDescription = null, tint = ink.copy(alpha = 0.7f))
            }
            Spacer(Modifier.width(4.dp))
            Text("Add item", style = MaterialTheme.typography.bodyLarge, color = ink.copy(alpha = 0.7f))
        }
        val checkedCount = items.count { it.checked }
        if (checkedCount > 0) {
            HorizontalDivider(
                color = ink.copy(alpha = 0.16f),
                modifier = Modifier.padding(start = 8.dp, top = 8.dp),
            )
            Text(
                "DONE",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = ink.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp),
            )
            items.forEachIndexed { index, item ->
                if (item.checked) key(index) { ItemRow(index, item) }
            }
        }
    }
}
