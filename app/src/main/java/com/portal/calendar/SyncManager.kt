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
    val color: Int,
    /** Source iCal UID — the handle used to delete-with-sync (empty if absent). */
    val uid: String = "",
    /** True for any occurrence of a repeating series (deleting removes all). */
    val recurring: Boolean = false
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
            val all = ArrayList<EventInstance>()
            val problems = ArrayList<String>()
            // onDone must ALWAYS fire (in the finally): callers gate state on it — e.g. the
            // assistant tool's "refresh in flight" flag would latch forever if a throw in
            // store.feeds()/sort skipped the callback.
            try {
                for (feed in store.feeds()) {
                    val text = fetchWithCache(feed, problems) ?: continue
                    try {
                        if (feed.kind == "inbox") processInbox(text)
                        else all.addAll(parseFeed(text, feed))
                    } catch (e: Exception) {
                        problems.add("${feed.name}: unreadable feed (${e.javaClass.simpleName})")
                    }
                }
                all.sortBy { it.start }
            } catch (e: Exception) {
                problems.add("sync failed (${e.javaClass.simpleName})")
            } finally {
                main.post { onDone(all, problems) }
            }
        }
    }

    /** The on-disk offline copy of a feed (one shared naming scheme for read and write). */
    private fun cacheFile(feed: FeedConfig): File =
        File(ctx.filesDir, "feed_" + md5(feed.url) + ".ics")

    /**
     * Parses events straight from the on-disk feed caches — no network, no callback.
     * Used by the assistant tool provider so a voice query can answer synchronously
     * within its timeout budget even when the board hasn't run a sync this process.
     * Returns whatever feeds have a cached copy (empty list if none cached yet);
     * any feed whose cache won't parse is reported via [problems], not silently dropped.
     *
     * Read-only: passes runCommands=false so a voice query never executes magic-word
     * commands (creating chores/lists) as a side effect — that belongs to a real sync.
     */
    fun eventsFromCache(problems: MutableList<String>): List<EventInstance> {
        val out = ArrayList<EventInstance>()
        for (feed in store.feeds()) {
            if (feed.kind == "inbox") continue // commands, never rendered
            val cache = cacheFile(feed)
            if (!cache.exists()) {
                // Never synced this device yet — distinct from "unreadable" so the caller can
                // tell the model the calendar hasn't loaded rather than that it's broken.
                problems.add("${feed.name}: no saved data yet")
                continue
            }
            runCatching { out.addAll(parseFeed(cache.readText(), feed, runCommands = false)) }
                .onFailure { problems.add("${feed.name}: cached copy unreadable") }
        }
        out.sortBy { it.start }
        return out
    }

    /** Network first; on failure falls back to the last good copy on disk. */
    private fun fetchWithCache(feed: FeedConfig, problems: MutableList<String>): String? {
        val cache = cacheFile(feed)
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
                // Atomic: a crash mid-write must not corrupt the offline copy.
                val tmp = File(cache.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(cache)
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

    private fun parseFeed(text: String, feed: FeedConfig, runCommands: Boolean = true): List<EventInstance> {
        val out = ArrayList<EventInstance>()
        val tz = TimeZone.getDefault()
        val now = System.currentTimeMillis()
        val windowStart = now - 35L * DAY_MS
        val windowEnd = now + 180L * DAY_MS
        // UIDs the user deleted from the board — keep them hidden until the
        // source feed catches up (Google's secret iCal can lag hours).
        val suppressed = DeletedEvents.activeUids(ctx)

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

                val evUid = e.uid?.value ?: ""
                if (evUid.isNotEmpty() && evUid in suppressed) continue // deleted from the board

                // Magic-word events route to lists/chores and never render. Executing
                // them is a write, so only a real sync (runCommands) does it — a
                // read-only cache parse just skips them.
                val magic = MagicWords.match(e.summary?.value ?: "")
                if (magic != null) {
                    if (runCommands) {
                        val uid = e.uid?.value ?: (feed.url + (e.summary?.value ?: ""))
                        if (!MagicWords.isProcessed(ctx, uid)) {
                            runCatching { MagicWords.execute(ctx, magic, ds.value.time) }
                                .onSuccess { MagicWords.markProcessed(ctx, uid) }
                        }
                    }
                    continue
                }

                val allDay = !ds.value.hasTime()
                val durMs = durationMs(e, allDay)
                val title = e.summary?.value?.trim().takeUnless { it.isNullOrEmpty() } ?: "(untitled)"
                val loc = e.location?.value?.trim().takeUnless { it.isNullOrEmpty() }

                if (e.recurrenceId != null) {
                    // Edited single instance: add as-is (part of a series).
                    val s = ds.value.time
                    if (s + durMs >= windowStart && s <= windowEnd)
                        out.add(EventInstance(s, s + durMs, allDay, title, loc, feed.name, feed.color, evUid, true))
                    continue
                }

                val recurring = e.recurrenceRule != null || e.recurrenceDates.isNotEmpty()
                if (!recurring) {
                    val s = ds.value.time
                    if (s + durMs >= windowStart && s <= windowEnd)
                        out.add(EventInstance(s, s + durMs, allDay, title, loc, feed.name, feed.color, evUid, false))
                } else {
                    val skips = e.uid?.value?.let { overridden[it] }
                    // Honors RRULE + RDATE + EXDATE. Expand in the EVENT's own
                    // timezone — using the device zone shifts a foreign-TZID
                    // feed's occurrences (sometimes a whole day) and breaks
                    // EXDATE/RECURRENCE-ID matching across DST mismatches.
                    val etz = ical.timezoneInfo.getTimezone(ds)?.timeZone ?: tz
                    val it2 = e.getDateIterator(etz)
                    it2.advanceTo(Date(windowStart - max(durMs, DAY_MS)))
                    var guard = 0
                    while (it2.hasNext() && guard < 2000) {
                        guard++
                        val occ = it2.next()
                        if (occ.time > windowEnd) break
                        if (occ.time + durMs < windowStart) continue
                        if (skips?.contains(occ.time) == true) continue
                        out.add(EventInstance(occ.time, occ.time + durMs, allDay, title, loc, feed.name, feed.color, evUid, true))
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
                if (MagicWords.isProcessed(ctx, uid)) continue
                // Mark only after success — a failed command retries next sync.
                runCatching {
                    MagicWords.execute(ctx, MagicWords.parseSmart(ctx, title),
                        e.dateStart?.value?.time ?: System.currentTimeMillis())
                }.onSuccess { MagicWords.markProcessed(ctx, uid) }
            }
        }
    }

    private fun durationMs(e: VEvent, allDay: Boolean): Long {
        val start = e.dateStart?.value?.time ?: return 0
        e.dateEnd?.value?.time?.let { return max(0, it - start) }
        e.duration?.value?.let { return it.toMillis() }
        if (allDay) {
            // One CALENDAR day, not a fixed 24h — a fixed span spills onto the
            // next day across DST and double-renders the event.
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = start
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return c.timeInMillis - start
        }
        return 0
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val DAY_MS = CalendarQuery.DAY_MS // single source of the ms-per-day constant
    }
}
