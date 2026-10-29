package com.portal.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure window/date helpers behind the assistant's `get_agenda` tool — no Android, no I/O,
 * so they are unit-testable in isolation. [CalendarToolProvider] handles JSON marshalling
 * and the event-snapshot lookup; [AgendaFormatter] renders the text.
 */
object CalendarQuery {

    const val DAY_MS = 86_400_000L

    /** Events overlapping the half-open window `[rangeStart, rangeEnd)` (epoch ms). The snapshot
     *  is already start-sorted (every producer sorts) and `filter` preserves order, so the result
     *  stays soonest-first without a redundant re-sort. */
    fun inWindow(events: List<EventInstance>, rangeStart: Long, rangeEnd: Long): List<EventInstance> =
        events.filter { it.end > rangeStart && it.start < rangeEnd }

    /** Local midnight (epoch ms) of the day containing [nowMs] — the default agenda start. */
    fun startOfToday(nowMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Local midnight [days] calendar days after [startMs]. Zone-aware (advances calendar
     * days, not fixed 24h spans) so a window crossing a DST transition lands exactly on the
     * Nth day's midnight instead of drifting an hour.
     */
    fun endAfterDays(startMs: Long, days: Int, zone: ZoneId): Long =
        Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
            .plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()

    /** ISO-8601 with offset in [zone], second precision — e.g. `2026-06-25T15:00:00-07:00`. */
    fun iso(ms: Long, zone: ZoneId): String =
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), zone)
            .withNano(0)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * Parses an ISO date or datetime into epoch ms, tolerant of the forms a model is likely
     * to send (a bare `YYYY-MM-DD` snaps to local midnight). Returns null when blank or
     * unparseable so the caller applies its default.
     */
    fun parseBoundary(raw: String?, zone: ZoneId): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        runCatching { return LocalDate.parse(s).atStartOfDay(zone).toInstant().toEpochMilli() }
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return Instant.parse(s).toEpochMilli() }
        runCatching { return LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli() }
        return null
    }
}
