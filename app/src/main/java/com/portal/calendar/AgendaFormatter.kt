package com.portal.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders a windowed, sorted list of [EventInstance] into the readable day-grouped text the
 * assistant tool hands to Jarvis. Pure (no Android, no I/O); the
 * provider supplies `now`, the device zone, the 12/24-hour preference, the calendar names,
 * and any soft notes. English locale on purpose — the agenda is consumed by the (English)
 * assistant and stays deterministic for tests.
 *
 * The goal is for the model to *read and reason* over this text (count, find the next item,
 * spot a person by name in a title), exactly as it would over a pasted calendar — so the
 * wording is plain and never error-shaped.
 *
 * Events are listed under **every local day they cover** within the window, so a multi-day
 * trip shows up on each of its days (not just the first), and a cross-midnight event reads
 * sensibly on both days.
 */
object AgendaFormatter {

    private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.ENGLISH)
    private val HEADER_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)
    private val T12 = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val T24 = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val WS = Regex("\\s+")

    /**
     * @param events  filtered to the window and sorted by start.
     * @param nowMs   current time, for the header.
     * @param windowStartMs  start of the agenda window (local midnight when the caller passed
     *                       a date; a mid-day instant if it passed a datetime).
     * @param days    window length (number of day buckets to render).
     * @param zone    device zone.
     * @param use24h  device 12/24-hour preference.
     * @param calendarNames  feed (person) names, for the "Calendars:" line. Empty => none configured.
     * @param notes   soft lines appended under the header (staleness, "some calendars couldn't be
     *                read", truncation, …). Never error-shaped.
     */
    fun format(
        events: List<EventInstance>,
        nowMs: Long,
        windowStartMs: Long,
        days: Int,
        zone: ZoneId,
        use24h: Boolean,
        calendarNames: List<String>,
        notes: List<String> = emptyList(),
    ): String {
        val sb = StringBuilder()
        val nowZdt = Instant.ofEpochMilli(nowMs).atZone(zone)
        val today = nowZdt.toLocalDate()
        sb.append("Today is ").append(HEADER_DATE.format(nowZdt))
            .append(", ").append(timeOf(nowMs, zone, use24h))
            .append(" (").append(zone.id).append(").\n")
        if (calendarNames.isEmpty()) {
            // The one explicit "nothing here" case — stated plainly, not as an error.
            sb.append("No calendars are configured on this device.")
            return sb.toString()
        }
        sb.append("Calendars: ").append(calendarNames.joinToString(", ")).append(".\n")
        val windowStartDate = Instant.ofEpochMilli(windowStartMs).atZone(zone).toLocalDate()
        val dayWord = if (days == 1) " day" else " days"
        if (windowStartDate == nowZdt.toLocalDate()) {
            sb.append("Upcoming agenda for the next ").append(days).append(dayWord)
        } else {
            sb.append("Agenda for ").append(days).append(dayWord)
                .append(" starting ").append(DAY_HEADER.format(windowStartDate))
        }
        sb.append(" — all times local.\n")
        notes.forEach { sb.append(it).append('\n') }

        if (events.isEmpty()) {
            sb.append("\nNo events in this period.")
            return sb.toString()
        }

        // List each event under every local day it covers within the window.
        for (offset in 0 until days) {
            val day = windowStartDate.plusDays(offset.toLong())
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val onDay = events.filter { it.start < dayEnd && it.end > dayStart } // overlaps this day
            if (onDay.isEmpty()) continue
            // Anchor each day relative to now so the model answers "today"/"tomorrow" without
            // doing (error-prone) date arithmetic over the weekday/date alone.
            sb.append('\n').append(DAY_HEADER.format(day))
            when (day.toEpochDay() - today.toEpochDay()) {
                0L -> sb.append(" (today)")
                1L -> sb.append(" (tomorrow)")
            }
            sb.append(":\n")
            for (e in onDay) {
                sb.append("  ").append(timeLabel(e, dayStart, dayEnd, zone, use24h))
                    .append("  ").append(oneLine(e.title))
                    .append(" — ").append(oneLine(e.feedName))
                e.location?.let { oneLine(it) }?.takeUnless { it.isEmpty() }?.let { sb.append(" @ ").append(it) }
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd('\n')
    }

    /**
     * Time label for [e] as seen on the day `[dayStart, dayEnd)`. A timed event that begins
     * or ends on another day is shown as "… onward" / "until …" so the range never reads
     * backwards across midnight; an event covering the whole day shows "all day".
     */
    private fun timeLabel(e: EventInstance, dayStart: Long, dayEnd: Long, zone: ZoneId, use24h: Boolean): String {
        if (e.allDay) return "all day"
        val startsToday = e.start >= dayStart
        val endsToday = e.end <= dayEnd
        return when {
            startsToday && endsToday -> timeOf(e.start, zone, use24h) + "–" + timeOf(e.end, zone, use24h)
            startsToday -> timeOf(e.start, zone, use24h) + " onward"
            endsToday -> "until " + timeOf(e.end, zone, use24h)
            else -> "all day"
        }
    }

    /** Collapse internal whitespace/newlines so every event stays on one line. */
    private fun oneLine(s: String): String = s.replace(WS, " ").trim()

    private fun timeOf(ms: Long, zone: ZoneId, use24h: Boolean): String =
        (if (use24h) T24 else T12).format(Instant.ofEpochMilli(ms).atZone(zone))
}
