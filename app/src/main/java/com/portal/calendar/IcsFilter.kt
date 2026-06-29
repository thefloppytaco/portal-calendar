package com.portal.calendar

/**
 * Reduces an iCalendar feed to just the properties the board reads, before biweekly parses it.
 * A feed dominated by data we never use (attendee lists, descriptions, attachments) otherwise
 * wastes most of the parse; keeping only the needed properties shrinks it and cuts parse time
 * proportionally.
 *
 * It's an allowlist ([EVENT_PROPERTIES]), not a denylist: a new field a provider invents is
 * dropped automatically rather than chased. Pure, JVM-testable, and fold-aware (a property
 * continues on any following line that starts with a space or tab).
 */
object IcsFilter {
    /** The VEVENT properties the board reads — the single source of truth (see SyncManager.
     *  parseFeed). Add one here when parseFeed starts reading it; nothing else changes. */
    val EVENT_PROPERTIES = setOf(
        "SUMMARY", "DTSTART", "DTEND", "DURATION", "LOCATION",
        "UID", "RRULE", "RDATE", "EXDATE", "RECURRENCE-ID", "STATUS",
    )

    fun keepNeededProperties(ics: String): String {
        val sb = StringBuilder(ics.length / 2)
        // Stack of open component names. A property is dropped only when its enclosing component
        // is a VEVENT and it isn't in EVENT_PROPERTIES; VCALENDAR headers, VTIMEZONE and VALARM
        // are kept. BEGIN/END are always emitted, so the output's structure matches the input's
        // and even a malformed/unbalanced feed can't be corrupted into one biweekly rejects.
        val stack = ArrayList<String>()
        var keepingFold = true  // are the current property's continuation lines kept?

        for (rawLine in ics.splitToSequence('\n')) {
            val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
            if (line.isNotEmpty() && (line[0] == ' ' || line[0] == '\t')) {
                if (keepingFold) sb.append(line).append("\r\n")
                continue
            }
            when (val name = propName(line)) {
                "BEGIN" -> {
                    stack.add(valueOf(line))
                    keepingFold = true
                    sb.append(line).append("\r\n")
                }
                "END" -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    keepingFold = true
                    sb.append(line).append("\r\n")
                }
                else -> {
                    keepingFold = stack.lastOrNull() != "VEVENT" || name in EVENT_PROPERTIES
                    if (keepingFold) sb.append(line).append("\r\n")
                }
            }
        }
        return sb.toString()
    }

    /** Property name = text before the first ':' or ';' (params), upper-cased. */
    private fun propName(line: String): String {
        var end = line.length
        for (i in line.indices) {
            val c = line[i]
            if (c == ':' || c == ';') { end = i; break }
        }
        return line.substring(0, end).uppercase()
    }

    /** Component name on a BEGIN/END line: text after the first ':', upper-cased. */
    private fun valueOf(line: String): String =
        line.substringAfter(':', "").trim().uppercase()
}
