package com.portal.calendar

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class FeedConfig(val name: String, val color: Int, val url: String)

/** Feed list persisted as a JSON array in SharedPreferences. */
class ConfigStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun feedsJson(): String = prefs.getString(KEY, "[]")!!

    fun feeds(): List<FeedConfig> {
        val arr = JSONArray(feedsJson())
        val out = ArrayList<FeedConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val color = try {
                Color.parseColor(o.optString("color", "#58A6FF"))
            } catch (e: IllegalArgumentException) {
                Color.parseColor("#58A6FF")
            }
            out.add(FeedConfig(o.optString("name", "Calendar"), color, o.optString("url", "")))
        }
        return out
    }

    /** Validates and saves; throws on malformed input (surfaced to the config page). */
    fun saveJson(json: String) {
        val arr = JSONArray(json)
        val clean = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name").trim()
            var url = o.optString("url").trim()
            val color = o.optString("color", "#58A6FF").trim()
            if (name.isEmpty()) throw IllegalArgumentException("calendar #${i + 1} has no name")
            if (url.startsWith("webcal://")) url = "https://" + url.removePrefix("webcal://")
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                throw IllegalArgumentException("\"$name\" has an invalid URL")
            Color.parseColor(color) // throws if bad
            clean.put(JSONObject().put("name", name).put("color", color).put("url", url))
        }
        prefs.edit().putString(KEY, clean.toString()).apply()
    }

    /** Kid-lock PIN; empty = lock disabled. Gates edits on the board only. */
    fun pin(): String = prefs.getString("kid_pin", "") ?: ""

    fun setPin(v: String) {
        if (v.isNotEmpty() && !v.matches(Regex("\\d{4}")))
            throw IllegalArgumentException("the PIN must be exactly 4 digits")
        prefs.edit().putString("kid_pin", v).apply()
    }

    /** Global UI zoom (1.0 = designed-for-Portal+ size; 10″ Portals want ~1.1–1.25). */
    fun uiScale(): Float = prefs.getFloat("ui_scale", 1f)

    fun setUiScale(v: Float) {
        prefs.edit().putFloat("ui_scale", v.coerceIn(0.7f, 1.6f)).apply()
    }

    companion object {
        private const val KEY = "feeds"
    }
}
