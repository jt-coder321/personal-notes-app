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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The compact notes widget. At its smallest it is one row: the most recent
 * note's title (tap to open it) plus a new-note button. Made taller, it grows
 * into more recent-note rows.
 */
class CompactNotesWidget : AppWidgetProvider() {
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

    companion object {
        private val EXTRA_ROWS = listOf(
            R.id.compact_1, R.id.compact_2, R.id.compact_3, R.id.compact_4, R.id.compact_5,
        )
        private const val MAX_NOTES = 6

        internal suspend fun updateInstances(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val notes = RecentNotesWidget.loadNotes(context, MAX_NOTES)
            for (id in ids) {
                val options = try {
                    manager.getAppWidgetOptions(id)
                } catch (e: Exception) {
                    null
                }
                manager.updateAppWidget(id, viewsFor(context, notes, options))
            }
        }

        private fun viewsFor(context: Context, notes: List<Note>, options: Bundle?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_notes_compact)
            val first = notes.firstOrNull()
            if (first == null) {
                views.setTextViewText(R.id.compact_first, context.getString(R.string.widget_hint))
                views.setOnClickPendingIntent(
                    R.id.compact_first,
                    RecentNotesWidget.open(
                        context,
                        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_NOTE),
                        1,
                    ),
                )
            } else {
                views.setTextViewText(R.id.compact_first, RecentNotesWidget.noteTitle(first))
                views.setOnClickPendingIntent(
                    R.id.compact_first,
                    RecentNotesWidget.open(context, RecentNotesWidget.openNoteIntent(context, first), 10),
                )
            }
            views.setOnClickPendingIntent(
                R.id.compact_add,
                RecentNotesWidget.open(
                    context,
                    Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_NEW_NOTE),
                    1,
                ),
            )
            val visible = WidgetSizing.rowsForHeightNoHeader(options, MAX_NOTES)
            EXTRA_ROWS.forEachIndexed { i, rowId ->
                val note = if (i + 1 < visible) notes.getOrNull(i + 1) else null
                if (note == null) {
                    views.setViewVisibility(rowId, View.GONE)
                } else {
                    views.setViewVisibility(rowId, View.VISIBLE)
                    views.setTextViewText(rowId, RecentNotesWidget.noteTitle(note))
                    views.setOnClickPendingIntent(
                        rowId,
                        RecentNotesWidget.open(context, RecentNotesWidget.openNoteIntent(context, note), 11 + i),
                    )
                }
            }
            return views
        }

        /** Asks the launcher to redraw every placed copy of this widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CompactNotesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, CompactNotesWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
