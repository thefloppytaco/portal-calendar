package com.portal.calendar

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import org.json.JSONObject
import java.time.ZoneId
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exposes the family calendar's upcoming **agenda** to the Portal **assistant** via its
 * tool provider plugin contract (an exported [ContentProvider] carrying a JSON tool
 * declaration in its manifest meta-data; the assistant discovers it, the user enables it
 * once in the assistant's Settings, and invokes it with a synchronous `call`).
 *
 * **Why an agenda dump:** a narrow parameterized query made the model guess date ranges /
 * names blindly and miss things. Instead we hand it the whole upcoming agenda as readable
 * text and let it reason — the same thing that made pasting the raw iCal accurate, but the
 * secret feed URL never leaves this process.
 *
 * **Privacy:** the iCal feed URLs and credentials never leave the app. We return only the
 * parsed event content (titles / times / locations / people) — never a URL or token. (This
 * hides the feed secrets, not event content: an exported provider is readable by any app on
 * the device, the same exposure as the board already on screen on a family Portal.)
 *
 * **Latency:** `call()` answers well inside the assistant's ~5s timeout and never blocks on
 * the network. It serves [App.lastEvents] (the board's live snapshot), falling back to a
 * synchronous parse of the on-disk feed caches when the board hasn't synced this process,
 * and kicks a best-effort background refresh for the *next* call.
 */
class CalendarToolProvider : ContentProvider() {

    /** True while a query-triggered background sync is in flight (coalesces refreshes). */
    private val refreshing = AtomicBoolean(false)

    /** Worker for the bounded offline-cache parse, so a slow parse keeps running past the
     *  call's deadline (publishing for the next call) instead of blocking the binder thread. */
    private val cacheParseExec = Executors.newSingleThreadExecutor()

    /** True while a cache parse is in flight (coalesces it, like [refreshing] for syncs):
     *  a burst of cold retries must not queue a backlog of identical multi-feed parses. */
    private val cacheParsing = AtomicBoolean(false)

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != METHOD_INVOKE) return null
        val result = runCatching {
            when (arg) {
                TOOL_AGENDA -> agenda(JSONObject(extras?.getString(EXTRA_ARGS_JSON) ?: "{}"))
                else -> JSONObject().put("error", "unknown tool: $arg")
            }
        }.getOrElse { JSONObject().put("error", it.message ?: "calendar lookup failed") }
        return Bundle().apply { putString(EXTRA_RESULT_JSON, result.toString()) }
    }

    private fun agenda(args: JSONObject): JSONObject {
        // The call() that wakes a dead PortalHub can land on a binder thread before
        // App.onCreate finishes; briefly wait for startup (well inside the assistant's
        // timeout) so the first cold call answers instead of erroring out.
        val app = awaitApp(STARTUP_WAIT_MS)
            ?: return JSONObject().put("error", "calendar still starting up, try again")
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val notes = ArrayList<String>()

        val feeds = app.store.feeds()
        val calendarNames = feeds.filter { it.kind != "inbox" }.map { it.name }

        // Source the snapshot without ever touching the network.
        val asOf = app.lastSyncAt
        var events = app.lastEvents
        val problems = ArrayList<String>()
        if (events.isEmpty() && asOf == 0L) {
            // Board hasn't synced this process — parse the offline caches, but BOUND the wait.
            events = primeFromCacheBounded(app, problems, CACHE_PARSE_BUDGET_MS)
        }
        // Freshen for the next call when cold or the snapshot is getting old.
        val stale = asOf != 0L && now - asOf > STALE_AFTER_MS
        if (asOf == 0L || stale) kickBackgroundRefresh(app)

        // Soft notes (never error-shaped): age / "data not loaded yet", a feed that couldn't
        // be read or hasn't synced (so the model doesn't read it as "nothing scheduled"), a
        // bad start arg, truncation.
        freshnessNote(asOf, now, events.isNotEmpty())?.let { notes.add(it) }
        // Only when we already have *some* data (or a real sync ran) — otherwise the cold
        // "no saved calendar data yet" note above already covers it.
        if ((feedDataMissing(problems) || feedDataMissing(app.lastSyncProblems)) &&
            (asOf != 0L || events.isNotEmpty())
        ) {
            notes.add("(some calendars couldn't be loaded, so this agenda may be missing events)")
        }
        val startArg = args.optStringOrNull("start")
        val parsedStart = startArg?.let { CalendarQuery.parseBoundary(it, zone) }
        if (startArg != null && parsedStart == null) {
            notes.add("(couldn't read the requested start date \"$startArg\"; showing from today)")
        }

        // Window: start (parsed date arg, or today's local midnight) .. +days calendar days.
        val startMs = parsedStart ?: CalendarQuery.startOfToday(now, zone)
        val days = args.optInt("days", DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
        var windowed = CalendarQuery.inWindow(events, startMs, CalendarQuery.endAfterDays(startMs, days, zone))
        if (windowed.size > MAX_AGENDA_EVENTS) {
            notes.add("(showing the first $MAX_AGENDA_EVENTS events; ask about a specific day or a shorter range for the rest)")
            windowed = windowed.take(MAX_AGENDA_EVENTS)
        }

        val use24h = context?.let { DateFormat.is24HourFormat(it) } ?: false
        val text = AgendaFormatter.format(
            events = windowed,
            nowMs = now,
            windowStartMs = startMs,
            days = days,
            zone = zone,
            use24h = use24h,
            calendarNames = calendarNames,
            notes = notes,
        )

        return JSONObject()
            .put("agenda", text)
            .put("generatedAt", CalendarQuery.iso(now, zone))
    }

    /** Problems that mean a feed's events are MISSING (broken, fetch-failed, or never synced)
     *  — as opposed to merely stale/cached, which is fine. */
    private fun feedDataMissing(problems: List<String>): Boolean =
        problems.any {
            it.contains("unreadable") || it.contains("fetch failed") ||
                it.contains("sync failed") || it.contains("no saved data")
        }

    /** A soft, never-alarming note about data age (so Jarvis won't say "not connected"). */
    private fun freshnessNote(asOf: Long, now: Long, haveEvents: Boolean): String? = when {
        asOf == 0L -> if (haveEvents) "(showing the last saved calendar; refreshing now)"
        else "(no saved calendar data yet; refreshing now)"
        now - asOf > STALE_AFTER_MS -> "(calendar last updated about ${humanAge(now - asOf)} ago)"
        else -> null
    }

    private fun humanAge(ms: Long): String {
        val min = ms / 60_000
        return when {
            min < 90 -> "$min min"
            min < 60 * 36 -> "${(min + 30) / 60} hours"
            else -> "${(min + 720) / 1440} days"
        }
    }

    /** Waits up to [timeoutMs] for App.onCreate to finish (ready). Safe to block here:
     *  call() runs on a binder thread and the assistant budgets several seconds. */
    private fun awaitApp(timeoutMs: Long): App? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var app = App.instanceOrNull()
        while (app == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            app = App.instanceOrNull()
        }
        return app
    }

    /**
     * Parse the on-disk caches on a worker, waiting at most [budgetMs]. If the parse finishes
     * in time, return its events (and surface its per-feed problems); if it overruns — a large
     * feed can take many seconds — return empty and let it keep running: it still publishes to
     * [App.lastEvents] on completion, so the next call is instant. Coalesced so a burst of cold
     * retries can't queue a backlog of identical parses.
     */
    private fun primeFromCacheBounded(app: App, out: MutableList<String>, budgetMs: Long): List<EventInstance> {
        if (!cacheParsing.compareAndSet(false, true)) return emptyList()
        val probs = java.util.Collections.synchronizedList(ArrayList<String>())
        // submit() inside the guard: if it throws (e.g. RejectedExecutionException) the worker's
        // finally never runs, so reset the flag here or it would latch true for the whole process.
        val future = runCatching {
            cacheParseExec.submit(Callable {
                try {
                    val evs = app.sync.eventsFromCache(probs)
                    // Publish only if still cold, so we can't clobber a real sync that landed
                    // meanwhile. asOf stays 0: this is cache, not a sync (the kicked refresh sets it).
                    if (evs.isNotEmpty()) app.onMain {
                        if (app.lastEvents.isEmpty() && app.lastSyncAt == 0L) app.lastEvents = evs
                    }
                    evs
                } finally {
                    cacheParsing.set(false)
                }
            })
        }.getOrElse { cacheParsing.set(false); return emptyList() }
        return runCatching {
            val evs = future.get(budgetMs, TimeUnit.MILLISECONDS)
            synchronized(probs) { out.addAll(probs) } // completed in time → surface its problems
            evs
        }.getOrDefault(emptyList()) // timed out (or failed) — worker keeps running and publishes
    }

    /** A query-triggered refresh; coalesced so a burst of cold calls can't queue a
     *  pile of redundant network syncs on the single-thread sync executor. */
    private fun kickBackgroundRefresh(app: App) {
        if (!refreshing.compareAndSet(false, true)) return
        runCatching {
            app.sync.requestSync { evs, probs ->
                app.publishSync(evs, System.currentTimeMillis(), probs)
                refreshing.set(false)
            }
        }.onFailure { refreshing.set(false) }
    }

    // --- ContentProvider surface we don't use: this provider only answers call(). ---
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).trim().takeUnless { it.isEmpty() } else null

    companion object {
        // The Portal assistant tool contract (literal strings — no dependency needed).
        private const val METHOD_INVOKE = "invoke"
        private const val EXTRA_ARGS_JSON = "com.portal.assistant.tools.extra.ARGS"
        private const val EXTRA_RESULT_JSON = "com.portal.assistant.tools.extra.RESULT"
        private const val TOOL_AGENDA = "com.portal.calendar.get_agenda"

        private const val DEFAULT_DAYS = 14
        private const val MAX_DAYS = 60

        /** Soft ceiling on event lines per agenda, so a huge range can't bloat the prompt. */
        private const val MAX_AGENDA_EVENTS = 200

        /** Past this age we note the snapshot's age and trigger a background refresh. */
        private const val STALE_AFTER_MS = 20L * 60 * 1000

        /** How long a cold call() waits for App startup before giving up (< the
         *  assistant's ~5s tool timeout, leaving headroom for the lookup itself). */
        private const val STARTUP_WAIT_MS = 3_000L

        /** How long a cold call() waits for the offline-cache parse before answering "loading". */
        private const val CACHE_PARSE_BUDGET_MS = 2_000L
    }
}
