package com.jt.notes

import android.appwidget.AppWidgetManager
import android.os.Bundle

/**
 * Reads a widget's current size from its options and turns it into row counts,
 * so every widget adapts its content when it is resized instead of stretching.
 * Sizes come from the portrait (minimum) bounds, which is the size the widget
 * actually has when the phone is held upright. An unknown size shows everything.
 */
object WidgetSizing {
    /** Height of one list row, and of the header row, in the widget layouts. */
    private const val ROW_DP = 40
    private const val HEADER_DP = 40

    /** Vertical padding around the list layouts (14dp top and bottom). */
    private const val PADDING_DP = 28

    /** Below this width the quick-capture widget drops its secondary button. */
    private const val NARROW_WIDTH_DP = 200

    /** The widget's height in dp, or 0 when unknown. */
    fun heightDp(options: Bundle?): Int =
        options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0

    /** The widget's width in dp, or 0 when unknown. */
    fun widthDp(options: Bundle?): Int =
        options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0

    /** How many rows fit at this height, given a header row. Always at least 1. */
    fun rowsForHeight(options: Bundle?, maxRows: Int): Int {
        val height = heightDp(options)
        if (height <= 0) return maxRows
        val rows = (height - PADDING_DP - HEADER_DP + ROW_DP - 1) / ROW_DP
        return rows.coerceIn(1, maxRows)
    }

    /** How many rows fit at this height when there is no header row. Always at least 1. */
    fun rowsForHeightNoHeader(options: Bundle?, maxRows: Int): Int {
        val height = heightDp(options)
        if (height <= 0) return maxRows
        val rows = (height - PADDING_DP + ROW_DP - 1) / ROW_DP
        return rows.coerceIn(1, maxRows)
    }

    /** True when the widget is too narrow for its secondary buttons. */
    fun isNarrow(options: Bundle?): Boolean {
        val width = widthDp(options)
        return width > 0 && width < NARROW_WIDTH_DP
    }
}
