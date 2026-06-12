package com.portal.calendar

import android.content.Context
import android.os.Handler
import android.os.Looper
import biweekly.Biweekly
import biweekly.component.VEvent
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.max

data class EventInstance(
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val title: String,
    val location: String?,
    val feedName: String,
    val color: Int
)

/**
 * Fetches each configured ICS feed, caches the raw text on disk (so the board
 * still shows events after a reboot with no network), parses with biweekly and
 * expands recurrences into concrete instances inside a rolling window.
 */
class SyncManager(private val ctx: Context, private val store: ConfigStore) {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun requestSync(onDone: (events: List<EventInstance>, problems: List<String>) -> Unit) {
        executor.execute {
            val feeds = store.feeds()
            val all = ArrayList<EventInstance>()
            val problems = ArrayList<String>()
            for (feed in feeds) {
                val text = fetchWithCache(feed, problems) ?: continue
                try {
                    if (feed.kind == "inbox") processInbox(text)
                    else all.addAll(parseFeed(text, feed))
                } catch (e: Exception) {
                    problems.add("${feed.name}: unreadable feed (${e.javaClass.simpleName})")
                }
            }
            all.sortBy { it.start }
            main.post { onDone(all, problems) }
        }
    }

    /** Network first; on failure falls back to the last good copy on disk. */
    private fun fetchWithCache(feed: FeedConfig, problems: MutableList<String>): String? {
        val cache = File(ctx.filesDir, "feed_" + md5(feed.url) + ".ics")
        try {
            val url = if (feed.url.startsWith("webcal://"))
                "https://" + feed.url.removePrefix("webcal://") else feed.url
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
            try {
                if (conn.responseCode !in 200..299)
                    throw RuntimeException("HTTP ${conn.responseCode}")
                val text = conn.inputStream.bufferedReader().readText()
                if (!text.contains("BEGIN:VCALENDAR"))
                    throw RuntimeException("not an iCal feed")
                cache.writeText(text)
                return text
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            return if (cache.exists()) {
                problems.add("${feed.name}: offline, showing cached copy")
                cache.readText()
            } else {
                problems.add("${feed.name}: fetch failed (${e.message ?: e.javaClass.simpleName})")
                null
            }
        }
    }

    private fun parseFeed(text: String, feed: FeedConfig): List<EventInstance> {
        val out = ArrayList<EventInstance>()
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val windowStart = now - 35L * DAY_MS
        val windowEnd = now + 180L * DAY_MS

        for (ical in Biweekly.parse(text).all()) {
            // Instances of a recurring series that were individually edited carry a
            // RECURRENCE-ID; the master series must skip those occurrences.
            val overridden = HashMap<String, MutableSet<Long>>()
            for (e in ical.events) {
                val rid = e.recurrenceId ?: continue
                val uid = e.uid?.value ?: continue
                overridden.getOrPut(uid) { HashSet() }.add(rid.value.time)
            }

            for (e in ical.events) {
                if (e.status?.value?.equals("CANCELLED", true) == true) continue
                val ds = e.dateStart ?: continue

                // Magic-word events route to lists/chores and never render.
                val magic = MagicWords.match(e.summary?.value ?: "")
                if (magic != null) {
                    val uid = e.uid?.value ?: (feed.url + (e.summary?.value ?: ""))
                    if (MagicWords.markProcessed(ctx, uid)) {
                        runCatching { MagicWords.execute(ctx, magic, ds.value.time) }
                    }
                    continue
                }

                val allDay = !ds.value.hasTime()
                val durMs = durationMs(e, allDay)
                val title = e.summary?.value?.trim().takeUnless { it.isNullOrEmpty() } ?: "(untitled)"
                val loc = e.location?.value?.trim().takeUnless { it.isNullOrEmpty() }

                if (e.recurrenceId != null) {
                    // Edited single instance: add as-is.
                    val s = ds.value.time
                    if (s + durMs >= windowStart && s <= windowEnd)
                        out.add(EventInstance(s, s + durMs, allDay, title, loc, feed.name, feed.color))
                    continue
                }

                val recurring = e.recurrenceRule != null || e.recurrenceDates.isNotEmpty()
                if (!recurring) {
                    val s = ds.value.time
                    if (s + durMs >= windowStart && s <= windowEnd)
                        out.add(EventInstance(s, s + durMs, allDay, title, loc, feed.name, feed.color))
                } else {
                    val skips = e.uid?.value?.let { overridden[it] }
                    // Honors RRULE + RDATE + EXDATE.
                    val it2 = e.getDateIterator(tz)
                    it2.advanceTo(Date(windowStart - max(durMs, DAY_MS)))
                    var guard = 0
                    while (it2.hasNext() && guard < 2000) {
                        guard++
                        val occ = it2.next()
                        if (occ.time > windowEnd) break
                        if (occ.time + durMs < windowStart) continue
                        if (skips?.contains(occ.time) == true) continue
                        out.add(EventInstance(occ.time, occ.time + durMs, allDay, title, loc, feed.name, feed.color))
                    }
                }
            }
        }
        return out
    }

    /**
     * Test-fetches a feed URL (called from the config page when adding a
     * calendar, on the HTTP worker thread). Returns the event count; throws
     * IllegalArgumentException with a readable message if anything is wrong.
     */
    fun validateFeed(rawUrl: String): Int {
        val url = if (rawUrl.startsWith("webcal://"))
            "https://" + rawUrl.removePrefix("webcal://") else rawUrl
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "PortalFamilyCalendar/1.0")
            try {
                if (conn.responseCode !in 200..299)
                    throw IllegalArgumentException(
                        "the server said HTTP ${conn.responseCode} — check the link is the iCal/secret address")
                val text = conn.inputStream.bufferedReader().readText()
                if (!text.contains("BEGIN:VCALENDAR"))
                    throw IllegalArgumentException(
                        "that link isn't a calendar feed — it should end in .ics or start with webcal://")
                return Biweekly.parse(text).all().sumOf { it.events.size }
            } finally {
                conn.disconnect()
            }
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("couldn't reach that link (${e.message ?: e.javaClass.simpleName})")
        }
    }

    /** Inbox calendars: every event is a command, parsed loosely, never rendered. */
    private fun processInbox(text: String) {
        for (ical in Biweekly.parse(text).all()) {
            for (e in ical.events) {
                if (e.status?.value?.equals("CANCELLED", true) == true) continue
                val title = e.summary?.value?.trim().orEmpty()
                if (title.isEmpty()) continue
                val uid = e.uid?.value ?: title
                if (!MagicWords.markProcessed(ctx, uid)) continue
                runCatching {
                    MagicWords.execute(ctx, MagicWords.parseSmart(ctx, title),
                        e.dateStart?.value?.time ?: System.currentTimeMillis())
                }
            }
        }
    }

    private fun durationMs(e: VEvent, allDay: Boolean): Long {
        val start = e.dateStart?.value?.time ?: return 0
        e.dateEnd?.value?.time?.let { return max(0, it - start) }
        e.duration?.value?.let { return it.toMillis() }
        return if (allDay) DAY_MS else 0
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
