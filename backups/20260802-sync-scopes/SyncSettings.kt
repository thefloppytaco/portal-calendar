package com.portal.calendar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-controlled provider switches and reconciliation cadence. */
object SyncSettings {
    private const val PREFS = "sync-settings"
    private const val INTERVAL = "interval_ms"
    private const val GOOGLE = "google_enabled"
    private const val APPLE = "apple_enabled"
    private const val ACCOUNTS = "accounts"
    private const val DEFAULT_INTERVAL = 5_000L

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun intervalMs(ctx: Context): Long = p(ctx).getLong(INTERVAL, DEFAULT_INTERVAL).coerceIn(5_000L, 3_600_000L)
    fun googleEnabled(ctx: Context): Boolean = p(ctx).getBoolean(GOOGLE, true)
    fun appleEnabled(ctx: Context): Boolean = p(ctx).getBoolean(APPLE, true)

    fun json(ctx: Context): String = JSONObject()
        .put("intervalMs", intervalMs(ctx))
        .put("intervalSeconds", intervalMs(ctx) / 1000)
        .put("googleEnabled", googleEnabled(ctx))
        .put("appleEnabled", appleEnabled(ctx))
        .put("accounts", JSONArray(p(ctx).getString(ACCOUNTS, "[]")))
        .toString()

    fun save(ctx: Context, input: JSONObject): String {
        val seconds = input.optLong("intervalSeconds", 5L).coerceIn(5L, 3600L)
        val accounts = input.optJSONArray("accounts") ?: JSONArray()
        p(ctx).edit()
            .putLong(INTERVAL, seconds * 1000L)
            .putBoolean(GOOGLE, input.optBoolean("googleEnabled", true))
            .putBoolean(APPLE, input.optBoolean("appleEnabled", true))
            .putString(ACCOUNTS, accounts.toString())
            .apply()
        App.instance.restartSyncLoop()
        return json(ctx)
    }
}
