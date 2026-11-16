package com.jt.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.jt.notes.data.Note
import com.jt.notes.data.NotesDb
import com.jt.notes.data.itemsToBody
import com.jt.notes.data.parseItems
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Home-screen widget showing one checklist. Tapping an item checks it off without opening the app. */
class ChecklistWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val note = pickNote(context)
                for (id in ids) {
                    val options = try {
                        manager.getAppWidgetOptions(id)
                    } catch (e: Exception) {
                        null
                    }
                    manager.updateAppWidget(id, viewsFor(context, note, options))
                }
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
                manager.updateAppWidget(id, viewsFor(context, pickNote(context), newOptions))
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ROWS = listOf(
            R.id.check_1, R.id.check_2, R.id.check_3,
            R.id.check_4, R.id.check_5, R.id.check_6,
        )
        const val ACTION_TOGGLE = "com.jt.notes.CHECKLIST_TOGGLE"
        const val EXTRA_ITEM = "item"

        /** The checklist on show: a pinned one first, otherwise the most recently edited. Locked notes stay off the widget. */
        private suspend fun pickNote(context: Context): Note? =
            NotesDb.get(context).dao().allNotes()
                .filter { it.isChecklist && it.trashedAt == null && !it.archived && !it.locked }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
                .firstOrNull()

        private fun viewsFor(context: Context, note: Note?, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_checklist)
            views.setOnClickPendingIntent(
                R.id.check_header,
                openApp(context, Intent(context, MainActivity::class.java), 0),
            )
            views.setOnClickPendingIntent(
                R.id.check_add,
                openApp(context, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_CHECKLIST), 1),
            )
            if (note == null) {
                views.setTextViewText(R.id.check_title, "Checklist")
                views.setViewVisibility(R.id.check_empty, View.VISIBLE)
                views.setViewVisibility(R.id.check_more, View.GONE)
                ROWS.forEach { views.setViewVisibility(it, View.GONE) }
                views.setOnClickPendingIntent(
                    R.id.check_empty,
                    openApp(context, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_CHECKLIST), 2),
                )
            } else {
                views.setViewVisibility(R.id.check_empty, View.GONE)
                views.setTextViewText(R.id.check_title, note.title.ifBlank { "Checklist" })
                // Keep each item's position in the full list: the toggle receiver
                // flips the item at that position, blanks included.
                val indexed = parseItems(note.body).mapIndexed { index, item -> index to item }
                    .filter { it.second.text.isNotBlank() }
                val shown = indexed.take(WidgetSizing.rowsForHeight(options, ROWS.size))
                ROWS.forEachIndexed { i, rowId ->
                    val entry = shown.getOrNull(i)
                    if (entry == null) {
                        views.setViewVisibility(rowId, View.GONE)
                    } else {
                        val (index, item) = entry
                        views.setViewVisibility(rowId, View.VISIBLE)
                        views.setTextViewText(rowId, (if (item.checked) "\u2611 " else "\u2610 ") + item.text)
                        views.setOnClickPendingIntent(rowId, toggle(context, note.id, index, 20 + i))
                    }
                }
                val extra = indexed.size - shown.size
                if (extra > 0) {
                    views.setViewVisibility(R.id.check_more, View.VISIBLE)
                    views.setTextViewText(R.id.check_more, "+$extra more")
                    val openNote = Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_OPEN_NOTE)
                        .putExtra(Reminders.EXTRA_NOTE_ID, note.id)
                        .setData(Uri.parse("notes://note/${note.id}"))
                    views.setOnClickPendingIntent(R.id.check_more, openApp(context, openNote, 3))
                } else {
                    views.setViewVisibility(R.id.check_more, View.GONE)
                }
            }
            return views
        }

        private fun openApp(context: Context, intent: Intent, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                code,
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        private fun toggle(context: Context, noteId: Long, index: Int, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                code,
                Intent(context, ChecklistToggleReceiver::class.java)
                    .setAction(ACTION_TOGGLE)
                    .putExtra(Reminders.EXTRA_NOTE_ID, noteId)
                    .putExtra(EXTRA_ITEM, index)
                    .setData(Uri.parse("notes://check/$noteId/$index")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /** Asks the launcher to redraw every placed copy of this widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ChecklistWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ChecklistWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}

/** Toggles one checklist item from the widget, then redraws the widgets. */
class ChecklistToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val id = intent.getLongExtra(Reminders.EXTRA_NOTE_ID, -1)
                val index = intent.getIntExtra(ChecklistWidget.EXTRA_ITEM, -1)
                val dao = NotesDb.get(app).dao()
                val note = dao.getNote(id)
                if (note != null && note.isChecklist) {
                    val items = parseItems(note.body).toMutableList()
                    if (index in items.indices) {
                        items[index] = items[index].copy(checked = !items[index].checked)
                        dao.updateNote(note.copy(body = itemsToBody(items), updatedAt = System.currentTimeMillis()))
                    }
                }
                ChecklistWidget.refresh(app)
                RecentNotesWidget.refresh(app)
                LargeRecentNotesWidget.refresh(app)
                CompactNotesWidget.refresh(app)
            } finally {
                pending.finish()
            }
        }
    }
}
