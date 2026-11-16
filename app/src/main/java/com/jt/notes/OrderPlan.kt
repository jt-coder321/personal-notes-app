package com.jt.notes

/** The pure rule for saving a dragged order, kept free of Android so it can be unit tested. */
object OrderPlan {
    /**
     * Takes the sort values the notes have now and hands them back biggest first,
     * so the first note in the new order gets the biggest value. Ties are broken
     * so every note ends up strictly below the one before it.
     */
    fun assign(currentValues: List<Long>): List<Long> {
        val out = ArrayList<Long>(currentValues.size)
        for (v in currentValues.sortedDescending()) {
            out.add(if (out.isEmpty()) v else minOf(v, out.last() - 1))
        }
        return out
    }
}
