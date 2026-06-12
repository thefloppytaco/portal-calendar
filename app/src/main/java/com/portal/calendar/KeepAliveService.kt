package com.portal.calendar

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Minimal foreground service so the process (config server + dream-broadcast
 * receiver in [App]) survives when no UI is showing. Only runs while
 * [Screensaver] mode is on. The Portal is mains-powered; cost is negligible.
 *
 * Also hosts the takeover GUARD: the stock photo frame can reach the screen
 * through paths that fire no broadcast we can react to (and it then holds the
 * screen forever). With the optional adb-granted PACKAGE_USAGE_STATS
 * permission, this service checks every 30s whether the frame is foreground
 * and takes the screen back — converging from any losing state. Without the
 * grant it silently does nothing.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val guard = object : Runnable {
        override fun run() {
            runCatching {
                if (Screensaver.isEnabled(this@KeepAliveService) &&
                    getSystemService(PowerManager::class.java)?.isInteractive == true &&
                    photoFrameIsForeground()) {
                    App.instance.assertBoard()
                }
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(
            CHANNEL, "Idle screen takeover", NotificationManager.IMPORTANCE_MIN))
        startForeground(1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Family Calendar idle screen is on")
            .build())
        handler.postDelayed(guard, 30_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(guard)
        super.onDestroy()
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun photoFrameIsForeground(): Boolean {
        if (!hasUsageAccess()) return false
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // Wide window: the frame may have been foreground for hours with no
        // app switches since — a short scan would find no events and miss it.
        val events = usm.queryEvents(now - 12 * 60 * 60_000, now)
        var lastPkg = ""
        var lastCls = ""
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = ev.packageName ?: ""
                lastCls = ev.className ?: ""
            }
        }
        return lastPkg == "com.immortal.launcher" && lastCls.contains("PhotoFrame")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    companion object {
        private const val CHANNEL = "keepalive"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeepAliveService::class.java))
        }
    }
}
