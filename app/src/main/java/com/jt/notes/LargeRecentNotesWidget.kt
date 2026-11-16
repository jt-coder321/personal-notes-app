package com.jt.notes

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The large recent-notes widget: up to 8 rows, shrinking to 1 row at its smallest size. */
class LargeRecentNotesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                RecentNotesWidget.updateInstances(context, manager, ids, R.layout.widget_recent_notes_large, ROWS)
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
                RecentNotesWidget.updateInstances(
                    context, manager, intArrayOf(id), R.layout.widget_recent_notes_large, ROWS,
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val ROWS = listOf(
            R.id.recent_big_1, R.id.recent_big_2, R.id.recent_big_3, R.id.recent_big_4,
            R.id.recent_big_5, R.id.recent_big_6, R.id.recent_big_7, R.id.recent_big_8,
        )

        /** Asks the launcher to redraw every placed copy of this widget. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LargeRecentNotesWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, LargeRecentNotesWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
