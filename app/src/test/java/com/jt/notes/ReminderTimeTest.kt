package com.jt.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class ReminderTimeTest {
    private val zone = ZoneId.of("UTC")

    /** Wednesday, November 18, 2026, 10:00 UTC. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private val now = at(2026, 11, 18, 10, 0)

    @Test fun inMinutesAddsToNow() {
        assertEquals(now + 30 * 60_000L, ReminderTime.parse("in 30 minutes", now, zone))
    }

    @Test fun inHoursAndDays() {
        assertEquals(now + 2 * 3_600_000L, ReminderTime.parse("in 2 hours", now, zone))
        assertEquals(at(2026, 11, 21, 10, 0), ReminderTime.parse("in 3 days", now, zone))
    }

    @Test fun singularAndShortUnits() {
        assertEquals(now + 60_000L, ReminderTime.parse("in 1 minute", now, zone))
        assertEquals(now + 60_000L, ReminderTime.parse("in 1 min", now, zone))
        assertEquals(now + 3_600_000L, ReminderTime.parse("in an hour", now, zone))
        assertEquals(at(2026, 11, 19, 10, 0), ReminderTime.parse("in a day", now, zone))
    }

    @Test fun todayAndTomorrow() {
        assertEquals(at(2026, 11, 18, 15, 0), ReminderTime.parse("today 3pm", now, zone))
        assertEquals(at(2026, 11, 19, 9, 0), ReminderTime.parse("tomorrow 9am", now, zone))
        assertEquals(at(2026, 11, 19, 9, 0), ReminderTime.parse("tomorrow at 9", now, zone))
        assertEquals(at(2026, 11, 19, 9, 0), ReminderTime.parse("tomorrow", now, zone))
    }

    @Test fun todayWithPastTimeIsNull() {
        assertNull(ReminderTime.parse("today 9am", now, zone))
    }

    @Test fun weekdayMeansNextOccurrence() {
        assertEquals(at(2026, 11, 20, 10, 0), ReminderTime.parse("friday 10am", now, zone))
        assertEquals(at(2026, 11, 20, 10, 0), ReminderTime.parse("fri 10am", now, zone))
        assertEquals(at(2026, 11, 20, 9, 0), ReminderTime.parse("friday", now, zone))
    }

    @Test fun nextWeekdayMeansFollowingWeek() {
        assertEquals(at(2026, 11, 27, 10, 0), ReminderTime.parse("next friday 10am", now, zone))
    }

    @Test fun weekdayTodayRollsForwardWhenTimePassed() {
        assertEquals(at(2026, 11, 18, 11, 0), ReminderTime.parse("wednesday 11am", now, zone))
        assertEquals(at(2026, 11, 25, 9, 0), ReminderTime.parse("wednesday 9am", now, zone))
        assertEquals(at(2026, 11, 25, 9, 0), ReminderTime.parse("next wednesday 9am", now, zone))
    }

    @Test fun bareTimeTodayOrTomorrow() {
        assertEquals(at(2026, 11, 18, 11, 0), ReminderTime.parse("11am", now, zone))
        assertEquals(at(2026, 11, 19, 9, 0), ReminderTime.parse("9am", now, zone))
        assertEquals(at(2026, 11, 18, 21, 30), ReminderTime.parse("9:30pm", now, zone))
        assertEquals(at(2026, 11, 18, 21, 30), ReminderTime.parse("21:30", now, zone))
        assertEquals(at(2026, 11, 18, 12, 0), ReminderTime.parse("12pm", now, zone))
        assertEquals(at(2026, 11, 19, 0, 0), ReminderTime.parse("12am", now, zone))
    }

    @Test fun caseAndSpacingDoNotMatter() {
        assertEquals(at(2026, 11, 19, 9, 0), ReminderTime.parse("  Tomorrow   9AM ", now, zone))
        assertEquals(at(2026, 11, 20, 10, 0), ReminderTime.parse("FRIDAY 10am", now, zone))
    }

    @Test fun invalidInputIsNull() {
        assertNull(ReminderTime.parse("", now, zone))
        assertNull(ReminderTime.parse("gibberish", now, zone))
        assertNull(ReminderTime.parse("tomorrow 25:00", now, zone))
        assertNull(ReminderTime.parse("13pm", now, zone))
        assertNull(ReminderTime.parse("9:60", now, zone))
        assertNull(ReminderTime.parse("next tomorrow", now, zone))
        assertNull(ReminderTime.parse("yesterday 9am", now, zone))
        assertNull(ReminderTime.parse("in five minutes", now, zone))
        assertNull(ReminderTime.parse("in 0 minutes", now, zone))
        assertNull(ReminderTime.parse("remind me tomorrow 9am", now, zone))
    }

    @Test fun formatShowsWeekdayAndTime() {
        val la = ZoneId.of("America/Los_Angeles")
        val at = ZonedDateTime.of(2026, 11, 17, 9, 0, 0, 0, la).toInstant().toEpochMilli()
        assertEquals("Tue, Nov 17, 9:00 AM", ReminderTime.format(at, la, Locale.US))
    }
}
