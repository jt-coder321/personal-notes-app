package com.jt.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.jt.notes.data.Note
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
                updateInstances(context, manager, ids, R.layout.widget_recent_notes, ROWS)
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateInstances(context, manager, intArrayOf(id), R.layout.widget_recent_notes, ROWS)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ROWS = listOf(R.id.recent_1, R.id.recent_2, R.id.recent_3, R.id.recent_4)

        /** Pinned notes first, then most recently edited, skipping trash and archive. */
        internal suspend fun loadNotes(context: Context, max: Int): List<Note> =
            NotesDb.get(context).dao().allNotes()
                .filter { it.trashedAt == null && !it.archived }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
                .take(max)

        /** The one line a note shows as: its title, or its first line when untitled. */
        internal fun noteTitle(note: Note): String {
            val firstLine = note.body.lineSequence().firstOrNull { it.isNotBlank() }
                ?.removePrefix("[ ] ")?.removePrefix("[x] ")?.trim()
            return (if (note.pinned) "\uD83D\uDCCC " else "") + note.title.ifBlank { firstLine ?: "Empty note" }
        }

        internal fun openNoteIntent(context: Context, note: Note): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_NOTE)
                .putExtra(Reminders.EXTRA_NOTE_ID, note.id)
                .setData(Uri.parse("notes://note/${note.id}"))

        internal fun open(context: Context, intent: Intent, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Redraws the given instances, showing only as many rows as each one's height fits. */
        internal suspend fun updateInstances(
            context: Context,
            manager: AppWidgetManager,
            ids: IntArray,
            layoutRes: Int,
            rows: List<Int>,
        ) {
            val notes = loadNotes(context, rows.size)
            for (id in ids) {
                val options = try {
                    manager.getAppWidgetOptions(id)
                } catch (e: Exception) {
                    null
                }
                manager.updateAppWidget(id, viewsFor(context, notes, options, layoutRes, rows))
            }
        }

        internal fun viewsFor(
            context: Context,
            notes: List<Note>,
            options: Bundle?,
            layoutRes: Int,
            rows: List<Int>,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, layoutRes)
            views.setOnClickPendingIntent(R.id.recent_header, open(context, Intent(context, MainActivity::class.java), 0))
            views.setOnClickPendingIntent(R.id.recent_add, open(context, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_NOTE), 1))
            views.setViewVisibility(R.id.recent_empty, if (notes.isEmpty()) View.VISIBLE else View.GONE)
            val visible = WidgetSizing.rowsForHeight(options, rows.size)
            rows.forEachIndexed { i, rowId ->
                val note = if (i < visible) notes.getOrNull(i) else null
                if (note == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(rowId, noteTitle(note))
                    views.setOnClickPendingIntent(rowId, open(context, openNoteIntent(context, note), 10 + i))
                }
            }
            return views
        }

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
