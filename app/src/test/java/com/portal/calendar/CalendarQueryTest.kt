package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Unit tests for the pure window/date helpers behind the assistant's `get_agenda` tool.
 * No Android dependencies — runs on the JVM. (Text rendering lives in AgendaFormatterTest.)
 */
class CalendarQueryTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val nowMs = epoch("2026-06-25T14:30:00-07:00") // Thu 2:30 PM local

    private fun epoch(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun ev(title: String, start: String, end: String, allDay: Boolean = false) = EventInstance(
        start = epoch(start), end = epoch(end), allDay = allDay,
        title = title, location = null, feedName = "Work", color = 0,
    )

    private val events = listOf(
        ev("Standup", "2026-06-25T09:00:00-07:00", "2026-06-25T09:15:00-07:00"),
        ev("1-1 with Sam", "2026-06-25T15:00:00-07:00", "2026-06-25T15:30:00-07:00"),
        ev("Dentist", "2026-06-29T11:00:00-07:00", "2026-06-29T12:00:00-07:00"),
        ev("Anniversary", "2026-06-25T00:00:00-07:00", "2026-06-26T00:00:00-07:00", allDay = true),
    )

    @Test fun startOfTodayIsLocalMidnight() {
        assertEquals(epoch("2026-06-25T00:00:00-07:00"), CalendarQuery.startOfToday(nowMs, zone))
    }

    @Test fun inWindowKeepsOverlappingEventsSortedByStart() {
        val start = CalendarQuery.startOfToday(nowMs, zone)
        val r = CalendarQuery.inWindow(events, start, start + CalendarQuery.DAY_MS) // just today
        assertEquals(listOf("Anniversary", "Standup", "1-1 with Sam"), r.map { it.title })
        assertTrue(r.none { it.title == "Dentist" }) // the 29th is outside a 1-day window
    }

    @Test fun parseBoundaryHandlesDateAndDatetime() {
        assertEquals(epoch("2026-06-29T00:00:00-07:00"), CalendarQuery.parseBoundary("2026-06-29", zone))
        assertEquals(epoch("2026-06-29T11:00:00-07:00"), CalendarQuery.parseBoundary("2026-06-29T11:00:00-07:00", zone))
        assertEquals(null, CalendarQuery.parseBoundary("  ", zone))
        assertEquals(null, CalendarQuery.parseBoundary("not-a-date", zone))
    }

    @Test fun endAfterDaysIsZoneAwareAcrossDst() {
        // Window from Oct 25 (PDT, -07:00) for 14 days crosses the Nov 1 fall-back, so the
        // 14th day's midnight is at -08:00 — NOT start + 14*24h (which would land at 23:00).
        val start = epoch("2026-10-25T00:00:00-07:00")
        assertEquals(epoch("2026-11-08T00:00:00-08:00"), CalendarQuery.endAfterDays(start, 14, zone))
    }
}
