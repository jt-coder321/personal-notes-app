package com.jt.notes.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jt.notes.NotesViewModel
import com.jt.notes.NoteAuth
import com.jt.notes.PinnedNote
import com.jt.notes.PlaceReminders
import com.jt.notes.ReminderPlan
import com.jt.notes.ReminderTime
import com.jt.notes.Undo
import com.jt.notes.data.AudioClips
import com.jt.notes.data.CheckItem
import com.jt.notes.data.ClipPlayer
import com.jt.notes.data.Images
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.NoteImageShare
import com.jt.notes.data.Ocr
import com.jt.notes.data.Version
import com.jt.notes.data.VoiceRecorder
import com.jt.notes.data.backlinksFor
import com.jt.notes.data.checklistBodyToText
import com.jt.notes.data.extractLinkTitles
import com.jt.notes.data.formatDue
import com.jt.notes.data.isBlank
import com.jt.notes.data.isOverdue
import com.jt.notes.data.itemsToBody
import com.jt.notes.data.parseItems
import com.jt.notes.data.resolveLinkTitle
import com.jt.notes.data.textToChecklistBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.time.LocalDate

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
    var rich by remember(noteId) { mutableStateOf(Rich()) }
    var composing by remember(noteId) { mutableStateOf<TextRange?>(null) }
    LaunchedEffect(noteId) {
        val fromDb = vm.getNote(noteId)
        if (fromDb == null) {
            onClose()
        } else {
            original = fromDb
            rich = richFromMarkdown(fromDb.body)
            loaded = fromDb
        }
    }
    val note = loaded ?: return

    // A locked note needs one biometric check per visit; leaving re-locks it.
    var noteUnlocked by remember(noteId) { mutableStateOf(false) }

    val labels by vm.labels.collectAsStateWithLifecycle()
    val links by vm.noteLabels.collectAsStateWithLifecycle()
    val allAttachments by vm.attachments.collectAsStateWithLifecycle()
    val allClips by vm.audioClips.collectAsStateWithLifecycle()
    val placeReminders by vm.placeReminders.collectAsStateWithLifecycle()
    val allNotes by vm.allNotes.collectAsStateWithLifecycle()
    // [[Note title]] links: which titles in this body point at a real note,
    // and which notes point back here.
    val linkTitles = remember(note.body, allNotes) {
        extractLinkTitles(note.body).filter { title -> resolveLinkTitle(title, allNotes) != null }.toSet()
    }
    val noteLinks = remember(note.body, allNotes) {
        extractLinkTitles(note.body).distinct().mapNotNull { title ->
            resolveLinkTitle(title, allNotes)?.let { title to it }
        }
    }
    val backlinks = remember(note.id, note.title, allNotes) { backlinksFor(note, allNotes) }
    val myLabelIds = remember(links, noteId) { links.filter { it.noteId == noteId }.map { it.labelId }.toSet() }
    val myLabels = labels.filter { it.id in myLabelIds }
    val pictures = allAttachments.filter { it.noteId == noteId }
    val clips = allClips.filter { it.noteId == noteId }

    // Voice notes: one recorder and one player per open note.
    val recorder = remember(noteId) { VoiceRecorder(context) }
    val player = remember(noteId) { ClipPlayer() }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var isClipPlaying by remember { mutableStateOf(false) }
    var showRecording by remember { mutableStateOf(false) }
    var showDraw by remember { mutableStateOf(false) }
    var showPlaceDialog by remember { mutableStateOf(false) }

    // Find in note: text mode only, selection-based navigation.
    var findOpen by remember { mutableStateOf(false) }
    // Focus mode: title and body only, no chrome. Per-editor-session state.
    var focusMode by remember(noteId) { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findIndex by remember { mutableStateOf(0) }

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
    var pinned by remember(noteId) { mutableStateOf(PinnedNote.isPinned(context, noteId)) }
    var versions by remember { mutableStateOf<List<Version>?>(null) }
    var pendingPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingOcrPhoto by rememberSaveable { mutableStateOf<String?>(null) }
    var showOcrChoice by remember { mutableStateOf(false) }
    var showMergePicker by remember { mutableStateOf(false) }
    var mergeTarget by remember { mutableStateOf<Note?>(null) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun edit(changed: Note) {
        loaded = changed.copy(updatedAt = System.currentTimeMillis())
    }

    /** Replaces the whole body, keeping the text field in step. */
    fun replaceContent(changed: Note) {
        rich = richFromMarkdown(changed.body)
        composing = null
        edit(changed)
    }

    // Autosave shortly after each change, when the app goes to the background,
    // and whenever the editor goes away for any reason.
    LaunchedEffect(note) {
        delay(400)
        vm.save(note)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.save(note)
        // The app went to the background mid-recording: keep what was recorded.
        if (recorder.isRecording) {
            recorder.stop()?.let { vm.addAudioClip(noteId, it.first, it.second) }
            showRecording = false
        }
    }

    val exit = remember(noteId) { ExitState() }
    val latest by rememberUpdatedState(note)
    val latestOriginal by rememberUpdatedState(original)
    DisposableEffect(noteId) {
        onDispose {
            player.release()
            recorder.cancel()
            if (!exit.handled) vm.finishEditing(latest, latestOriginal)
        }
    }

    /** Leaves the editor, writing [finalNote] (or dropping it if it is empty). */
    fun leave(finalNote: Note, offerUndo: Undo? = null) {
        exit.handled = true
        PinnedNote.refreshIfPinned(context, finalNote)
        vm.finishEditing(finalNote, original, offerUndo)
        onClose()
    }
    BackHandler { leave(note) }

    // ----- find in note -----

    val findMatches = remember(findQuery, rich.text) { findAllMatches(rich.text, findQuery) }

    /** Moves the cursor to a match, wrapping around at the ends. */
    fun jumpToMatch(index: Int) {
        if (findMatches.isEmpty()) return
        val wrapped = ((index % findMatches.size) + findMatches.size) % findMatches.size
        findIndex = wrapped
        val start = findMatches[wrapped]
        rich = rich.copy(selection = TextRange(start, start + findQuery.length))
        runCatching { bodyFocus.requestFocus() }
    }

    // Typing a query jumps to its first match. Editing the note itself does
    // not move the cursor: only the query restarts this.
    LaunchedEffect(findQuery) {
        if (findMatches.isNotEmpty()) {
            findIndex = 0
            val start = findMatches[0]
            rich = rich.copy(selection = TextRange(start, start + findQuery.length))
        }
    }

    // ----- scan text from a photo -----

    /** Adds recognized text at the cursor, or as new list items in a checklist. */
    fun insertScannedText(text: String) {
        val current = latest
        if (current.isChecklist) {
            val items = parseItems(current.body).filter { it.text.isNotBlank() } +
                text.split("\n").filter { it.isNotBlank() }.map { CheckItem(it.trim(), false) }
            edit(current.copy(body = itemsToBody(items)))
            toast("Scanned text added as list items")
        } else {
            val pos = rich.selection.min.coerceIn(0, rich.text.length)
            val end = rich.selection.max.coerceIn(0, rich.text.length)
            val atEnd = pos == rich.text.length
            val insert = (if (atEnd && rich.text.isNotEmpty() && !rich.text.endsWith("\n")) "\n" else "") + text
            val newText = rich.text.substring(0, pos) + insert + rich.text.substring(end)
            val next = clearSpanRange(
                richEdit(rich, newText, TextRange(pos + insert.length)),
                pos,
                pos + insert.length,
            )
            rich = next
            composing = null
            val markdown = richToMarkdown(next)
            if (markdown != note.body) edit(note.copy(body = markdown))
            toast("Scanned text added")
        }
    }

    fun scanBitmap(bitmap: Bitmap) {
        Ocr.recognize(bitmap) { text ->
            when {
                text == null -> toast("Text recognition failed. Try again once the model has downloaded.")
                text.isBlank() -> toast("No text was found in that photo")
                else -> insertScannedText(text.trim())
            }
        }
    }

    fun scanPickedPhoto(uri: Uri) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { Ocr.loadBitmap(context, uri) }
            if (bitmap == null) toast("That photo could not be read") else scanBitmap(bitmap)
        }
    }

    /** The camera shot is not attached to the note; the unused-picture sweep removes it later. */
    fun scanCameraPhoto(name: String) {
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { Images.load(context, name, 1600) }
            if (bitmap == null) toast("That photo could not be read") else scanBitmap(bitmap)
        }
    }

    // ----- things that open other screens -----

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addImage(noteId, uri)
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val name = pendingPhoto
        if (saved && name != null) vm.addImageFile(noteId, name)
        pendingPhoto = null
    }
    val pickPictureForOcr = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scanPickedPhoto(uri)
    }
    val takePictureForOcr = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val name = pendingOcrPhoto
        if (saved && name != null) scanCameraPhoto(name)
        pendingOcrPhoto = null
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

    fun startRecording() {
        player.stop()
        playingId = null
        isClipPlaying = false
        if (recorder.start()) showRecording = true else toast("Recording could not be started")
    }

    fun stopRecording(save: Boolean) {
        if (!recorder.isRecording) {
            showRecording = false
            return
        }
        if (save) {
            val kept = recorder.stop()
            if (kept != null) vm.addAudioClip(noteId, kept.first, kept.second)
            else toast("The recording could not be saved")
        } else {
            recorder.cancel()
        }
        showRecording = false
    }

    fun toggleClip(clip: com.jt.notes.data.AudioClip) {
        if (playingId == clip.id) {
            if (isClipPlaying) {
                player.pause()
                isClipPlaying = false
            } else {
                player.resume()
                isClipPlaying = player.isPlaying
            }
        } else {
            player.play(AudioClips.file(context, clip.fileName)) {
                playingId = null
                isClipPlaying = false
            }
            playingId = clip.id
            isClipPlaying = true
        }
    }

    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording() else toast("Microphone permission is needed to record a voice note")
    }

    // A voice-note shortcut opens the editor with recording started.
    LaunchedEffect(noteId) {
        if (vm.autoRecordFor.value == noteId) {
            vm.autoRecordFor.value = null
            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
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

    fun startOcrCamera() {
        val name = Images.newName()
        pendingOcrPhoto = name
        try {
            val uri = FileProvider.getUriForFile(context, "com.jt.notes.files", Images.file(context, name))
            takePictureForOcr.launch(uri)
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

    var pingDialogAt by remember { mutableStateOf<Long?>(null) }

    fun pickReminder() {
        // Pre-fill the time that was originally picked, not the next ping time
        // the reminder has since moved on to.
        val existing = latest.remindAnchor ?: latest.reminderAt
        val start = Calendar.getInstance().apply {
            if (existing != null) timeInMillis = existing else add(Calendar.HOUR_OF_DAY, 1)
        }
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
                            pingDialogAt = at
                        }
                    },
                    start.get(Calendar.HOUR_OF_DAY),
                    if (existing != null) start.get(Calendar.MINUTE) else 0,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            },
            start.get(Calendar.YEAR),
            start.get(Calendar.MONTH),
            start.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    pingDialogAt?.let { at ->
        var pings by remember(at) { mutableStateOf(latest.remindPings) }
        var gap by remember(at) { mutableStateOf(latest.remindGapMin) }
        var alarm by remember(at) { mutableStateOf(latest.remindAlarm) }
        var repeat by remember(at) { mutableStateOf(latest.remindRepeat) }
        // A plain-language time ("tomorrow 9am") overrides the picked time.
        // Nothing is guessed: an unparseable phrase leaves the picked time
        // in place and says so.
        var typedTime by remember(at) { mutableStateOf("") }
        val parsedAt = remember(typedTime) {
            if (typedTime.isBlank()) null else ReminderTime.parse(typedTime, System.currentTimeMillis())
        }
        val chosenAt = parsedAt ?: at
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pingDialogAt = null },
            title = { Text("Reminder options") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = typedTime,
                        onValueChange = { typedTime = it },
                        placeholder = { Text("e.g., tomorrow 9am") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (typedTime.isNotBlank() && parsedAt == null) {
                        Text(
                            "Couldn't understand that time. The picked time below will be used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.muted,
                        )
                    }
                    Text(
                        if (parsedAt != null) ReminderTime.format(parsedAt) else formatReminder(at),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val gaps = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60)
                    Stepper(
                        label = "Number of pings",
                        value = if (pings == 1) "1" else "$pings",
                        onMinus = { if (pings > 1) pings -= 1 },
                        onPlus = { if (pings < 20) pings += 1 },
                    )
                    if (pings > 1) {
                        Stepper(
                            label = "Repeat every",
                            value = if (gap < 60) "$gap min" else "1 hour",
                            onMinus = { gaps.lastOrNull { it < gap }?.let { gap = it } },
                            onPlus = { gaps.firstOrNull { it > gap }?.let { gap = it } },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Loud alarm sound", Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = alarm, onCheckedChange = { alarm = it })
                    }
                    Text("Repeat", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RepeatChoice("Once", repeat == ReminderPlan.REPEAT_NONE) { repeat = ReminderPlan.REPEAT_NONE }
                        RepeatChoice("Daily", repeat == ReminderPlan.REPEAT_DAILY) { repeat = ReminderPlan.REPEAT_DAILY }
                        RepeatChoice("Weekly", repeat == ReminderPlan.REPEAT_WEEKLY) { repeat = ReminderPlan.REPEAT_WEEKLY }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val updated = latest.copy(
                        reminderAt = chosenAt,
                        remindAnchor = chosenAt,
                        remindRepeat = repeat,
                        remindPings = pings,
                        remindGapMin = gap,
                        remindAlarm = alarm,
                        remindDone = 0,
                    )
                    edit(updated)
                    vm.saveReminderChange(updated)
                    vm.setReminder(noteId, chosenAt)
                    pingDialogAt = null
                    if (Build.VERSION.SDK_INT >= 33) {
                        askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text("Set reminder") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pingDialogAt = null }) { Text("Cancel") }
            },
        )
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

    // A locked note stays shut until the person proves it is them.
    if (note.locked && !noteUnlocked) {
        LockGate(
            note = note,
            embedded = embedded,
            modifier = frame,
            onUnlock = {
                val activity = context as? FragmentActivity
                if (activity != null) {
                    NoteAuth.prompt(activity, "Unlock note") { noteUnlocked = true }
                } else {
                    toast("Unlock is not available here")
                }
            },
            onBack = { leave(note) },
        )
        return
    }

    CompositionLocalProvider(LocalContentColor provides p.ink) {
        Column(frame) {
            // Top row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (focusMode) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { focusMode = false }) {
                        Icon(Icons.Outlined.FullscreenExit, contentDescription = "Exit focus mode")
                    }
                }
                if (!focusMode) {
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
                if (!note.isChecklist) {
                    IconButton(onClick = {
                        findOpen = !findOpen
                        if (!findOpen) findQuery = ""
                    }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Find in note")
                    }
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
                        EditorMenuRow("Remind me at a place", Icons.Outlined.LocationOn) {
                            menuOpen = false
                            showPlaceDialog = true
                        }
                        EditorMenuRow("Add picture", Icons.Outlined.Image) {
                            menuOpen = false
                            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        EditorMenuRow("Take photo", Icons.Outlined.PhotoCamera) { menuOpen = false; startCamera() }
                        EditorMenuRow("Scan text from photo", Icons.Outlined.DocumentScanner) {
                            menuOpen = false; showOcrChoice = true
                        }
                        EditorMenuRow("Add sketch", Icons.Outlined.Edit) { menuOpen = false; showDraw = true }
                        EditorMenuRow("Dictate", Icons.Outlined.Mic) { menuOpen = false; startSpeech() }
                        EditorMenuRow("Record voice note", Icons.Outlined.Mic) {
                            menuOpen = false
                            askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
                        }
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
                        EditorMenuRow("Save as template", Icons.Outlined.Description) {
                            menuOpen = false
                            if (note.title.isBlank() && note.body.isBlank()) {
                                toast("Nothing to save yet")
                            } else {
                                vm.saveTemplate(note.title, note.body, note.isChecklist)
                                toast("Template saved")
                            }
                        }
                        EditorMenuRow(
                            if (note.locked) "Unlock note" else "Lock note",
                            if (note.locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        ) {
                            menuOpen = false
                            val locking = !note.locked
                            vm.setNoteLocked(noteId, locking)
                            edit(note.copy(locked = locking))
                            if (locking) noteUnlocked = false
                        }
                        if (!note.locked) {
                            EditorMenuRow(
                                if (pinned) "Unpin from notifications" else "Pin to notifications",
                                if (pinned) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            ) {
                                menuOpen = false
                                if (pinned) {
                                    PinnedNote.unpin(context)
                                    pinned = false
                                } else {
                                    PinnedNote.pin(context, latest)
                                    pinned = true
                                }
                            }
                        }
                        EditorMenuRow("Merge into another note", Icons.Outlined.MergeType) {
                            menuOpen = false; showMergePicker = true
                        }
                        EditorMenuRow("Focus mode", Icons.Outlined.Fullscreen) {
                            menuOpen = false; focusMode = true
                        }
                        HorizontalDivider()
                        EditorMenuRow("Share as text", Icons.Outlined.Share) { menuOpen = false; shareAsText() }
                        EditorMenuRow("Share as image", Icons.Outlined.Image) {
                            menuOpen = false
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    runCatching { NoteImageShare.renderToFile(context, latest) }.getOrNull()
                                }
                                if (file == null) toast("The image could not be shared")
                                else NoteImageShare.shareFile(context, file, latest.title)
                            }
                        }
                        EditorMenuRow("Save as PDF", Icons.Outlined.PictureAsPdf) {
                            menuOpen = false
                            savePdf.launch((note.title.ifBlank { "note" }).take(40) + ".pdf")
                        }
                    }
                }
                }
            }

            // Find bar: text notes only. Matches move the cursor; there is no
            // persistent highlighting.
            if (findOpen && !note.isChecklist && !focusMode) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PlainTextField(
                        value = findQuery,
                        onValueChange = { findQuery = it },
                        placeholder = "Find in this note",
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (findQuery.isEmpty()) "" else if (findMatches.isEmpty()) "No matches"
                        else "${findIndex + 1} of ${findMatches.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = p.ink.copy(alpha = 0.7f),
                    )
                    IconButton(onClick = { jumpToMatch(findIndex - 1) }, enabled = findMatches.isNotEmpty()) {
                        Icon(Icons.Outlined.ExpandLess, contentDescription = "Previous match")
                    }
                    IconButton(onClick = { jumpToMatch(findIndex + 1) }, enabled = findMatches.isNotEmpty()) {
                        Icon(Icons.Outlined.ExpandMore, contentDescription = "Next match")
                    }
                    IconButton(onClick = { findOpen = false; findQuery = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close find")
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
                                formatReminder(reminder) + reminderDetail(note),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { pickReminder() },
                            )
                            IconButton(onClick = {
                                val cleared = note.copy(
                                    reminderAt = null,
                                    remindAnchor = null,
                                    remindRepeat = 0,
                                    remindDone = 0,
                                )
                                edit(cleared)
                                vm.saveReminderChange(cleared)
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

                val place = placeReminders.firstOrNull { it.noteId == noteId }
                if (place != null) {
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
                            Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                (if (place.trigger == PlaceReminders.TRIGGER_ARRIVE) "Arriving" else "Leaving") +
                                    " · within ${place.radiusM}m",
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { showPlaceDialog = true },
                            )
                            IconButton(onClick = { vm.clearPlaceReminder(noteId) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "Remove place reminder",
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

                clips.forEach { clip ->
                    key(clip.id) {
                        Surface(
                            shape = CircleShape,
                            color = p.ink.copy(alpha = 0.08f),
                            contentColor = p.ink,
                        ) {
                            Row(
                                Modifier.padding(start = 6.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                IconButton(onClick = { toggleClip(clip) }) {
                                    Icon(
                                        if (playingId == clip.id && isClipPlaying) Icons.Outlined.Pause
                                        else Icons.Outlined.PlayArrow,
                                        contentDescription = if (playingId == clip.id && isClipPlaying) "Pause" else "Play",
                                    )
                                }
                                Text(
                                    "Voice note",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(formatDuration(clip.durationMs), style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = {
                                    if (playingId == clip.id) {
                                        player.stop()
                                        playingId = null
                                        isClipPlaying = false
                                    }
                                    vm.removeAudioClip(clip)
                                }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Delete voice note",
                                        modifier = Modifier.size(18.dp),
                                    )
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
                    BasicTextField(
                        value = TextFieldValue(richAnnotated(rich, linkTitles, p.accent), rich.selection, composing),
                        onValueChange = { value ->
                            val next = richEdit(rich, value.text, value.selection)
                            composing = if (next.text == value.text) value.composition else null
                            rich = next
                            val markdown = richToMarkdown(next)
                            if (markdown != note.body) edit(note.copy(body = markdown))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 240.dp)
                            .focusRequester(bodyFocus),
                        textStyle = bodyStyle.copy(color = p.ink),
                        cursorBrush = SolidColor(p.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            Box {
                                if (rich.text.isEmpty()) {
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

                // Note links are tappable chips, like the web links above: taps
                // inside the text field move the cursor, so the chips carry
                // the navigation. Unresolved [[text]] gets no chip.
                if (noteLinks.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        noteLinks.forEach { (title, target) ->
                            Surface(
                                onClick = { vm.pendingOpen.value = target.id },
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
                                        Icons.Outlined.Link,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (backlinks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Linked from",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = p.ink.copy(alpha = 0.7f),
                        )
                        backlinks.forEach { source ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { vm.pendingOpen.value = source.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    source.title.ifBlank { "Untitled note" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // Bottom: formatting for text notes and when it was last edited, then the tool bar
            // The live word count sits at the end of the formatting row, so it never overlaps it.
            val words = remember(note.body, note.isChecklist) { countWords(note) }
            if (!focusMode) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!note.isChecklist) {
                    fun apply(next: Rich) {
                        rich = next
                        composing = null
                        val markdown = richToMarkdown(next)
                        if (markdown != note.body) edit(note.copy(body = markdown))
                    }
                    val marker = lineMarker(rich)
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        FormatButton(isHeadingOn(rich, Fmt.H1), p.accent, { apply(toggleHeading(rich, Fmt.H1)) }) {
                            Icon(Icons.Outlined.Title, contentDescription = "Heading")
                        }
                        FormatButton(isHeadingOn(rich, Fmt.H2), p.accent, { apply(toggleHeading(rich, Fmt.H2)) }) {
                            Text("H2", style = MaterialTheme.typography.labelLarge)
                        }
                        FormatButton(isInlineOn(rich, Fmt.BOLD), p.accent, { apply(toggleInline(rich, Fmt.BOLD)) }) {
                            Icon(Icons.Outlined.FormatBold, contentDescription = "Bold")
                        }
                        FormatButton(isInlineOn(rich, Fmt.ITALIC), p.accent, { apply(toggleInline(rich, Fmt.ITALIC)) }) {
                            Icon(Icons.Outlined.FormatItalic, contentDescription = "Italic")
                        }
                        FormatButton(isInlineOn(rich, Fmt.STRIKE), p.accent, { apply(toggleInline(rich, Fmt.STRIKE)) }) {
                            Icon(Icons.Outlined.FormatStrikethrough, contentDescription = "Strikethrough")
                        }
                        FormatButton(isInlineOn(rich, Fmt.UNDERLINE), p.accent, { apply(toggleInline(rich, Fmt.UNDERLINE)) }) {
                            Icon(Icons.Outlined.FormatUnderlined, contentDescription = "Underline")
                        }
                        FormatButton(isInlineOn(rich, Fmt.CODE), p.accent, { apply(toggleInline(rich, Fmt.CODE)) }) {
                            Icon(Icons.Outlined.Code, contentDescription = "Code")
                        }
                        FormatButton(marker == BULLET_MARKER, p.accent, { apply(togglePrefix(rich, BULLET_MARKER)) }) {
                            Icon(Icons.Outlined.FormatListBulleted, contentDescription = "Bullet list")
                        }
                        FormatButton(marker == NUMBER_MARKER, p.accent, { apply(togglePrefix(rich, NUMBER_MARKER)) }) {
                            Icon(Icons.Outlined.FormatListNumbered, contentDescription = "Numbered list")
                        }
                        FormatButton(marker == QUOTE_MARKER, p.accent, { apply(togglePrefix(rich, QUOTE_MARKER)) }) {
                            Icon(Icons.Outlined.FormatQuote, contentDescription = "Quote")
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Text(
                    wordCountLabel(words),
                    style = MaterialTheme.typography.labelSmall,
                    color = p.ink.copy(alpha = 0.6f),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            }
            if (!focusMode) {
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
                    ColorRow(
                        selected = note.color,
                        barColor = p.bar,
                        onSelect = { edit(note.copy(color = it)) },
                        modifier = Modifier.weight(1f),
                    )
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
                        contentColor = p.onAccent,
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
    if (showDraw) {
        DrawDialog(
            onSave = { bitmap ->
                showDraw = false
                scope.launch {
                    val name = withContext(Dispatchers.IO) { Images.saveSketch(context, bitmap) }
                    if (name != null) vm.addImageFile(noteId, name) else toast("The sketch could not be saved")
                }
            },
            onCancel = { showDraw = false },
        )
    }
    if (showPlaceDialog) {
        PlaceReminderDialog(
            vm = vm,
            noteId = noteId,
            existing = placeReminders.firstOrNull { it.noteId == noteId },
            onDismiss = { showPlaceDialog = false },
        )
    }
    if (showOcrChoice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showOcrChoice = false },
            title = { Text("Scan text from photo") },
            text = { Text("Pick a photo, or take one, and the words in it are added to this note.") },
            confirmButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        showOcrChoice = false
                        pickPictureForOcr.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }) { Text("Choose photo") }
                    androidx.compose.material3.TextButton(onClick = {
                        showOcrChoice = false
                        startOcrCamera()
                    }) { Text("Take photo") }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showOcrChoice = false }) { Text("Cancel") }
            },
        )
    }
    if (showMergePicker) {
        MergeTargetDialog(
            notes = allNotes,
            excludeId = noteId,
            onPick = { target -> showMergePicker = false; mergeTarget = target },
            onDismiss = { showMergePicker = false },
        )
    }
    mergeTarget?.let { target ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { mergeTarget = null },
            title = { Text("Merge into \"${target.title.ifBlank { "Untitled note" }}\" ?") },
            text = {
                Text(
                    "This note's text will be added to that note, and this note " +
                        "will be moved to Trash. The other note's earlier text stays in its version history.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val picked = target
                    mergeTarget = null
                    vm.mergeNotes(noteId, picked.id) { message ->
                        toast(message)
                        leave(latest.copy(trashedAt = System.currentTimeMillis(), pinned = false))
                    }
                }) { Text("Merge") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { mergeTarget = null }) { Text("Cancel") }
            },
        )
    }
    if (showRecording) {
        var elapsed by remember { mutableStateOf(0L) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(200)
                elapsed = recorder.elapsedMs
            }
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { stopRecording(save = false) },
            title = { Text("Recording") },
            text = { Text(formatDuration(elapsed) + "\nTap Stop to keep this voice note.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { stopRecording(save = true) }) { Text("Stop") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { stopRecording(save = false) }) { Text("Cancel") }
            },
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

/** The shut door of a locked note: title, lock, and one way in. */
@Composable
private fun LockGate(
    note: Note,
    embedded: Boolean,
    modifier: Modifier = Modifier,
    onUnlock: () -> Unit,
    onBack: () -> Unit,
) {
    val p = LocalPalette.current
    Box(modifier, contentAlignment = Alignment.Center) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
            if (embedded) Icon(Icons.Outlined.Close, contentDescription = "Close note")
            else Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = p.ink, modifier = Modifier.size(40.dp))
            Text(
                note.title.ifBlank { "Locked note" },
                style = MaterialTheme.typography.titleLarge,
                color = p.ink,
                textAlign = TextAlign.Center,
            )
            Text("This note is locked", style = MaterialTheme.typography.bodyMedium, color = p.muted)
            androidx.compose.material3.Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

/** The note color choices. Edge fades show the row scrolls: the trailing fade
 * is visible while more colors lie beyond the visible ones, and the leading
 * fade appears once the row is scrolled. Taps pass through the fades to the
 * dots beneath them. */
@Composable
private fun ColorRow(
    selected: Int,
    barColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Row(
            Modifier.horizontalScroll(scroll),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteColors.indices.forEach { index ->
                ColorDot(
                    index = index,
                    selected = index == selected || (index == 0 && selected !in NoteColors.indices),
                    onClick = { onSelect(index) },
                )
            }
        }
        if (scroll.value > 0) {
            Box(
                Modifier.align(Alignment.CenterStart).width(28.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(barColor, Color.Transparent)))
            )
        }
        if (scroll.value < scroll.maxValue) {
            Box(
                Modifier.align(Alignment.CenterEnd).width(28.dp).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, barColor)))
            )
        }
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

    // Due dates: the platform date picker, with a neutral button to clear.
    // The dialog is modal, so the item list cannot change while it is up.
    val dueContext = LocalContext.current
    fun showDuePicker(index: Int, current: String?) {
        val initial = runCatching { LocalDate.parse(current) }.getOrDefault(LocalDate.now())
        DatePickerDialog(
            dueContext,
            { _, year, month, day ->
                val picked = LocalDate.of(year, month + 1, day).toString()
                update(items.toMutableList().also { it[index] = items[index].copy(due = picked) })
            },
            initial.year, initial.monthValue - 1, initial.dayOfMonth,
        ).apply {
            setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "Clear date") { _, _ ->
                update(items.toMutableList().also { it[index] = items[index].copy(due = null) })
            }
            show()
        }
    }

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
            // Due date: a chip showing the date, or a calendar affordance to
            // set one. Overdue open items get the error tint.
            val due = item.due
            val overdue = due != null && !item.checked && isOverdue(due)
            androidx.compose.material3.TextButton(
                onClick = { showDuePicker(index, due) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                if (due != null) {
                    Text(
                        formatDue(due),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (overdue) MaterialTheme.colorScheme.error else ink.copy(alpha = 0.75f),
                    )
                } else {
                    Icon(
                        Icons.Outlined.CalendarMonth,
                        contentDescription = "Set a due date",
                        tint = ink.copy(alpha = 0.45f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
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

@Composable
private fun FormatButton(active: Boolean, accent: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (active) accent.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (active) accent else LocalContentColor.current,
        ),
    ) { content() }
}

private fun reminderDetail(n: com.jt.notes.data.Note): String {
    val parts = mutableListOf<String>()
    if (n.remindPings > 1) parts.add("${n.remindPings} pings, every ${n.remindGapMin} min")
    if (n.remindAlarm) parts.add("alarm")
    if (n.remindRepeat != ReminderPlan.REPEAT_NONE) parts.add(ReminderPlan.repeatLabel(n.remindRepeat).lowercase())
    return if (parts.isEmpty()) "" else " \u00b7 " + parts.joinToString(", ")
}

/** One choice in the reminder dialog's repeat picker. */
@Composable
private fun RepeatChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** A length in milliseconds as m:ss. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

/** Words in the note's plain text: markup stripped for text notes, item text for checklists. */
private fun countWords(note: Note): Int {
    val plain = if (note.isChecklist) checklistBodyToText(note.body) else NoteExport.plainFromMarkup(note.body)
    return plain.split(Regex("\\s+")).count { it.isNotBlank() }
}

/** "42 words · 1 min read" at 200 words a minute, rounded up. */
private fun wordCountLabel(words: Int): String {
    if (words == 0) return "0 words"
    val minutes = (words + 199) / 200
    return "$words words \u00b7 $minutes min read"
}

/** Every start offset of [query] in [text], ignoring case. Empty when the query is blank. */
private fun findAllMatches(text: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    val out = ArrayList<Int>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) break
        out.add(at)
        from = at + query.length
    }
    return out
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        androidx.compose.material3.OutlinedButton(
            onClick = onMinus,
            modifier = Modifier.size(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("\u2212", style = MaterialTheme.typography.titleMedium) }
        Text(
            value,
            modifier = Modifier.width(72.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
        )
        androidx.compose.material3.OutlinedButton(
            onClick = onPlus,
            modifier = Modifier.size(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

