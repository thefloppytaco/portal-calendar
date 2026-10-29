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

    /** Set once onCreate has wired up store/sync/server — guards against a binder-thread
     *  provider call() that lands mid-startup (instance set, dependencies not yet). */
    @Volatile var ready = false

    /** The board currently on screen, if any — used for live scale preview. */
    var activeBoard: BoardController? = null

    /** Last synced events, so a rebuilt board paints instantly instead of empty. */
    @Volatile var lastEvents: List<EventInstance> = emptyList()

    /** When [lastEvents] was last refreshed (epoch ms; 0 = never). Surfaced to the
     *  assistant as `asOf` so it can judge staleness when the board isn't on screen. */
    @Volatile var lastSyncAt: Long = 0L

    /** Per-feed problems from the last sync (e.g. a feed that failed to fetch/parse), so the
     *  assistant tool can note that the agenda may be missing a calendar. */
    @Volatile var lastSyncProblems: List<String> = emptyList()

    fun onMain(block: () -> Unit) {
        main.post(block)
    }

    /** Publish a completed sync to the in-memory snapshot and persist it (off the main thread),
     *  so a later cold start can answer from it without re-parsing every feed. A wholly-failed
     *  sync (no events + problems) is not persisted, so it can't wipe the last good snapshot. */
    fun publishSync(events: List<EventInstance>, at: Long, problems: List<String>) {
        lastEvents = events
        lastSyncAt = at
        lastSyncProblems = problems
        if (events.isNotEmpty() || problems.isEmpty()) {
            Thread { runCatching { Snapshot.save(this, events, at, problems) } }.start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ConfigStore(this)
        sync = SyncManager(this, store)
        // Prime from the last persisted agenda BEFORE marking ready, so a cold provider call
        // answers instantly from the last good data instead of re-parsing every ICS cache.
        Snapshot.load(this)?.let { snap ->
            // Drop events (and the matching "Name: ..." problems) for feeds no longer configured,
            // so a removed/renamed calendar isn't resurrected on a cold start. Adopt the "as of"
            // only when usable events remain, else leave it 0 so the tool refreshes.
            val names = store.feeds().filter { it.kind != "inbox" }.map { it.name }.toSet()
            val events = snap.events.filter { it.feedName in names }
            if (events.isNotEmpty()) {
                lastEvents = events
                lastSyncAt = snap.syncedAt
                lastSyncProblems = snap.problems.filter {
                    val feed = it.substringBefore(": ", "")
                    feed.isEmpty() || feed in names
                }
            }
        }
        // The assistant tool only needs store + sync — mark ready now so a cold provider
        // call isn't blocked (or wedged) by the peripheral startup that follows.
        ready = true
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
        FamilySync.applyRole(this) // hub advertises / spoke discovers + polls
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
                // A salvo, not a single shot: Immortal's photo frame relaunches
                // on this same broadcast and holds the screen forever if it
                // lands last — one lost race used to mean "photo frame until
                // someone intervenes". Re-asserting is idempotent (singleTask).
                launchBoard(1200)
                launchBoard(3500)
                launchBoard(7000)
            }
            Intent.ACTION_SCREEN_OFF ->
                // Meta's presence policy puts an "empty" room straight to sleep
                // with no dream at all. A Skylight-style board is always on, so
                // wake right back up into the calendar.
                main.postDelayed({
                    if (!Screensaver.isEnabled(this)) return@postDelayed
                    wake("portalcal:wakeBoard")
                    launchBoard(800)
                    launchBoard(3000)
                    launchBoard(6500)
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

    /** Bring the board to the front now (used by the foreground guard). */
    fun assertBoard() = launchBoard(0)

    private fun launchBoard(delayMs: Long) {
        main.postDelayed({
            if (!Screensaver.isEnabled(this)) return@postDelayed
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }, delayMs)
    }

    companion object {
        lateinit var instance: App

        /** Null until [onCreate] has fully run — a ContentProvider call() can land on a
         *  binder thread mid-startup, before `instance` or its deps are set. Gated on
         *  [ready] so callers never see a half-initialized App. */
        fun instanceOrNull(): App? =
            if (::instance.isInitialized && instance.ready) instance else null
    }
}
