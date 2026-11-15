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
import com.jt.notes.data.Label

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
