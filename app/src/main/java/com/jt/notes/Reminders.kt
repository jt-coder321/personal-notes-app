package com.jt.notes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jt.notes.data.NotesDb
import com.jt.notes.data.parseItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Note reminders. Alarms are the battery-friendly kind, so a reminder can
 * arrive a few minutes after the chosen time.
 */
object Reminders {
    const val ACTION_REMIND = "com.jt.notes.REMIND"
    const val EXTRA_NOTE_ID = "noteId"
    private const val CHANNEL = "reminders"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun alarmIntent(context: Context, noteId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND).putExtra(EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, noteId: Long, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context, noteId))
    }

    fun cancel(context: Context, noteId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, noteId))
    }

    /** Sets every pending reminder again, for example after the phone restarts. */
    suspend fun rescheduleAll(context: Context) {
        NotesDb.get(context).dao().notesWithReminders().forEach { note ->
            note.reminderAt?.let { schedule(context, note.id, it) }
        }
    }

    fun show(context: Context, noteId: Long, title: String, text: String) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            noteId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(EXTRA_NOTE_ID, noteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(noteId.toInt(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (intent.action == Reminders.ACTION_REMIND) {
                    val id = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1)
                    val dao = NotesDb.get(app).dao()
                    val note = dao.getNote(id)
                    if (note != null && note.trashedAt == null && note.reminderAt != null) {
                        val text = if (note.isChecklist) {
                            parseItems(note.body).filter { !it.checked && it.text.isNotBlank() }
                                .joinToString("\n") { it.text }
                        } else {
                            note.body
                        }
                        Reminders.show(app, id, note.title.ifBlank { "Reminder" }, text.take(400))
                        dao.clearReminder(id)
                    }
                } else if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    Reminders.rescheduleAll(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
