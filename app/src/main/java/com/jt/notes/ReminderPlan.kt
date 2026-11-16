package com.jt.notes

import java.util.Calendar

data class PingState(val nextAt: Long?, val done: Int)

/** The pure rules for repeating pings, kept free of Android so they can be unit tested. */
object ReminderPlan {
    const val REPEAT_NONE = 0
    const val REPEAT_DAILY = 1
    const val REPEAT_WEEKLY = 2

    /** What to store after a ping has just fired. */
    fun afterFire(pings: Int, gapMin: Int, done: Int, now: Long): PingState {
        val sent = done + 1
        return if (sent < pings) PingState(now + gapMin.coerceAtLeast(1) * 60_000L, sent) else PingState(null, 0)
    }

    /** Snoozing buys exactly one more ping. */
    fun snooze(pings: Int, now: Long, minutes: Int): PingState =
        PingState(now + minutes * 60_000L, (pings - 1).coerceAtLeast(0))

    /**
     * The next occurrence of a repeating reminder: the same time of day, one day
     * or one week after [anchor]. Steps forward until it is in the future, so a
     * phone that was off for days catches up without a burst of stale pings.
     * Returns null when the reminder does not repeat.
     */
    fun nextOccurrence(anchor: Long, repeat: Int, now: Long): Long? {
        val stepDays = when (repeat) {
            REPEAT_DAILY -> 1
            REPEAT_WEEKLY -> 7
            else -> return null
        }
        val calendar = Calendar.getInstance().apply { timeInMillis = anchor }
        var next = anchor
        var steps = 0
        while (next <= now && steps < 1000) {
            calendar.add(Calendar.DAY_OF_YEAR, stepDays)
            next = calendar.timeInMillis
            steps++
        }
        return next
    }

    /** Short label for the repeat setting, for the reminders list and the editor. */
    fun repeatLabel(repeat: Int): String = when (repeat) {
        REPEAT_DAILY -> "Daily"
        REPEAT_WEEKLY -> "Weekly"
        else -> "Once"
    }
}
