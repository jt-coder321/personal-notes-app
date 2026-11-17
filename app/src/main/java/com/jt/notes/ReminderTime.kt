package com.jt.notes

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plain-language reminder times, kept free of Android so the rules can be
 * unit tested. [parse] returns the exact moment, or null when the input
 * cannot be understood or would land in the past: the caller never saves a
 * guessed time.
 *
 * Understood, all case-insensitive:
 * - Relative: "in 30 minutes", "in 2 hours", "in 3 days" (also "min", "mins",
 *   "hr", "hrs", and "a"/"an" for one).
 * - Anchored: "today 3pm", "tomorrow 9am", "tomorrow at 9". "at" is optional.
 * - Weekdays: "friday 10am", "next friday 10am", and three-letter names
 *   ("fri 10am"). Without "next", the next occurrence of that weekday: today
 *   when the time is still ahead, otherwise next week. With "next", the
 *   weekday of the following week.
 * - Bare times: "9am", "9:30pm", "21:30". Today when still in the future,
 *   otherwise tomorrow. A bare hour without am/pm reads as 24-hour time.
 * - A day with no time ("tomorrow", "friday") means 9:00 AM.
 */
object ReminderTime {
    private val weekdays = mapOf(
        "monday" to DayOfWeek.MONDAY,
        "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY,
        "tue" to DayOfWeek.TUESDAY,
        "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "thu" to DayOfWeek.THURSDAY,
        "thur" to DayOfWeek.THURSDAY,
        "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY,
        "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
        "sun" to DayOfWeek.SUNDAY,
    )

    private val relativePattern =
        Regex("""in\s+(\d+|an?)\s+(minutes?|mins?|hours?|hrs?|days?)""", RegexOption.IGNORE_CASE)
    private val dayPattern = Regex(
        """(next\s+)?(today|tomorrow|monday|mon|tuesday|tues?|wednesday|wed|thursday|thurs?|thur|friday|fri|saturday|sat|sunday|sun)\s*(?:at\s+)?(\d{1,2}(?::\d{2})?\s*(?:am|pm)?)?""",
        RegexOption.IGNORE_CASE,
    )
    private val timePattern =
        Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE)

    private val defaultTime = LocalTime.of(9, 0)

    fun parse(input: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val text = input.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return null
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZoned.toLocalDate()

        relativePattern.matchEntire(text)?.let { match ->
            val amountText = match.groupValues[1]
            val amount = if (amountText.equals("a", ignoreCase = true) || amountText.equals("an", ignoreCase = true)) {
                1L
            } else {
                amountText.toLongOrNull() ?: return null
            }
            // Absurd amounts would overflow the date arithmetic; no reminder needs them.
            if (amount > 1_000_000) return null
            val result = when (match.groupValues[2].lowercase(Locale.ROOT)) {
                "minute", "minutes", "min", "mins" -> nowZoned.plusMinutes(amount)
                "hour", "hours", "hr", "hrs" -> nowZoned.plusHours(amount)
                "day", "days" -> nowZoned.plusDays(amount)
                else -> return null
            }
            return result.toInstant().toEpochMilli().takeIf { it > now }
        }

        dayPattern.matchEntire(text)?.let { match ->
            val next = match.groupValues[1].isNotBlank()
            val dayWord = match.groupValues[2].lowercase(Locale.ROOT)
            val timeText = match.groupValues[3].ifBlank { null }
            val time = if (timeText == null) defaultTime else parseTimeOfDay(timeText) ?: return null
            val date = when (dayWord) {
                "today" -> if (next) return null else today
                "tomorrow" -> if (next) return null else today.plusDays(1)
                else -> {
                    val target = weekdays[dayWord] ?: return null
                    val delta = (target.value - today.dayOfWeek.value + 7) % 7
                    val days = if (next) { if (delta == 0) 7 else delta + 7 } else delta
                    today.plusDays(days.toLong())
                }
            }
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
            // A weekday naming today with a time that already passed rolls to next week.
            // "today" with a past time has nowhere to go.
            if (!next && date == today && weekdays.containsKey(dayWord)) {
                return ZonedDateTime.of(date.plusDays(7), time, zone).toInstant().toEpochMilli()
            }
            return null
        }

        parseTimeOfDay(text)?.let { time ->
            val candidate = ZonedDateTime.of(today, time, zone).toInstant().toEpochMilli()
            if (candidate > now) return candidate
            return ZonedDateTime.of(today.plusDays(1), time, zone).toInstant().toEpochMilli()
        }

        return null
    }

    /** "9am", "9:30pm", "21:30", "9". A bare hour without am/pm reads as 24-hour time. */
    private fun parseTimeOfDay(text: String): LocalTime? {
        val match = timePattern.matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].ifBlank { "0" }.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val suffix = match.groupValues[3].ifBlank { null }?.lowercase(Locale.ROOT)
        val hour24 = when (suffix) {
            "am" -> { if (hour !in 1..12) return null; if (hour == 12) 0 else hour }
            "pm" -> { if (hour !in 1..12) return null; if (hour == 12) 12 else hour + 12 }
            null -> { if (hour !in 0..23) return null; hour }
            else -> return null
        }
        return LocalTime.of(hour24, minute)
    }

    /** "Tue, Nov 17, 9:00 AM": the exact moment, shown for confirmation before saving. */
    fun format(at: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(at).atZone(zone)
            .format(DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", locale))
}
