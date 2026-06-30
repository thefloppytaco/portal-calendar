package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last parsed agenda (events + sync time) to disk, so a cold start — e.g. an
 * assistant call that wakes a dead process — answers from the last good data instantly,
 * without re-parsing every ICS feed (which is too slow for the assistant's timeout).
 *
 * Same event content already lives in the plaintext feed_*.ics caches in filesDir, so it
 * adds no new exposure. Atomic write/read reuse [Data].
 */
object Snapshot {
    private const val FILE = "agenda_snapshot.json"

    data class Loaded(val events: List<EventInstance>, val syncedAt: Long, val problems: List<String>)

    fun save(ctx: Context, events: List<EventInstance>, syncedAt: Long, problems: List<String>) {
        val arr = JSONArray()
        for (e in events) arr.put(JSONObject().apply {
            put("s", e.start); put("e", e.end); put("ad", e.allDay); put("t", e.title)
            putOpt("loc", e.location); put("fn", e.feedName); put("c", e.color)
            put("uid", e.uid); put("rec", e.recurring)
        })
        val root = JSONObject().put("at", syncedAt).put("events", arr).put("problems", JSONArray(problems))
        runCatching { Data.writeRaw(ctx, FILE, root.toString()) }
    }

    fun load(ctx: Context): Loaded? {
        val root = Data.readObject(ctx, FILE)
        val arr = root.optJSONArray("events") ?: return null
        val out = ArrayList<EventInstance>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(EventInstance(
                start = o.optLong("s"), end = o.optLong("e"), allDay = o.optBoolean("ad"),
                title = o.optString("t"), location = o.optString("loc").takeUnless { it.isEmpty() },
                feedName = o.optString("fn"), color = o.optInt("c"),
                uid = o.optString("uid"), recurring = o.optBoolean("rec")))
        }
        val problems = ArrayList<String>()
        root.optJSONArray("problems")?.let { p ->
            for (i in 0 until p.length()) p.optString(i).takeUnless { it.isEmpty() }?.let(problems::add)
        }
        return Loaded(out, root.optLong("at", 0L), problems)
    }
}
