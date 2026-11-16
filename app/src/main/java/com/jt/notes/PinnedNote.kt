package com.jt.notes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.NotesDb
import com.jt.notes.data.checklistBodyToText

/**
 * One pinned note, shown as an ongoing notification. Only one note can be
 * pinned at a time; pinning another replaces it. Locked notes cannot be
 * pinned, and locking a pinned note removes the pin. The pin re-posts
 * after a reboot through the boot receiver. A dedicated low-importance
 * channel keeps the pin silent: the reminders channel is high importance
 * and would buzz on every post.
 */
object PinnedNote {
    const val CHANNEL_ID = "pinned_note"
    private const val NOTIFICATION_ID = 987654321
    private const val ACTION_UNPIN = "com.jt.notes.UNPIN_NOTE"
    private const val PREF_PINNED_ID = "pinned_note_id"

    fun pinnedId(context: Context): Long =
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).getLong(PREF_PINNED_ID, -1L)

    fun isPinned(context: Context, noteId: Long): Boolean = pinnedId(context) == noteId

    /** Pins the note and posts the notification. Refuses locked notes. */
    fun pin(context: Context, note: Note) {
        if (note.locked) return
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putLong(PREF_PINNED_ID, note.id).apply()
        post(context, note)
    }

    fun unpin(context: Context) {
        context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().remove(PREF_PINNED_ID).apply()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Removes the pin when the pinned note is locked or trashed. */
    fun unpinIfPinned(context: Context, noteId: Long) {
        if (pinnedId(context) == noteId) unpin(context)
    }

    /** Re-posts the pinned note after a reboot. Runs on a background thread. */
    suspend fun restoreAfterBoot(context: Context) {
        val id = pinnedId(context)
        if (id <= 0L) return
        val note = NotesDb.get(context).dao().getNote(id) ?: return
        if (note.locked || note.trashedAt != null) {
            unpin(context)
            return
        }
        post(context, note)
    }

    /** Re-posts with the latest text when the editor closes. */
    fun refreshIfPinned(context: Context, note: Note) {
        if (isPinned(context, note.id) && !note.locked && note.trashedAt == null) post(context, note)
    }

    fun post(context: Context, note: Note) {
        ensureChannel(context)
        val body = if (note.isChecklist) checklistBodyToText(note.body) else NoteExport.plainFromMarkup(note.body)
        val preview = body.lines().filter { it.isNotBlank() }.take(3).joinToString("\n")
        val openIntent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_NOTE)
            .putExtra("noteId", note.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val unpinIntent = Intent(context, PinnedNoteReceiver::class.java).setAction(ACTION_UNPIN)
        val unpinPending = PendingIntent.getBroadcast(
            context, 0, unpinIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_note)
            .setContentTitle(note.title.ifBlank { "Note" })
            .setContentText(preview.ifBlank { "No text yet" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentIntent)
            .addAction(0, "Unpin", unpinPending)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Pinned note", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "The note pinned to the notification shade" },
            )
        }
    }
}

/** Handles the Unpin action on the pinned-note notification. */
class PinnedNoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PinnedNote.unpin(context)
    }
}
