package com.jt.notes.ui

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
import androidx.compose.ui.unit.dp
import com.jt.notes.NotesViewModel
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import com.jt.notes.data.Label
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
private fun NewLabelRow(onAdd: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text("New label") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            enabled = name.isNotBlank(),
            onClick = {
                onAdd(name)
                name = ""
            },
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add label")
        }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                labels.forEach { label ->
                    val on = label.id in selectedIds
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.setNoteLabel(noteId, label.id, !on) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, onCheckedChange = { vm.setNoteLabel(noteId, label.id, it) })
                        Text(label.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                NewLabelRow(onAdd = { vm.addLabel(it, attachToNoteId = noteId) })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
fun ManageLabelsDialog(vm: NotesViewModel, labels: List<Label>, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf<Label?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit labels") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                NewLabelRow(onAdd = { vm.addLabel(it) })
                Spacer(Modifier.height(8.dp))
                labels.forEach { label ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(label.name, modifier = Modifier.weight(1f))
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
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
