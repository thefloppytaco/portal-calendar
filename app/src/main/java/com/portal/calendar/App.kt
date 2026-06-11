package com.portal.calendar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Process-wide state: one ConfigStore/SyncManager/ConfigServer shared by all
 * UI (avoids port clashes on :8090), plus the idle-takeover policy.
 *
 * Idle takeover (all verified on this Portal):
 *  - At screen timeout Meta's presence policy starts the screensaver dream
 *    (Immortal's photo frame — its SettingsGuard re-asserts that on every home
 *    resume, so we don't compete for `screensaver_components`).
 *  - Dream windows draw ABOVE app windows; launching an activity does not
 *    dismiss them. So on DREAMING_STARTED we wake the device via a
 *    full-wakelock with ACQUIRE_CAUSES_WAKEUP, which ends the dream.
 *  - On DREAMING_STOPPED Immortal relaunches ITS photo frame; we start the
 *    board 1.2 s later to land on top. The board holds the screen on, so the
 *    calendar then stays up until the user exits via ✕.
 */
class App : Application() {
    lateinit var store: ConfigStore
    lateinit var sync: SyncManager
    private var server: ConfigServer? = null
    private val main = Handler(Looper.getMainLooper())
    private val configListeners = CopyOnWriteArraySet<() -> Unit>()

    /** The board currently on screen, if any — used for live scale preview. */
    var activeBoard: BoardController? = null

    fun onMain(block: () -> Unit) {
        main.post(block)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ConfigStore(this)
        sync = SyncManager(this, store)
        server = ConfigServer(this, store) { notifyConfigChanged() }
        try {
            server?.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        } catch (_: Exception) {
            // Port busy (stale process) — the page just won't load until relaunch.
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                onDreamEvent(intent.action ?: return)
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        if (Screensaver.isEnabled(this)) KeepAliveService.start(this)
    }

    fun addConfigListener(l: () -> Unit) = configListeners.add(l)
    fun removeConfigListener(l: () -> Unit) = configListeners.remove(l)
    fun notifyConfigChanged() {
        main.post { configListeners.forEach { it() } }
    }

    /** Debounced Google Tasks reconcile after local list edits / on the sync loop. */
    private val tasksSyncRunnable = Runnable {
        Thread { runCatching { GoogleTasks.syncAll(this) } }.start()
    }

    fun kickTasksSync(delayMs: Long = 2500) {
        main.removeCallbacks(tasksSyncRunnable)
        main.postDelayed(tasksSyncRunnable, delayMs)
    }

    /** Local family data changed (lists/chores/meals/members) — cheaper than a feed re-sync. */
    private val dataListeners = CopyOnWriteArraySet<() -> Unit>()
    fun addDataListener(l: () -> Unit) = dataListeners.add(l)
    fun removeDataListener(l: () -> Unit) = dataListeners.remove(l)
    fun notifyDataChanged() {
        main.post { dataListeners.forEach { it() } }
    }

    private fun onDreamEvent(action: String) {
        if (!Screensaver.isEnabled(this)) return
        val pm = getSystemService(PowerManager::class.java) ?: return
        when (action) {
            Intent.ACTION_DREAMING_STARTED ->
                // End the dream by forcing wakefulness to Awake (see class doc).
                wake("portalcal:dismissDream")
            Intent.ACTION_DREAMING_STOPPED -> {
                if (!pm.isInteractive) return // SCREEN_OFF handles real sleep
                launchBoard(1200)
            }
            Intent.ACTION_SCREEN_OFF ->
                // Meta's presence policy puts an "empty" room straight to sleep
                // with no dream at all. A Skylight-style board is always on, so
                // wake right back up into the calendar.
                main.postDelayed({
                    if (!Screensaver.isEnabled(this)) return@postDelayed
                    wake("portalcal:wakeBoard")
                    launchBoard(800)
                }, 1500)
        }
    }

    private fun wake(reason: String) {
        @Suppress("DEPRECATION")
        getSystemService(PowerManager::class.java)?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            reason
        )?.acquire(3000)
    }

    private fun launchBoard(delayMs: Long) {
        main.postDelayed({
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }, delayMs)
    }

    companion object {
        lateinit var instance: App
    }
}
