package com.jt.notes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.provider.Settings
import com.jt.notes.data.Note
import com.jt.notes.data.NotesDb
import com.jt.notes.data.parseItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Note reminders. They use exact alarms, can ping several times, and can
 * sound like an alarm. Snooze and Done are buttons on the notification.
 */
object Reminders {
    const val ACTION_REMIND = "com.jt.notes.REMIND"
    const val ACTION_DONE = "com.jt.notes.REMIND_DONE"
    const val ACTION_SNOOZE = "com.jt.notes.REMIND_SNOOZE"
    const val EXTRA_NOTE_ID = "noteId"
    const val EXTRA_ACK = "ack"
    const val SNOOZE_MIN = 10
    private const val CHANNEL = "reminders"
    private const val CHANNEL_ALARM = "reminders_alarm"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        )
        val alarm = NotificationChannel(CHANNEL_ALARM, "Alarm reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        }
        manager.createNotificationChannel(alarm)
    }

    private fun alarmIntent(context: Context, noteId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMIND).putExtra(EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun actionIntent(context: Context, noteId: Long, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            noteId.toInt(),
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_NOTE_ID, noteId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun schedule(context: Context, noteId: Long, at: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, noteId)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(context: Context, noteId: Long) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context, noteId))
    }

    fun dismiss(context: Context, noteId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(noteId.toInt())
    }

    /** Sets every pending reminder again, for example after the phone restarts. */
    suspend fun rescheduleAll(context: Context) {
        NotesDb.get(context).dao().notesWithReminders().forEach { note ->
            note.reminderAt?.let { schedule(context, note.id, it) }
        }
    }

    fun show(context: Context, note: Note, text: String) {
        ensureChannel(context)
        val noteId = note.id
        val open = PendingIntent.getActivity(
            context,
            noteId.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(EXTRA_NOTE_ID, noteId)
                .putExtra(EXTRA_ACK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = Icon.createWithResource(context, R.drawable.ic_stat_note)
        val builder = Notification.Builder(context, if (note.remindAlarm) CHANNEL_ALARM else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(note.title.ifBlank { "Reminder" })
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(icon, "Snooze $SNOOZE_MIN min", actionIntent(context, noteId, ACTION_SNOOZE)).build())
            .addAction(Notification.Action.Builder(icon, "Done", actionIntent(context, noteId, ACTION_DONE)).build())
        if (note.remindAlarm) builder.setCategory(Notification.CATEGORY_ALARM)
        val notification = builder.build()
        // An alarm keeps ringing until you tap it, Snooze or Done.
        if (note.remindAlarm) notification.flags = notification.flags or Notification.FLAG_INSISTENT
        context.getSystemService(NotificationManager::class.java).notify(noteId.toInt(), notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1)
                val dao = NotesDb.get(app).dao()
                when (intent.action) {
                    Reminders.ACTION_REMIND -> {
                        val note = dao.getNote(id)
                        if (note != null && note.trashedAt == null && note.reminderAt != null) {
                            val text = if (note.isChecklist) {
                                parseItems(note.body).filter { !it.checked && it.text.isNotBlank() }
                                    .joinToString("\n") { it.text }
                            } else {
                                note.body
                            }
                            Reminders.show(app, note, text.take(400))
                            val now = System.currentTimeMillis()
                            val next = ReminderPlan.afterFire(note.remindPings, note.remindGapMin, note.remindDone, now)
                            dao.setReminderState(id, next.nextAt, next.done)
                            next.nextAt?.let { Reminders.schedule(app, id, it) }
                        }
                    }
                    Reminders.ACTION_DONE -> {
                        Reminders.cancel(app, id)
                        dao.setReminderState(id, null, 0)
                        Reminders.dismiss(app, id)
                    }
                    Reminders.ACTION_SNOOZE -> {
                        val note = dao.getNote(id)
                        if (note != null) {
                            val next = ReminderPlan.snooze(note.remindPings, System.currentTimeMillis(), Reminders.SNOOZE_MIN)
                            dao.setReminderState(id, next.nextAt, next.done)
                            next.nextAt?.let { Reminders.schedule(app, id, it) }
                        }
                        Reminders.dismiss(app, id)
                    }
                    Intent.ACTION_BOOT_COMPLETED -> Reminders.rescheduleAll(app)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
