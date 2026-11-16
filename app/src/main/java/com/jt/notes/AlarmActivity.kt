package com.jt.notes

import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jt.notes.data.Note
import com.jt.notes.data.NotesDb
import com.jt.notes.data.parseItems
import com.jt.notes.ui.NotesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The full-screen alarm for alarm-type reminders. Shows over the lock screen,
 * plays the alarm sound with rising volume, and needs an explicit Snooze or
 * Done. Back is swallowed: dismissing the screen must not silently drop a
 * one-ping reminder.
 */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var noteId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        noteId = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1L)
        if (noteId <= 0) {
            finish()
            return
        }
        // The alarm sound moves here so it can rise in volume. When no sound
        // can be started, the notification is left in place to keep ringing.
        if (startSound()) Reminders.dismiss(this, noteId)
        val themeId = getSharedPreferences("prefs", MODE_PRIVATE).getString("theme", "classic") ?: "classic"
        setContent {
            NotesTheme(themeId) {
                AlarmScreen(
                    noteId = noteId,
                    onSnooze = { act(Reminders.ACTION_SNOOZE) },
                    onDone = { act(Reminders.ACTION_DONE) },
                )
            }
        }
    }

    /** Sends the same broadcast the notification buttons send, then closes. */
    private fun act(action: String) {
        sendBroadcast(
            Intent(this, ReminderReceiver::class.java)
                .setAction(action)
                .putExtra(Reminders.EXTRA_NOTE_ID, noteId)
        )
        finish()
    }

    /**
     * Loops the alarm sound, rising from quiet to full volume in five steps
     * over about thirty seconds. Returns false when no sound could be started.
     */
    private fun startSound(): Boolean {
        return try {
            val mp = MediaPlayer()
            mp.setDataSource(this, Settings.System.DEFAULT_ALARM_ALERT_URI)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            mp.isLooping = true
            mp.prepare()
            val steps = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1.0f)
            mp.setVolume(steps[0], steps[0])
            mp.start()
            player = mp
            steps.drop(1).forEachIndexed { i, volume ->
                handler.postDelayed({ player?.setVolume(volume, volume) }, (i + 1) * 6_000L)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun stopSound() {
        handler.removeCallbacksAndMessages(null)
        player?.runCatching { stop() }
        player?.runCatching { release() }
        player = null
    }

    override fun onDestroy() {
        stopSound()
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(noteId: Long, onSnooze: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val note by produceState<Note?>(initialValue = null, noteId) {
        value = withContext(Dispatchers.IO) { NotesDb.get(context).dao().getNote(noteId) }
    }
    BackHandler { /* An alarm needs an explicit Snooze or Done. */ }
    Box(
        Modifier.fillMaxSize().background(ComposeColor(0xFF101418)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Reminder",
                style = MaterialTheme.typography.labelLarge,
                color = ComposeColor.White.copy(alpha = 0.7f),
            )
            Text(
                note?.title?.ifBlank { "Reminder" } ?: "Reminder",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ComposeColor.White,
                textAlign = TextAlign.Center,
            )
            val snippet = note?.let {
                if (it.isChecklist) {
                    parseItems(it.body).filter { item -> !item.checked && item.text.isNotBlank() }
                        .joinToString("\n") { item -> item.text }
                } else {
                    it.body
                }
            }.orEmpty().take(200)
            if (snippet.isNotBlank()) {
                Text(
                    snippet,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                    color = ComposeColor.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Done", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) { Text("Snooze ${Reminders.SNOOZE_MIN} min", style = MaterialTheme.typography.titleMedium) }
        }
    }
}
