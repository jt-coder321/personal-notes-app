package com.jt.notes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.jt.notes.LabelTree
import com.jt.notes.NotesViewModel
import com.jt.notes.PlaceReminders
import com.jt.notes.ReminderPlan
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.jt.notes.data.Label
import com.jt.notes.data.Note
import com.jt.notes.data.PlaceReminder
import com.jt.notes.data.Template
import com.jt.notes.data.Version
import com.jt.notes.data.checklistBodyToText
import java.text.DateFormat
import java.util.Date

@Composable
fun ColorPickerDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Color") },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NoteColors.indices.forEach { index ->
                    val fill = noteBackground(index) ?: MaterialTheme.colorScheme.surface
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(fill)
                            .border(
                                BorderStroke(
                                    if (isSelected) 3.dp else 1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                                CircleShape,
                            )
                            .clickable { onSelect(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == 0) {
                            Icon(
                                Icons.Outlined.FormatColorReset,
                                contentDescription = "No color",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun NewLabelRow(name: String, onName: (String) -> Unit, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            placeholder = { Text("New label name") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onDone = { if (name.isNotBlank()) onAdd() }
            ),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(enabled = name.isNotBlank(), onClick = onAdd) { Text("Add") }
    }
}

@Composable
fun LabelPickerDialog(
    vm: NotesViewModel,
    noteId: Long,
    labels: List<Label>,
    selectedIds: Set<Long>,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    val typing = newName.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                LabelTree.ordered(labels).forEach { (label, depth) ->
                    val on = label.id in selectedIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = (depth.coerceAtMost(LabelTree.MAX_INDENT_DEPTH) * 16).dp)
                            .clickable { vm.setNoteLabel(noteId, label.id, !on) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { vm.setNoteLabel(noteId, label.id, it) })
                        Text(label.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                NewLabelRow(
                    name = newName,
                    onName = { newName = it },
                    onAdd = { vm.addLabel(newName, attachToNoteId = noteId); newName = "" },
                )
            }
        },
        confirmButton = {
            if (typing) {
                TextButton(onClick = { vm.addLabel(newName, attachToNoteId = noteId); newName = "" }) {
                    Text("Add \"${newName.trim().take(18)}\"")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = if (typing) {
            { TextButton(onClick = { newName = "" }) { Text("Cancel") } }
        } else null,
    )
}

@Composable
fun ManageLabelsDialog(vm: NotesViewModel, labels: List<Label>, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Label?>(null) }
    var parenting by remember { mutableStateOf<Label?>(null) }
    var newName by remember { mutableStateOf("") }
    val typing = newName.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit labels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NewLabelRow(
                    name = newName,
                    onName = { newName = it },
                    onAdd = { vm.addLabel(newName); newName = "" },
                )
                Spacer(Modifier.height(8.dp))
                LabelTree.ordered(labels).forEach { (label, depth) ->
                    val parentName = labels.firstOrNull { it.id == label.parentId }?.name
                    Row(
                        modifier = Modifier.padding(
                            start = (depth.coerceAtMost(LabelTree.MAX_INDENT_DEPTH) * 16).dp,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { parenting = label },
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text(
                                parentName ?: "Top level",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { renaming = label }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = { vm.deleteLabel(label) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete label")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (typing) {
                TextButton(onClick = { vm.addLabel(newName); newName = "" }) {
                    Text("Add \"${newName.trim().take(18)}\"")
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = if (typing) {
            { TextButton(onClick = { newName = "" }) { Text("Cancel") } }
        } else null,
    )

    renaming?.let { label ->
        var name by remember(label.id) { mutableStateOf(label.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename label") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameLabel(label, name)
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    parenting?.let { label ->
        ParentPickerDialog(vm = vm, label = label, labels = labels, onDismiss = { parenting = null })
    }
}

/** Picks the parent of one label: top level, or another label that is not itself or below it. */
@Composable
fun ParentPickerDialog(vm: NotesViewModel, label: Label, labels: List<Label>, onDismiss: () -> Unit) {
    val descendants = LabelTree.descendantIds(labels, label.id)
    val choices = labels.filter { it.id != label.id && it.id !in descendants }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parent of \"${label.name}\"") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.setLabelParent(label, null); onDismiss() }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Top level", modifier = Modifier.weight(1f))
                    if (label.parentId == null) Icon(Icons.Outlined.Check, contentDescription = null)
                }
                LabelTree.ordered(choices).forEach { (choice, depth) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = (depth.coerceAtMost(LabelTree.MAX_INDENT_DEPTH) * 16).dp)
                            .clickable { vm.setLabelParent(label, choice.id); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(choice.name, modifier = Modifier.weight(1f))
                        if (label.parentId == choice.id) Icon(Icons.Outlined.Check, contentDescription = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks how to back up: plain, or locked with a password. */
@Composable
fun BackupDialog(onDismiss: () -> Unit, onConfirm: (password: String?) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up to a file") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Saves every note, label and picture into one file. Pick Google Drive or any folder on the next screen.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "With a password the file is encrypted. There is no way to recover a forgotten password.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.ifEmpty { null }) }) { Text("Back up") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks for the password of an encrypted backup. */
@Composable
fun PasswordDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("This backup is encrypted") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onConfirm(password) }) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists every note with a reminder still to come. Tapping one opens it; the X clears its reminder. */
@Composable
fun RemindersListDialog(
    notes: List<Note>,
    onOpen: (Long) -> Unit,
    onClear: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminders") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (notes.isEmpty()) {
                    Text("No reminders set. Open a note and tap Remind me to add one.")
                }
                notes.forEach { note ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(note.id) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                note.title.ifBlank {
                                    (if (note.isChecklist) checklistBodyToText(note.body) else note.body)
                                        .lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: "Empty note"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                formatReminder(note.reminderAt ?: 0L) + " \u00b7 " +
                                    ReminderPlan.repeatLabel(note.remindRepeat),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { onClear(note.id) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear reminder")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Lists earlier versions of a note. Tapping one brings its text back. */
@Composable
fun VersionHistoryDialog(versions: List<Version>, onRestore: (Version) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Version history") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (versions.isEmpty()) {
                    Text("No earlier versions yet. One is saved each time you change this note and close it.")
                }
                versions.forEach { version ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onRestore(version) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(version.savedAt)),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val preview = (if (version.isChecklist) checklistBodyToText(version.body) else version.body)
                            .ifBlank { version.title }
                        Text(
                            preview,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}



/** Picks the note another note merges into. The source itself and trash are left out. */
@Composable
fun MergeTargetDialog(
    notes: List<Note>,
    excludeId: Long,
    onPick: (Note) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = notes.filter { it.id != excludeId && it.trashedAt == null }.sortedByDescending { it.updatedAt }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Merge into another note") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (targets.isEmpty()) {
                    Text("No other notes to merge into.")
                }
                targets.forEach { target ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(target) }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(
                            target.title.ifBlank { "Untitled note" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!target.locked) {
                            val firstLine = (if (target.isChecklist) checklistBodyToText(target.body) else target.body)
                                .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                            if (firstLine.isNotEmpty()) {
                                Text(
                                    firstLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists saved templates. Tapping one starts a new note from it; the X deletes it. */
@Composable
fun TemplatesDialog(
    templates: List<Template>,
    onApply: (Template) -> Unit,
    onDelete: (Template) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Templates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (templates.isEmpty()) {
                    Text("No templates yet. Open a note and tap Save as template to keep it as a starter.")
                }
                templates.forEach { template ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onApply(template) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                template.title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val firstLine = (if (template.isChecklist) checklistBodyToText(template.body) else template.body)
                                .lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                            if (firstLine.isNotEmpty()) {
                                Text(
                                    firstLine,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(template) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete template")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Sets a reminder that fires when the phone arrives at, or leaves, the current place. */
@Composable
fun PlaceReminderDialog(
    vm: NotesViewModel,
    noteId: Long,
    existing: PlaceReminder?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var latitude by remember { mutableStateOf(existing?.latitude) }
    var longitude by remember { mutableStateOf(existing?.longitude) }
    var radiusM by remember { mutableStateOf(existing?.radiusM ?: PlaceReminders.RADIUS_DEFAULT) }
    var trigger by remember { mutableStateOf(existing?.trigger ?: PlaceReminders.TRIGGER_ARRIVE) }
    var message by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var pendingSave by remember { mutableStateOf(false) }

    fun hasLocation(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasBackground(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun capture() {
        val loc = PlaceReminders.lastKnown(context)
        if (loc == null) {
            message = "Could not get a location. Turn location on and try again."
        } else {
            latitude = loc.latitude
            longitude = loc.longitude
            message = null
        }
    }

    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            capture()
        } else {
            message = "Location permission is needed to use the current place."
        }
    }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val lat = latitude
        val lng = longitude
        if (granted && pendingSave && lat != null && lng != null) {
            pendingSave = false
            vm.savePlaceReminder(noteId, lat, lng, radiusM, trigger)
            onDismiss()
        } else {
            pendingSave = false
            showSettings = true
            message = "Background location was not allowed, so the reminder was not saved. " +
                "Place reminders need location access all the time to notice when you arrive or leave."
        }
    }

    fun save() {
        val lat = latitude
        val lng = longitude
        if (lat == null || lng == null) {
            message = "Use the current location first."
            return
        }
        if (!hasBackground()) {
            pendingSave = true
            askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            return
        }
        vm.savePlaceReminder(noteId, lat, lng, radiusM, trigger)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me at a place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Pings when you arrive at, or leave, the place you are at now. " +
                        "Checked about every 15 minutes while location is on.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { if (hasLocation()) capture() else askFine.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                        Text("Use current location")
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (latitude != null && longitude != null) "Place set" else "No place yet",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Within", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaceReminders.RADII.forEach { radius ->
                        FilterChip(
                            selected = radiusM == radius,
                            onClick = { radiusM = radius },
                            label = { Text("${radius}m") },
                        )
                    }
                }
                Text("Ping me", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = trigger == PlaceReminders.TRIGGER_ARRIVE,
                        onClick = { trigger = PlaceReminders.TRIGGER_ARRIVE },
                        label = { Text("When I arrive") },
                    )
                    FilterChip(
                        selected = trigger == PlaceReminders.TRIGGER_LEAVE,
                        onClick = { trigger = PlaceReminders.TRIGGER_LEAVE },
                        label = { Text("When I leave") },
                    )
                }
                if (existing != null) {
                    TextButton(onClick = { vm.clearPlaceReminder(noteId); onDismiss() }) {
                        Text("Remove place reminder")
                    }
                }
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (showSettings) {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    }) { Text("Open app settings") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}