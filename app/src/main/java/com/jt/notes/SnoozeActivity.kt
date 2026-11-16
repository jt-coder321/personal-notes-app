package com.jt.notes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jt.notes.ui.LocalPalette
import com.jt.notes.ui.NotesTheme

/** The snooze picker: how long until this reminder pings again. Opened from the notification. */
class SnoozeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val noteId = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1L)
        if (noteId <= 0) {
            finish()
            return
        }
        val themeId = getSharedPreferences("prefs", MODE_PRIVATE).getString("theme", "classic") ?: "classic"
        setContent {
            NotesTheme(themeId) {
                SnoozePicker(
                    onPick = { minutes ->
                        sendBroadcast(
                            Intent(this, ReminderReceiver::class.java)
                                .setAction(Reminders.ACTION_SNOOZE)
                                .putExtra(Reminders.EXTRA_NOTE_ID, noteId)
                                .putExtra(Reminders.EXTRA_SNOOZE_MIN, minutes),
                        )
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun SnoozePicker(onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val p = LocalPalette.current
    Surface(color = p.surface, contentColor = p.ink) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Snooze for how long?", style = MaterialTheme.typography.titleMedium)
            listOf(5, 15, 30, 60).forEach { minutes ->
                Button(onClick = { onPick(minutes) }, modifier = Modifier.fillMaxWidth()) {
                    Text("$minutes minutes")
                }
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}
