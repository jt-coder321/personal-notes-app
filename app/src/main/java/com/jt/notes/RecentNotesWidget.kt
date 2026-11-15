package com.jt.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.jt.notes.data.NotesDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen widget listing pinned and recent notes. Tap one to open it. */
class RecentNotesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val notes = NotesDb.get(context).dao().allNotes()
                    .filter { it.trashedAt == null && !it.archived }
                    .sortedWith(compareByDescending<com.jt.notes.data.Note> { it.pinned }.thenByDescending { it.updatedAt })
                    .take(ROWS.size)
                for (id in ids) {
                    val views = RemoteViews(context.packageName, R.layout.widget_recent_notes)
                    views.setOnClickPendingIntent(R.id.recent_header, open(context, Intent(context, MainActivity::class.java), 0))
                    views.setOnClickPendingIntent(R.id.recent_add, open(context, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_NOTE), 1))
                    views.setViewVisibility(R.id.recent_empty, if (notes.isEmpty()) View.VISIBLE else View.GONE)
                    ROWS.forEachIndexed { i, rowId ->
                        val note = notes.getOrNull(i)
                        if (note == null) {
                            views.setViewVisibility(rowId, View.GONE)
                        } else {
                            val firstLine = note.body.lineSequence().firstOrNull { it.isNotBlank() }
                                ?.removePrefix("[ ] ")?.removePrefix("[x] ")?.trim()
                            views.setViewVisibility(rowId, View.VISIBLE)
                            views.setTextViewText(rowId, (if (note.pinned) "\uD83D\uDCCC " else "") + note.title.ifBlank { firstLine ?: "Empty note" })
                            val intent = Intent(context, MainActivity::class.java)
                                .setAction(MainActivity.ACTION_OPEN_NOTE)
                                .putExtra(Reminders.EXTRA_NOTE_ID, note.id)
                                .setData(Uri.parse("notes://note/${note.id}"))
                            views.setOnClickPendingIntent(rowId, open(context, intent, 10 + i))
                        }
                    }
                    manager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun open(context: Context, intent: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private val ROWS = listOf(R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4)

        /** Asks the launcher to redraw every placed copy of this widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecentNotesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, RecentNotesWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
