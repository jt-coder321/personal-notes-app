package com.jt.notes

data class PingState(val nextAt: Long?, val done: Int)

/** The pure rules for repeating pings, kept free of Android so they can be unit tested. */
object ReminderPlan {
    /** What to store after a ping has just fired. */
    fun afterFire(pings: Int, gapMin: Int, done: Int, now: Long): PingState {
        val sent = done + 1
        return if (sent < pings) PingState(now + gapMin.coerceAtLeast(1) * 60_000L, sent) else PingState(null, 0)
    }

    /** Snoozing buys exactly one more ping. */
    fun snooze(pings: Int, now: Long, minutes: Int): PingState =
        PingState(now + minutes * 60_000L, (pings - 1).coerceAtLeast(0))
}
