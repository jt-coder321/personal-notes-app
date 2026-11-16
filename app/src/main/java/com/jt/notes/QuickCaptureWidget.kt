package com.jt.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** Home-screen widget: one tap to start a note or a checklist. At narrow widths only the note action shows. */
class QuickCaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val options = try {
                manager.getAppWidgetOptions(id)
            } catch (e: Exception) {
                null
            }
            manager.updateAppWidget(id, viewsFor(context, options))
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle?) {
        super.onAppWidgetOptionsChanged(context, manager, id, newOptions)
        manager.updateAppWidget(id, viewsFor(context, newOptions))
    }

    private fun viewsFor(context: Context, options: Bundle?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
        views.setOnClickPendingIntent(R.id.widget_note, launch(context, MainActivity.ACTION_NEW_NOTE, 1))
        views.setOnClickPendingIntent(R.id.widget_add, launch(context, MainActivity.ACTION_NEW_NOTE, 1))
        views.setOnClickPendingIntent(R.id.widget_checklist, launch(context, MainActivity.ACTION_NEW_CHECKLIST, 2))
        views.setViewVisibility(
            R.id.widget_checklist,
            if (WidgetSizing.isNarrow(options)) View.GONE else View.VISIBLE,
        )
        return views
    }

    private fun launch(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
