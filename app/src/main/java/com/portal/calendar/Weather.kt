package com.portal.calendar

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Weather via Open-Meteo (free, no API key, plain HTTPS — ideal for a device
 * with no Google services). The user picks a location once on the config page
 * (geocoded by Open-Meteo's search API); forecasts are cached on disk and
 * refreshed at most every 30 minutes by the board's sync loop.
 */
object Weather {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private const val CACHE = "weather_cache.json"
    @Volatile private var memory: JSONObject? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun isConfigured(ctx: Context): Boolean = prefs(ctx).contains("wx_lat")
    fun label(ctx: Context): String = prefs(ctx).getString("wx_label", "") ?: ""
    fun unitSuffix(ctx: Context): String =
        if (prefs(ctx).getString("wx_unit", "f") == "c") "°C" else "°"

    fun set(ctx: Context, lat: Double?, lon: Double?, label: String?, unit: String?) {
        val e = prefs(ctx).edit()
        if (lat != null && lon != null) {
            e.putFloat("wx_lat", lat.toFloat()).putFloat("wx_lon", lon.toFloat())
        }
        label?.let { e.putString("wx_label", it) }
        unit?.let { e.putString("wx_unit", if (it == "c") "c" else "f") }
        e.apply()
        memory = null
        File(ctx.filesDir, CACHE).delete()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("wx_lat").remove("wx_lon")
            .remove("wx_label").remove("wx_unit").apply()
        memory = null
        File(ctx.filesDir, CACHE).delete()
    }

    /** Background-thread only. Returns true if fresh data was fetched. */
    @Synchronized
    fun maybeRefresh(ctx: Context, force: Boolean = false): Boolean {
        if (!isConfigured(ctx)) return false
        val data = data(ctx)
        if (!force && data != null &&
            System.currentTimeMillis() - data.optLong("fetchedAt") < 30 * 60_000) return false
        return try {
            val p = prefs(ctx)
            val unit = if (p.getString("wx_unit", "f") == "c") "celsius" else "fahrenheit"
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${p.getFloat("wx_lat", 0f)}&longitude=${p.getFloat("wx_lon", 0f)}" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                "&hourly=temperature_2m,weather_code" +
                "&forecast_days=10&timezone=auto&temperature_unit=$unit"
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (resp.code !in 200..299) return false
                val o = JSONObject(resp.body?.string() ?: return false)
                o.put("fetchedAt", System.currentTimeMillis())
                File(ctx.filesDir, CACHE).writeText(o.toString())
                memory = o
            }
            App.instance.notifyDataChanged()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun data(ctx: Context): JSONObject? {
        memory?.let { return it }
        return try {
            val o = JSONObject(File(ctx.filesDir, CACHE).readText())
            memory = o
            o
        } catch (e: Exception) {
            null
        }
    }

    /** "☀️ 72° · H 78 L 64" for the sidebar, or null. */
    fun summaryLine(ctx: Context): String? {
        val d = data(ctx) ?: return null
        val cur = d.optJSONObject("current") ?: return null
        val temp = cur.optDouble("temperature_2m", Double.NaN)
        if (temp.isNaN()) return null
        val sb = StringBuilder()
        sb.append(emoji(cur.optInt("weather_code"))).append(" ")
            .append(temp.roundToInt()).append(unitSuffix(ctx))
        today(ctx)?.let { (hi, lo, _) -> sb.append("  ·  H ").append(hi).append(" L ").append(lo) }
        return sb.toString()
    }

    fun today(ctx: Context): Triple<Int, Int, Int>? =
        daily(ctx, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

    /** (hi, lo, code) for a yyyy-MM-dd date inside the forecast window. */
    fun daily(ctx: Context, date: String): Triple<Int, Int, Int>? {
        val d = data(ctx)?.optJSONObject("daily") ?: return null
        val times = d.optJSONArray("time") ?: return null
        for (i in 0 until times.length()) {
            if (times.optString(i) == date) {
                return Triple(
                    d.optJSONArray("temperature_2m_max")?.optDouble(i)?.roundToInt() ?: return null,
                    d.optJSONArray("temperature_2m_min")?.optDouble(i)?.roundToInt() ?: return null,
                    d.optJSONArray("weather_code")?.optInt(i) ?: 0)
            }
        }
        return null
    }

    /** (temp, code) at the hour containing [millis], or null outside the window. */
    fun hourly(ctx: Context, millis: Long): Pair<Int, Int>? {
        val d = data(ctx)?.optJSONObject("hourly") ?: return null
        val times = d.optJSONArray("time") ?: return null
        val key = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US).format(Date(millis))
        for (i in 0 until times.length()) {
            if (times.optString(i) == key) {
                val t = d.optJSONArray("temperature_2m")?.optDouble(i) ?: return null
                return t.roundToInt() to (d.optJSONArray("weather_code")?.optInt(i) ?: 0)
            }
        }
        return null
    }

    /** Open-Meteo geocoding search (city or postal code). */
    fun search(q: String): JSONArray {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&name=" +
            URLEncoder.encode(q, "UTF-8")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.code !in 200..299)
                throw IllegalArgumentException("location search failed (HTTP ${resp.code})")
            val results = JSONObject(resp.body?.string() ?: "{}").optJSONArray("results")
                ?: return JSONArray()
            val out = JSONArray()
            for (i in 0 until results.length()) {
                val r = results.getJSONObject(i)
                out.put(JSONObject()
                    .put("label", listOfNotNull(
                        r.optString("name").ifEmpty { null },
                        r.optString("admin1").ifEmpty { null },
                        r.optString("country_code").ifEmpty { null }).joinToString(", "))
                    .put("lat", r.optDouble("latitude"))
                    .put("lon", r.optDouble("longitude")))
            }
            return out
        }
    }

    fun statusJson(ctx: Context): String {
        val o = JSONObject()
            .put("configured", isConfigured(ctx))
            .put("label", label(ctx))
            .put("unit", prefs(ctx).getString("wx_unit", "f"))
        summaryLine(ctx)?.let { o.put("summary", it) }
        return o.toString()
    }

    /** WMO weather code → a friendly glyph. */
    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤"
        3 -> "☁️"
        45, 48 -> "🌫"
        in 51..57 -> "🌦"
        in 61..67 -> "🌧"
        in 71..77 -> "🌨"
        in 80..82 -> "🌧"
        85, 86 -> "🌨"
        in 95..99 -> "⛈"
        else -> "🌤"
    }
}
