package com.portal.calendar

import android.content.Context
import org.json.JSONObject

/**
 * What the Portal does when no one's touched the board for a while. Three modes:
 *
 *  - [MODE_TAKEOVER] "Calendar is the screensaver": the board never sleeps. We
 *    do NOT touch the system screensaver setting (Immortal's SettingsGuard
 *    rewrites `screensaver_components` back to its own dream on boot and on
 *    every home resume, an unwinnable fight). Instead [App] listens for dream
 *    broadcasts and takes the screen back when the system idles, and
 *    [KeepAliveService] keeps the process alive so those broadcasts are heard.
 *
 *  - [MODE_YIELD] "Let the screensaver run after N minutes": the board stays up
 *    while in use, but after [yieldMinutes] untouched [MainActivity] releases
 *    its keep-screen-on hold, so the device idles into whatever screensaver it
 *    has (Immortal's photo frame if installed, otherwise the Portal's own). A
 *    touch brings the board straight back. This is the burn-in-friendly mode.
 *
 *  - [MODE_OFF] (default): the board holds the screen on while it's open; the
 *    screensaver only runs once the calendar is exited.
 *
 * [isEnabled] still means "takeover" so [App]/[KeepAliveService]'s dream-fighting
 * is unchanged; in YIELD/OFF it returns false and they stand down.
 */
object Screensaver {
    const val MODE_OFF = "off"
    const val MODE_TAKEOVER = "takeover"
    const val MODE_YIELD = "yield"

    private const val PREF_MODE = "idle_mode"
    private const val PREF_YIELD_MIN = "idle_yield_min"
    private const val LEGACY_TAKEOVER = "idle_takeover" // pre-tri-state boolean

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences("config", Context.MODE_PRIVATE)

    /** Current idle mode, migrating the old boolean on first read. */
    fun mode(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(PREF_MODE, null)?.let { return it }
        return if (p.getBoolean(LEGACY_TAKEOVER, false)) MODE_TAKEOVER else MODE_OFF
    }

    fun setMode(ctx: Context, mode: String) {
        val clean = if (mode in listOf(MODE_OFF, MODE_TAKEOVER, MODE_YIELD)) mode else MODE_OFF
        prefs(ctx).edit()
            .putString(PREF_MODE, clean)
            .putBoolean(LEGACY_TAKEOVER, clean == MODE_TAKEOVER) // keep legacy in sync, harmless
            .apply()
        if (clean == MODE_TAKEOVER) KeepAliveService.start(ctx) else KeepAliveService.stop(ctx)
    }

    /** Idle delay before yielding to the screensaver (minutes), YIELD mode only. */
    fun yieldMinutes(ctx: Context): Int =
        prefs(ctx).getInt(PREF_YIELD_MIN, 10).coerceIn(1, 120)

    fun setYieldMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(PREF_YIELD_MIN, minutes.coerceIn(1, 120)).apply()
    }

    /** True only in takeover mode — preserves [App]/[KeepAliveService] behavior. */
    fun isEnabled(ctx: Context): Boolean = mode(ctx) == MODE_TAKEOVER

    /** True when the board should release the screen after an idle period. */
    fun isYield(ctx: Context): Boolean = mode(ctx) == MODE_YIELD

    fun statusJson(ctx: Context): String = JSONObject()
        .put("mode", mode(ctx))
        .put("yieldMinutes", yieldMinutes(ctx))
        .put("canWrite", true)
        .toString()
}
