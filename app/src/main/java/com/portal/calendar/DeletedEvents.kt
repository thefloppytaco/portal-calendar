package com.portal.calendar

import android.content.Context
import org.json.JSONObject

/**
 * UIDs of events the user deleted from the board. A published iCal feed
 * (especially Google's secret address) can keep serving a deleted event for
 * minutes-to-hours, so we hide it locally until the feed catches up. Entries
 * expire after [TTL_MS] — by then the source has long since dropped it.
 */
object DeletedEvents {
    private const val FILE = "deleted_events.json"
    private const val TTL_MS = 14L * 24 * 3600 * 1000

    /** Records a deletion so the board stops showing it. */
    fun suppress(ctx: Context, uid: String) {
        if (uid.isBlank()) return
        val now = System.currentTimeMillis()
        Data.mutateObject(ctx, FILE) { o ->
            o.put(uid, now)
            // Prune expired while we're here.
            val dead = o.keys().asSequence().filter { o.optLong(it) < now - TTL_MS }.toList()
            dead.forEach { o.remove(it) }
        }
    }

    /** UIDs still within their suppression window. */
    fun activeUids(ctx: Context): Set<String> {
        val o = Data.readObject(ctx, FILE)
        val now = System.currentTimeMillis()
        return o.keys().asSequence().filter { o.optLong(it) >= now - TTL_MS }.toSet()
    }
}
