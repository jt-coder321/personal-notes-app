package com.jt.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.item
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.jt.notes.data.Note
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A month grid: a dot marks each day with a note created or edited, or a reminder due. */
@Composable
fun CalendarDialog(notes: List<Note>, onOpen: (Long) -> Unit, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }

    fun dayOf(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    val live = remember(notes) { notes.filter { it.trashedAt == null } }
    val marked = remember(live) {
        val days = HashSet<LocalDate>()
        live.forEach { note ->
            days.add(dayOf(note.createdAt))
            days.add(dayOf(note.updatedAt))
            note.reminderAt?.let { days.add(dayOf(it)) }
            note.remindAnchor?.let { days.add(dayOf(it)) }
        }
        days
    }
    val dayNotes = remember(selectedDay, live) {
        live.filter { note ->
            dayOf(note.createdAt) == selectedDay ||
                dayOf(note.updatedAt) == selectedDay ||
                note.reminderAt?.let { dayOf(it) == selectedDay } == true ||
                note.remindAnchor?.let { dayOf(it) == selectedDay } == true
        }.sortedByDescending { it.updatedAt }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = p.surface, contentColor = p.ink) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { month = month.minusMonths(1) }) {
                        Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { month = month.plusMonths(1) }) {
                        Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { name ->
                        Text(
                            name,
                            style = MaterialTheme.typography.labelMedium,
                            color = p.muted,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // Weeks start on Sunday; the first row is padded with blanks.
                val first = month.atDay(1)
                val offset = first.dayOfWeek.value % 7
                val cells = offset + month.lengthOfMonth()
                val rows = (cells + 6) / 7
                val today = LocalDate.now()
                for (row in 0 until rows) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val index = row * 7 + col
                            val day = index - offset + 1
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                if (day in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(day)
                                    val selected = date == selectedDay
                                    val isToday = date == today
                                    Column(
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .then(
                                                if (selected) Modifier.background(p.accent)
                                                else if (isToday) Modifier.border(1.dp, p.accent, CircleShape)
                                                else Modifier,
                                            )
                                            .clickable { selectedDay = date }
                                            .padding(top = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text(
                                            "$day",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) p.onAccent else p.ink,
                                        )
                                        if (date in marked) {
                                            Box(
                                                Modifier
                                                    .padding(top = 2.dp)
                                                    .size(6.dp)
                                                    .background(
                                                        if (selected) p.onAccent else p.accent,
                                                        CircleShape,
                                                    ),
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.size(40.dp))
                                }
                            }
                        }
                    }
                }
                Text(
                    selectedDay.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = p.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (dayNotes.isEmpty()) {
                        item {
                            Text(
                                "Nothing on this day.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = p.muted,
                            )
                        }
                    }
                    items(dayNotes, key = { it.id }) { note ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(note.id) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                note.title.ifBlank { "Untitled note" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!note.locked) {
                                val firstLine = note.body.lineSequence()
                                    .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                                if (firstLine.isNotEmpty()) {
                                    Text(
                                        firstLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = p.muted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}
