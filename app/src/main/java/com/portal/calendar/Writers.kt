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

    fun calendars(ctx: Context): List<Cal> {
        val out = ArrayList<Cal>()
        if (SyncSettings.appleEnabled(ctx))
            out += CalDav.calendars(ctx).map { Cal("icloud", it.href, "${it.name} (iCloud)") }
        if (SyncSettings.googleCalendarEnabled(ctx)) {
            val profiles = GoogleCal.accountsJson(ctx)
            for (i in 0 until profiles.length()) {
                val id = profiles.getJSONObject(i).optString("id")
                val email = profiles.getJSONObject(i).optString("email")
                val owner = ownerLabel(email)
                GoogleCal.profileCalendars(ctx, id).forEach { cal ->
                    out += Cal("google", "$id::${cal.first}", "$owner — ${cal.second} (Google)")
                }
            }
            // Legacy installations may not have a profile yet.
            if (profiles.length() == 0)
                out += GoogleCal.calendars(ctx).map { Cal("google", it.first, "${it.second} (Google)") }
        }
        return out
    }

    private fun ownerLabel(email: String): String {
        val q = email.substringBefore('@').lowercase()
        return when {
            q.contains("mrbeers") || q.contains("matt") -> "Matt"
            q.contains("juanita") -> "Juanita"
            q.contains("kaylee") -> "Kaylee"
            q.contains("jesse") -> "Jesse"
            else -> email.substringBefore('@')
        }
    }

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
            JSONObject().put("kind", kind).put("id", id).toString())
            .putBoolean("write_target_explicit", true).apply()
    }

    fun setTargetByName(ctx: Context, requested: String): Boolean {
        val q = requested.trim().lowercase()
        if (q.isEmpty()) return false
        val hit = calendars(ctx).firstOrNull { c ->
            val n = c.name.substringBeforeLast(" (").lowercase()
            n == q || c.name.lowercase() == q || n.contains(q) || q.contains(n)
        } ?: return false
        setTarget(ctx, hit.kind, hit.id)
        return true
    }

    /** Select a person's explicitly configured default calendar; never guess from a literal name. */
    fun setTargetForOwner(ctx: Context, owner: String): Boolean {
        val key = owner.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (key.isEmpty()) return false
        val raw = prefs(ctx).getString("calendar_owner_$key", null)
        if (raw != null) {
            val o = runCatching { JSONObject(raw) }.getOrNull()
            val kind = o?.optString("kind").orEmpty(); val id = o?.optString("id").orEmpty()
            if (kind.isNotBlank() && id.isNotBlank() && calendars(ctx).any { it.kind == kind && it.id == id }) {
                setTarget(ctx, kind, id); return true
            }
        }
        val account = GoogleCal.accountForOwner(ctx, owner) ?: return false
        val email = GoogleCal.profileEmail(ctx, account)
        val profile = GoogleCal.profileCalendars(ctx, account)
        val primary = profile.firstOrNull { it.first == email } ?: profile.firstOrNull() ?: return false
        val id = "$account::${primary.first}"
        if (calendars(ctx).none { it.kind == "google" && it.id == id }) return false
        setTarget(ctx, "google", id); return true
    }

    /** Keeps an explicitly selected target valid; never invents a default. */
    fun ensureDefault(ctx: Context) {
        val p = prefs(ctx)
        if (!p.getBoolean("write_target_owner_policy_v1", false)) {
            // Clear targets created by the old first-calendar defaulting logic.
            p.edit().remove("write_target").putBoolean("write_target_owner_policy_v1", true).apply()
            return
        }
        if (!p.getBoolean("write_target_explicit", false))
            p.edit().remove("write_target").apply()
        else if (target(ctx) == null)
            p.edit().remove("write_target").apply()
    }

    fun addEvent(ctx: Context, title: String, start: Long, end: Long, allDay: Boolean) {
        val t = target(ctx)
            ?: throw IllegalArgumentException("no writable calendar is configured")
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
        if (SyncSettings.googleCalendarEnabled(ctx) && GoogleCal.isConnected(ctx) &&
            runCatching { GoogleCal.deleteByUid(ctx, uid) }.getOrDefault(false)) return true
        if (SyncSettings.appleEnabled(ctx) && CalDav.isConnected(ctx) &&
            runCatching { CalDav.deleteByUid(ctx, uid) }.getOrDefault(false)) return true
        return false
    }

    fun statusJson(ctx: Context): String {
        ensureDefault(ctx)
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
            .put("ownerTargets", ownerTargetsJson(ctx))
            .put("target", if (t == null) JSONObject.NULL
                           else JSONObject().put("kind", t.kind).put("id", t.id))
            .toString()
    }

    fun ownerTargetsJson(ctx: Context): JSONArray {
        val out = JSONArray()
        listOf("Matt", "Mathieu", "Juanita", "Kaylee", "Jesse").distinct().forEach { owner ->
            val account = GoogleCal.accountForOwner(ctx, owner) ?: return@forEach
            val email = GoogleCal.profileEmail(ctx, account) ?: return@forEach
            val primary = GoogleCal.profileCalendars(ctx, account)
                .firstOrNull { it.first == email } ?: GoogleCal.profileCalendars(ctx, account).firstOrNull()
                ?: return@forEach
            out.put(JSONObject().put("owner", owner).put("account", email)
                .put("calendarId", "$account::${primary.first}").put("calendar", primary.second))
        }
        return out
    }
}
