package com.portal.calendar

import android.content.Context
import org.json.JSONObject

/**
 * "Calendar as idle screen" mode. We do NOT touch the system screensaver
 * setting: Immortal's SettingsGuard rewrites `screensaver_components` back to
 * its own dream on boot and on every home resume, so that's an unwinnable
 * fight. Instead [App] listens for dream broadcasts and takes over the screen
 * when the system idles (see App.onDreamEvent). This flag just arms that
 * behavior; [KeepAliveService] keeps the process alive so the broadcasts are
 * always heard.
 */
object Screensaver {
    private const val PREF = "idle_takeover"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE).getBoolean(PREF, false)

    fun set(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF, enabled).apply()
        if (enabled) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
    }

    fun statusJson(ctx: Context): String = JSONObject()
        .put("enabled", isEnabled(ctx))
        .put("canWrite", true)
        .toString()
}
