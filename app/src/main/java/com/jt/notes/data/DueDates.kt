package com.jt.notes.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jt.notes.MainActivity
import com.jt.notes.R
import com.jt.notes.Reminders
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Formats an ISO due date ("2026-11-20") as "Nov 20". Falls back to the raw text. */
fun formatDue(due: String): String = runCatching {
    LocalDate.parse(due).format(DateTimeFormatter.ofPattern("MMM d"))
}.getOrDefault(due)

fun isDueToday(due: String): Boolean =
    runCatching { LocalDate.parse(due) == LocalDate.now() }.getOrDefault(false)

fun isOverdue(due: String): Boolean =
    runCatching { LocalDate.parse(due) < LocalDate.now() }.getOrDefault(false)

/** Schedules the once-a-day check for checklist items due today. */
fun scheduleDueDateWorker(context: Context) {
    val request = PeriodicWorkRequestBuilder<DueDateWorker>(1, TimeUnit.DAYS).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "due_date_pings",
        ExistingPeriodicWorkPolicy.KEEP,
        request,
    )
}

/**
 * Pings once per day for each unchecked item due today. Each item pings at
 * most once per day: the last-pinged date is kept in SharedPreferences,
 * keyed by note id, item text hash, and due date. The notification is
 * tap-to-open only; it deliberately does not reuse Reminders.show, whose
 * Snooze/Done actions would mutate reminder state on notes that have none.
 */
class DueDateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val dao = NotesDb.get(applicationContext).dao()
        val today = LocalDate.now().toString()
        val prefs = applicationContext.getSharedPreferences("due_pings", Context.MODE_PRIVATE)
        dao.allNotes()
            .filter { it.isChecklist && it.trashedAt == null }
            .forEach { note ->
                parseItems(note.body)
                    .filter { !it.checked && it.due == today }
                    .forEach { item ->
                        val key = "ping:${note.id}:${item.text.hashCode()}:$today"
                        if (prefs.getString(key, null) != today) {
                            ping(note, item.text)
                            prefs.edit().putString(key, today).apply()
                        }
                    }
            }
        return Result.success()
    }

    private fun ping(note: Note, itemText: String) {
        val context = applicationContext
        Reminders.ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOTE)
            .putExtra("noteId", note.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, ("due:${note.id}:$itemText").hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, Reminders.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(note.title.ifBlank { "Checklist" })
            .setContentText("Due today: $itemText")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(("due:${note.id}:$itemText").hashCode(), notification)
    }
}
