package com.jt.notes

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.jt.notes.data.Note
import com.jt.notes.data.NoteExport
import com.jt.notes.data.NotesDb
import com.jt.notes.data.parseItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen widget showing one chosen note: its title plus the first lines. Tap it to open it. */
class SingleNoteWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                updateInstances(context, manager, ids)
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
                updateInstances(context, manager, intArrayOf(id))
            } finally {
                pending.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        // Deleting the widget drops its saved note choice.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply { appWidgetIds.forEach { remove(KEY_PREFIX + it) } }.apply()
    }

    companion object {
        private const val PREFS = "single_note_widget"
        private const val KEY_PREFIX = "note_"
        private val ROWS = listOf(R.id.single_line_1, R.id.single_line_2, R.id.single_line_3, R.id.single_line_4)

        /** Remembers which note a widget instance shows. */
        fun saveNoteId(context: Context, appWidgetId: Int, noteId: Long) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_PREFIX + appWidgetId, noteId).apply()
        }

        private fun loadNoteId(context: Context, appWidgetId: Int): Long =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_PREFIX + appWidgetId, -1L)

        internal suspend fun updateInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val dao = NotesDb.get(context).dao()
            for (id in ids) {
                val noteId = loadNoteId(context, id)
                val note = if (noteId > 0) dao.getNote(noteId) else null
                val options = try {
                    manager.getAppWidgetOptions(id)
                } catch (e: Exception) {
                    null
                }
                manager.updateAppWidget(id, viewsFor(context, note, options))
            }
        }

        private fun bodyLines(note: Note): List<String> =
            if (note.isChecklist) {
                parseItems(note.body).filter { it.text.isNotBlank() }
                    .map { (if (it.checked) "\u2611 " else "\u2610 ") + it.text }
            } else {
                NoteExport.plainFromMarkup(note.body).lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
            }

        internal fun viewsFor(context: Context, note: Note?, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_note_single)
            val openIntent = if (note != null && note.trashedAt == null) {
                RecentNotesWidget.openNoteIntent(context, note)
            } else {
                Intent(context, MainActivity::class.java)
            }
            views.setOnClickPendingIntent(R.id.single_root, RecentNotesWidget.open(context, openIntent, 0))
            if (note == null || note.trashedAt != null) {
                views.setTextViewText(R.id.single_title, "Note unavailable")
                ROWS.forEach { views.setViewVisibility(it, View.GONE) }
            } else if (note.locked) {
                // A locked note shows its title only, like its card; the editor asks for proof on open.
                views.setTextViewText(R.id.single_title, note.title.ifBlank { "Locked note" })
                ROWS.forEach { views.setViewVisibility(it, View.GONE) }
            } else {
                val title = note.title.ifBlank {
                    if (note.isChecklist) "Checklist" else bodyLines(note).firstOrNull() ?: "Empty note"
                }
                views.setTextViewText(R.id.single_title, title)
                val lines = bodyLines(note)
                // One row is the title; the rest are body lines, as many as the height fits.
                val visible = (WidgetSizing.rowsForHeightNoHeader(options, ROWS.size + 1) - 1)
                    .coerceIn(0, ROWS.size)
                ROWS.forEachIndexed { i, rowId ->
                    val line = if (i < visible) lines.getOrNull(i) else null
                    if (line == null) {
                        views.setViewVisibility(rowId, View.GONE)
                    } else {
                        views.setViewVisibility(rowId, View.VISIBLE)
                        views.setTextViewText(rowId, line)
                    }
                }
            }
            return views
        }

        /** Asks the launcher to redraw every placed copy of this widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SingleNoteWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, SingleNoteWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
