package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Unit tests for the agenda text renderer the assistant's `get_agenda` tool hands to Gemini.
 * Pure (no Android) — runs on the JVM.
 */
class AgendaFormatterTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val nowMs = epoch("2026-06-25T14:30:00-07:00") // Thu 2:30 PM local

    private fun epoch(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun ev(
        title: String,
        start: String,
        end: String,
        person: String = "Work",
        allDay: Boolean = false,
        location: String? = null,
    ) = EventInstance(
        start = epoch(start), end = epoch(end), allDay = allDay,
        title = title, location = location, feedName = person, color = 0,
    )

    private val events = listOf(
        ev("Standup", "2026-06-25T09:00:00-07:00", "2026-06-25T09:15:00-07:00"),
        ev("1-1 with Sam", "2026-06-25T15:00:00-07:00", "2026-06-25T15:30:00-07:00", location = "Zoom"),
        ev("Alex – Soccer", "2026-06-25T15:45:00-07:00", "2026-06-25T16:30:00-07:00", person = "Family", location = "Community Center"),
        ev("Dentist", "2026-06-29T11:00:00-07:00", "2026-06-29T12:00:00-07:00"),
        ev("Anniversary", "2026-06-25T00:00:00-07:00", "2026-06-26T00:00:00-07:00", allDay = true),
    )

    private fun agenda(
        evs: List<EventInstance>,
        days: Int = 14,
        startMs: Long = CalendarQuery.startOfToday(nowMs, zone),
        use24h: Boolean = false,
        calendars: List<String> = listOf("Work", "Family"),
        notes: List<String> = emptyList(),
    ): String {
        val windowed = CalendarQuery.inWindow(evs, startMs, CalendarQuery.endAfterDays(startMs, days, zone))
        return AgendaFormatter.format(windowed, nowMs, startMs, days, zone, use24h, calendars, notes)
    }

    @Test fun headerStatesNowAndCalendars() {
        val out = agenda(events)
        assertTrue(out.startsWith("Today is Thursday, June 25, 2026, 2:30 PM (America/Los_Angeles)."))
        assertTrue(out.contains("Calendars: Work, Family."))
        assertTrue(out.contains("Upcoming agenda for the next 14 days"))
    }

    @Test fun headerNamesTheStartDayWhenNotToday() {
        val start = CalendarQuery.parseBoundary("2026-07-01", zone)!!
        val out = agenda(events, days = 3, startMs = start)
        assertTrue(out.contains("Agenda for 3 days starting Wednesday, Jul 1"))
        assertFalse(out.contains("Upcoming agenda for the next"))
    }

    @Test fun groupsByDayWithTimesPersonAndLocation() {
        val out = agenda(events)
        assertTrue(out.contains("Thursday, Jun 25:"))
        assertTrue(out.contains("Monday, Jun 29:"))
        assertTrue(out.contains("3:00 PM–3:30 PM  1-1 with Sam — Work @ Zoom"))
        // name in the title is preserved so the model can answer "does Alex ..."
        assertTrue(out.contains("Alex – Soccer — Family @ Community Center"))
        assertTrue(out.contains("all day  Anniversary — Work"))
        // no location => no trailing " @ "
        assertTrue(out.contains("Standup — Work"))
        assertFalse(out.contains("Standup — Work @"))
    }

    @Test fun twentyFourHourFormat() {
        val out = agenda(events, use24h = true)
        assertTrue(out.contains("15:00–15:30  1-1 with Sam"))
        assertFalse(out.contains("PM"))
    }

    @Test fun emptyWindowStatesNoEventsButKeepsHeader() {
        val out = agenda(emptyList())
        assertTrue(out.contains("Today is Thursday"))
        assertTrue(out.contains("No events in this period."))
    }

    @Test fun noCalendarsConfiguredIsExplicitNotAnError() {
        val out = agenda(emptyList(), calendars = emptyList())
        assertTrue(out.contains("No calendars are configured on this device."))
        assertFalse(out.contains("error"))
    }

    @Test fun notesAppearUnderHeader() {
        val out = agenda(events, notes = listOf(
            "(calendar last updated about 3 hours ago)",
            "(some calendars couldn't be loaded, so this agenda may be missing events)",
        ))
        assertTrue(out.contains("(calendar last updated about 3 hours ago)"))
        assertTrue(out.contains("(some calendars couldn't be loaded"))
    }

    @Test fun multiDayEventRepeatsUnderEachCoveredDay() {
        // A single multi-day all-day event must show on every day it spans, so "are we on
        // vacation Saturday?" finds it.
        val vacation = ev("Vacation", "2026-06-26T00:00:00-07:00", "2026-06-29T00:00:00-07:00", person = "Family", allDay = true)
        val out = agenda(listOf(vacation))
        assertTrue(out.contains("Friday, Jun 26:"))
        assertTrue(out.contains("Saturday, Jun 27:"))
        assertTrue(out.contains("Sunday, Jun 28:"))
        assertFalse(out.contains("Monday, Jun 29:")) // end is exclusive midnight of the 29th
        assertEquals(3, out.lines().count { it.contains("Vacation") })
    }

    @Test fun crossMidnightEventReadsForwardsOnBothDays() {
        // Overnight: "onward" on the start day, "until" on the end day — never a backwards
        // "10:00 PM–6:00 AM" range.
        val overnight = ev("Red-eye", "2026-06-25T22:00:00-07:00", "2026-06-26T06:00:00-07:00")
        val out = agenda(listOf(overnight))
        assertTrue(out.contains("10:00 PM onward  Red-eye"))
        assertTrue(out.contains("until 6:00 AM  Red-eye"))
        assertFalse(out.contains("10:00 PM–6:00 AM"))
    }

    @Test fun locationNewlinesCollapseToOneLine() {
        val multiline = ev(
            "Workout", "2026-06-25T09:30:00-07:00", "2026-06-25T10:30:00-07:00",
            location = "Gym\n123 Main St.\nSpringfield",
        )
        val out = agenda(listOf(multiline))
        assertTrue(out.contains("Workout — Work @ Gym 123 Main St. Springfield"))
        assertEquals(1, out.lines().count { it.contains("Workout") })
    }

    @Test fun eventBeforeWindowShowsUnderFirstDayNotEarlier() {
        // An event that started yesterday but runs into today lists under today as "until …",
        // never printing a header earlier than the window start.
        val ongoing = ev("Conference", "2026-06-24T18:00:00-07:00", "2026-06-25T10:00:00-07:00")
        val out = agenda(listOf(ongoing))
        assertTrue(out.contains("Thursday, Jun 25:"))
        assertTrue(out.contains("until 10:00 AM  Conference"))
        assertFalse(out.contains("Jun 24"))
    }
}
