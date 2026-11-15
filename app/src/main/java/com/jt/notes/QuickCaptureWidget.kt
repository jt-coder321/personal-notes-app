package com.jt.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home-screen widget: one tap to start a note or a checklist. */
class QuickCaptureWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_capture)
            views.setOnClickPendingIntent(R.id.widget_note, launch(context, MainActivity.ACTION_NEW_NOTE, 1))
            views.setOnClickPendingIntent(R.id.widget_add, launch(context, MainActivity.ACTION_NEW_NOTE, 1))
            views.setOnClickPendingIntent(R.id.widget_checklist, launch(context, MainActivity.ACTION_NEW_CHECKLIST, 2))
            manager.updateAppWidget(id, views)
        }
    }

    private fun launch(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
