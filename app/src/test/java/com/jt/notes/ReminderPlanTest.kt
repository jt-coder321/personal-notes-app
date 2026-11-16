package com.jt.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderPlanTest {
    private val min = 60_000L

    @Test fun singlePingStopsAfterFiring() {
        val s = ReminderPlan.afterFire(pings = 1, gapMin = 5, done = 0, now = 1000)
        assertNull(s.nextAt)
        assertEquals(0, s.done)
    }

    @Test fun threePingsRepeatThenStop() {
        val first = ReminderPlan.afterFire(3, 5, 0, 0)
        assertEquals(5 * min, first.nextAt)
        assertEquals(1, first.done)
        val second = ReminderPlan.afterFire(3, 5, first.done, 5 * min)
        assertEquals(10 * min, second.nextAt)
        assertEquals(2, second.done)
        val third = ReminderPlan.afterFire(3, 5, second.done, 10 * min)
        assertNull(third.nextAt)
        assertEquals(0, third.done)
    }

    @Test fun snoozeGivesExactlyOneMorePing() {
        val snoozed = ReminderPlan.snooze(pings = 3, now = 0, minutes = 10)
        assertEquals(10 * min, snoozed.nextAt)
        val fired = ReminderPlan.afterFire(3, 5, snoozed.done, 10 * min)
        assertNull(fired.nextAt)
    }

    @Test fun snoozeWithSinglePingAlsoStopsAfterOne() {
        val snoozed = ReminderPlan.snooze(pings = 1, now = 0, minutes = 10)
        assertNull(ReminderPlan.afterFire(1, 5, snoozed.done, 10 * min).nextAt)
    }

    @Test fun gapNeverBelowOneMinute() {
        val s = ReminderPlan.afterFire(2, 0, 0, 0)
        assertEquals(1 * min, s.nextAt)
    }
}
