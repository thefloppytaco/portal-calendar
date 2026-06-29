package com.portal.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pre-parse property allowlist. No Android dependencies — runs on the JVM.
 */
class IcsFilterTest {

    private fun keep(s: String) = IcsFilter.keepNeededProperties(s)

    @Test fun keepsTheNeededEventPropertiesAndDropsAttendee() {
        val ics = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:abc-123",
            "SUMMARY:Standup",
            "DTSTART:20260629T160000Z",
            "DTEND:20260629T163000Z",
            "LOCATION:Room 4",
            "ATTENDEE;CN=Alice:mailto:alice@example.com",
            "RRULE:FREQ=WEEKLY",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val out = keep(ics)
        assertFalse("attendee dropped", out.contains("ATTENDEE"))
        for (p in listOf("UID:abc-123", "SUMMARY:Standup", "DTSTART:", "DTEND:", "LOCATION:Room 4", "RRULE:FREQ=WEEKLY"))
            assertTrue("kept $p", out.contains(p))
    }

    @Test fun dropsAnyUnknownEventPropertyAutomatically() {
        // The allowlist's whole point: a property the parser doesn't read is dropped without
        // having to be named — a future provider field needs no code change here.
        val ics = "BEGIN:VEVENT\r\nSUMMARY:x\r\nX-SOME-NEW-FIELD:huge payload\r\nDESCRIPTION:body\r\nEND:VEVENT"
        val out = keep(ics)
        assertTrue(out.contains("SUMMARY:x"))
        assertFalse(out.contains("X-SOME-NEW-FIELD"))
        assertFalse(out.contains("DESCRIPTION:body"))
    }

    @Test fun dropsFoldedContinuationLinesOfaDroppedProperty() {
        val ics = listOf(
            "BEGIN:VEVENT",
            "ATTENDEE;CN=Somebody With A Very Long Name:mailto:somebody",
            " .with.a.long.address@example.com",
            "\t-and-a-tab-folded-tail",
            "SUMMARY:After the fold",
            "END:VEVENT",
        ).joinToString("\r\n")
        val out = keep(ics)
        assertFalse(out.contains("ATTENDEE"))
        assertFalse("continuation gone", out.contains("long.address"))
        assertFalse("tab continuation gone", out.contains("tab-folded-tail"))
        assertTrue("real prop after fold kept", out.contains("SUMMARY:After the fold"))
    }

    @Test fun keepsFoldedContinuationOfaKeptProperty() {
        val ics = listOf(
            "BEGIN:VEVENT",
            "SUMMARY:A title that is",
            " folded across two lines",
            "END:VEVENT",
        ).joinToString("\r\n")
        val out = keep(ics)
        assertTrue(out.contains("SUMMARY:A title that is"))
        assertTrue("kept prop keeps its fold", out.contains(" folded across two lines"))
    }

    @Test fun keepsVtimezoneVerbatimIncludingItsRules() {
        // VTIMEZONE carries TZOFFSET/RRULE for DST and is NOT a VEVENT, so it's kept whole —
        // even properties not on the event allowlist (TZOFFSETFROM/TO).
        val ics = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VTIMEZONE",
            "TZID:America/Los_Angeles",
            "BEGIN:DAYLIGHT",
            "TZOFFSETFROM:-0800",
            "TZOFFSETTO:-0700",
            "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU",
            "END:DAYLIGHT",
            "END:VTIMEZONE",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val out = keep(ics)
        for (p in listOf("TZID:America/Los_Angeles", "TZOFFSETFROM:-0800", "TZOFFSETTO:-0700",
                "RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU", "BEGIN:DAYLIGHT", "END:VTIMEZONE"))
            assertTrue("kept $p", out.contains(p))
    }

    @Test fun keepsValarmSubBlockVerbatimButStillDropsTheEventsOwnBloat() {
        // VALARM is a sub-component, not a direct VEVENT property: kept whole so BEGIN/END
        // stay balanced. Only the event's own non-allowlisted properties (ATTENDEE) are removed.
        val ics = listOf(
            "BEGIN:VEVENT",
            "SUMMARY:Meeting",
            "ATTENDEE;CN=A:mailto:a@example.com",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER:-PT10M",
            "END:VALARM",
            "DTSTART:20260629T160000Z",
            "END:VEVENT",
        ).joinToString("\r\n")
        val out = keep(ics)
        assertTrue(out.contains("SUMMARY:Meeting"))
        assertTrue(out.contains("DTSTART:20260629T160000Z"))
        assertFalse("the event's own attendee is still dropped", out.contains("ATTENDEE"))
        assertTrue("VALARM kept verbatim", out.contains("BEGIN:VALARM"))
        assertTrue(out.contains("ACTION:DISPLAY"))
        assertTrue(out.contains("TRIGGER:-PT10M"))
        assertTrue(out.contains("END:VALARM"))
    }

    @Test fun malformedUnbalancedComponentsDoNotCorruptStructure() {
        // Regression: a VALARM missing its END must not swallow END:VEVENT or merge events.
        // Every BEGIN/END is emitted verbatim, so the filtered structure mirrors the input
        // exactly and biweekly sees the same (recoverable) tree it would have on the raw feed.
        val ics = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", "UID:1", "SUMMARY:A",
            "BEGIN:VALARM", "ACTION:DISPLAY",   // <- no END:VALARM
            "END:VEVENT",
            "BEGIN:VEVENT", "UID:2", "SUMMARY:B",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val out = keep(ics)
        assertEquals("both events' opens survive", 2, Regex("(?m)^BEGIN:VEVENT").findAll(out).count())
        assertEquals("both events' closes survive", 2, Regex("(?m)^END:VEVENT").findAll(out).count())
        assertTrue(out.contains("UID:1"))
        assertTrue(out.contains("UID:2"))
        assertTrue("the unbalanced VALARM open is preserved, not silently consumed", out.contains("BEGIN:VALARM"))
    }

    @Test fun keepsVcalendarLevelHeaders() {
        // Top-level (non-VEVENT) properties — VERSION, PRODID, X-WR-TIMEZONE — are kept.
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Test//EN\r\nX-WR-TIMEZONE:America/Los_Angeles\r\nEND:VCALENDAR"
        val out = keep(ics)
        assertTrue(out.contains("VERSION:2.0"))
        assertTrue(out.contains("PRODID:-//Test//EN"))
        assertTrue(out.contains("X-WR-TIMEZONE:America/Los_Angeles"))
    }

    @Test fun matchesPropertyNameNotValue() {
        // A SUMMARY whose value contains "DESCRIPTION" survives; the DESCRIPTION property doesn't.
        val ics = "BEGIN:VEVENT\r\nSUMMARY:Job description review\r\nDESCRIPTION:long body\r\nEND:VEVENT"
        val out = keep(ics)
        assertTrue(out.contains("SUMMARY:Job description review"))
        assertFalse(out.contains("DESCRIPTION:long body"))
    }
}
