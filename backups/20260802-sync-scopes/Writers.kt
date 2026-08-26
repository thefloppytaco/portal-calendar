package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unified "where do new events go" layer over the two write backends
 * (iCloud CalDAV + Google Calendar API). The chosen target persists as
 * {kind, id} and event creation dispatches to the right backend.
 */
object Writers {
    data class Cal(val kind: String, val id: String, val name: String)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun calendars(ctx: Context): List<Cal> =
        CalDav.calendars(ctx).map { Cal("icloud", it.href, "${it.name} (iCloud)") } +
        GoogleCal.calendars(ctx).map { Cal("google", it.first, "${it.second} (Google)") }

    fun target(ctx: Context): Cal? {
        val raw = prefs(ctx).getString("write_target", null) ?: return null
        val o = JSONObject(raw)
        return calendars(ctx).find {
            it.kind == o.optString("kind") && it.id == o.optString("id")
        }
    }

    fun setTarget(ctx: Context, kind: String, id: String) {
        if (calendars(ctx).none { it.kind == kind && it.id == id })
            throw IllegalArgumentException("unknown calendar")
        prefs(ctx).edit().putString("write_target",
            JSONObject().put("kind", kind).put("id", id).toString()).apply()
    }

    /** Keeps the target valid as accounts connect/disconnect. */
    fun ensureDefault(ctx: Context) {
        if (target(ctx) != null) return
        val first = calendars(ctx).firstOrNull()
        if (first != null) setTarget(ctx, first.kind, first.id)
        else prefs(ctx).edit().remove("write_target").apply()
    }

    fun addEvent(ctx: Context, title: String, start: Long, end: Long, allDay: Boolean) {
        val t = target(ctx)
            ?: throw IllegalArgumentException("connect iCloud or Google first (Two-way sync card)")
        when (t.kind) {
            "icloud" -> CalDav.addEventTo(ctx, t.id, title, start, end, allDay)
            "google" -> GoogleCal.addEvent(ctx, t.id, title, start, end, allDay)
        }
    }

    /**
     * Deletes an event everywhere it can be reached by UID — tries each
     * connected backend, returns true if any removed it. False means it
     * wasn't on a connected (writable) account, so the deletion can't sync.
     */
    fun deleteEvent(ctx: Context, uid: String): Boolean {
        if (uid.isBlank()) return false
        if (GoogleCal.isConnected(ctx) &&
            runCatching { GoogleCal.deleteByUid(ctx, uid) }.getOrDefault(false)) return true
        if (CalDav.isConnected(ctx) &&
            runCatching { CalDav.deleteByUid(ctx, uid) }.getOrDefault(false)) return true
        return false
    }

    fun statusJson(ctx: Context): String {
        val cals = JSONArray()
        calendars(ctx).forEach {
            cals.put(JSONObject().put("kind", it.kind).put("id", it.id).put("name", it.name))
        }
        val t = target(ctx)
        return JSONObject()
            .put("icloud", JSONObject()
                .put("connected", CalDav.isConnected(ctx))
                .put("email", CalDav.email(ctx) ?: ""))
            .put("google", JSONObject()
                .put("connected", GoogleCal.isConnected(ctx))
                .put("email", GoogleCal.email(ctx) ?: ""))
            .put("calendars", cals)
            .put("target", if (t == null) JSONObject.NULL
                           else JSONObject().put("kind", t.kind).put("id", t.id))
            .toString()
    }
}
