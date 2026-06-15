package com.portal.calendar

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class FeedConfig(
    val name: String,
    val color: Int,
    val url: String,
    /** "calendar" renders on the board; "inbox" = every event is a command. */
    val kind: String = "calendar")

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
            out.add(FeedConfig(o.optString("name", "Calendar"), color, o.optString("url", ""),
                if (o.optString("kind") == "inbox") "inbox" else "calendar"))
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
            clean.put(JSONObject().put("name", name).put("color", color).put("url", url)
                .put("kind", if (o.optString("kind") == "inbox") "inbox" else "calendar"))
        }
        prefs.edit().putString(KEY, clean.toString()).commit() // server thread: durable before the 200 reply
    }

    /** Set once the setup wizard finishes or is skipped — never auto-launch again. */
    fun wizardDone(): Boolean = prefs.getBoolean("wizard_done", false)

    fun setWizardDone() {
        prefs.edit().putBoolean("wizard_done", true).commit()
    }

    /** Board tab visibility: "chores" / "lists" / "meals", all on by default. */
    fun featureEnabled(key: String): Boolean = prefs.getBoolean("feature_$key", true)

    fun setFeature(key: String, on: Boolean) {
        prefs.edit().putBoolean("feature_$key", on).commit()
    }

    /** Celebration animations when a chore is checked off (on by default). */
    fun choreEffects(): Boolean = prefs.getBoolean("chore_effects", true)

    fun setChoreEffects(on: Boolean) {
        prefs.edit().putBoolean("chore_effects", on).commit()
    }

    /** Kid-lock PIN; empty = lock disabled. Gates edits on the board only. */
    fun pin(): String = prefs.getString("kid_pin", "") ?: ""

    fun setPin(v: String) {
        if (v.isNotEmpty() && !v.matches(Regex("\\d{4}")))
            throw IllegalArgumentException("the PIN must be exactly 4 digits")
        prefs.edit().putString("kid_pin", v).commit()
    }

    /** Global UI zoom (1.0 = designed-for-Portal+ size; 10″ Portals want ~1.1–1.25). */
    fun uiScale(): Float = prefs.getFloat("ui_scale", 1f)

    fun setUiScale(v: Float) {
        prefs.edit().putFloat("ui_scale", v.coerceIn(0.7f, 1.6f)).commit()
    }

    /**
     * First day of the week as a java.util.Calendar day constant (1=Sun … 7=Sat),
     * or 0 = follow the device locale. Affects every week/month layout + the
     * chore/meal week boundaries.
     */
    fun weekStart(): Int = prefs.getInt("week_start", 0)

    fun weekStartResolved(): Int {
        val v = weekStart()
        return if (v in 1..7) v else java.util.Calendar.getInstance().firstDayOfWeek
    }

    fun setWeekStart(day: Int) {
        prefs.edit().putInt("week_start", if (day in 1..7) day else 0).commit()
    }

    /** Which calendar view the board opens on. 0=Day 1=Week 2=Month 3=Plan. */
    fun defaultView(): Int = prefs.getInt("default_view", 1).coerceIn(0, 3)

    fun setDefaultView(v: Int) {
        prefs.edit().putInt("default_view", v.coerceIn(0, 3)).commit()
    }

    /** "landscape" (default), "portrait", or "auto" (sensor). */
    fun orientation(): String = prefs.getString("orientation", "landscape") ?: "landscape"

    fun setOrientation(v: String) {
        val clean = if (v in listOf("landscape", "portrait", "auto")) v else "landscape"
        prefs.edit().putString("orientation", clean).commit()
    }

    /** Theme: "light" (default) / "dark" / "auto" (night by clock) / "system". */
    fun theme(): String = prefs.getString("theme", "light") ?: "light"

    fun setTheme(v: String) {
        val clean = if (v in listOf("light", "dark", "auto", "system")) v else "light"
        prefs.edit().putString("theme", clean).commit()
    }

    /** Resolves the theme to dark-on/off right now (for auto/system). */
    fun isDark(ctx: Context): Boolean = when (theme()) {
        "dark" -> true
        "light" -> false
        "auto" -> { // night by the clock — dark 7pm–7am
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            h >= 19 || h < 7
        }
        "system" -> (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    companion object {
        private const val KEY = "feeds"
    }
}
