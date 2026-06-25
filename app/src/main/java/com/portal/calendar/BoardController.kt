package com.portal.calendar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The whole calendar board UI + refresh loops, shared by [MainActivity] and
 * the idle takeover (which is why event details use an in-board overlay
 * rather than a dialog).
 *
 * Visual language: warm paper background, white cards with soft shadows,
 * solid per-person color pills, big friendly date numbers, coral accent.
 */
class BoardController(private val baseCtx: Context) {

    private val store get() = App.instance.store
    private val sync get() = App.instance.sync
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Everything is built against a context whose density is multiplied by the
     * user's UI-scale preference — one knob that uniformly zooms every dp and
     * sp on the board (10″ Portals render noticeably smaller than the Plus).
     */
    private val uiScale: Float = store.uiScale()
    private val ctx: Context = if (uiScale == 1f) baseCtx else {
        val conf = android.content.res.Configuration(baseCtx.resources.configuration)
        conf.densityDpi = (conf.densityDpi * uiScale).toInt()
        baseCtx.createConfigurationContext(conf)
    }

    /** When set, a ✕ button appears next to ⚙ and invokes this. */
    var onExit: (() -> Unit)? = null

    /** Host swaps the board in place at the new scale (no activity recreate). */
    var onScaleCommitted: ((fromSettings: Boolean) -> Unit)? = null

    private var events: List<EventInstance> = emptyList()
    private var weekOffset = 0
    private var monthOffset = 0
    private var dayOffset = 0
    private var scheduleOffset = 0
    private var viewMode = VIEW_WEEK
    private var lastDayStamp = ""
    private var statusLine = "Starting…"

    // Top-level tabs: 0=Calendar 1=Chores 2=Lists 3=Meals 4=Routines
    private var currentTab = 0
    private lateinit var tabButtons: List<TextView>
    private lateinit var tabPanels: List<View>
    private lateinit var listsTab: ListsTab
    private lateinit var choresTab: ChoresTab
    private lateinit var mealsTab: MealsTab
    private lateinit var routinesTab: RoutinesTab

    private lateinit var clockText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherText: TextView
    private lateinit var dayWxViews: List<TextView>
    private lateinit var statusText: TextView
    private lateinit var todayList: LinearLayout
    private lateinit var legendList: LinearLayout
    private lateinit var monthLabel: TextView
    private lateinit var dayNameViews: List<TextView>
    private lateinit var dayNumViews: List<TextView>
    private lateinit var dayColumnWraps: List<LinearLayout>
    private lateinit var dayColumns: List<LinearLayout>
    private var weekIsRows = false                  // portrait: days are rows, not columns
    private lateinit var weekRowsBox: LinearLayout   // portrait week container (built per render)
    private lateinit var weekContainer: LinearLayout
    private lateinit var monthContainer: LinearLayout
    private lateinit var dayBox: LinearLayout
    private lateinit var dayContainer: ScrollView
    private lateinit var scheduleBox: LinearLayout
    private lateinit var scheduleContainer: ScrollView
    private lateinit var monthCells: List<MonthCell>
    private lateinit var viewToggles: List<TextView>
    private lateinit var weekPanel: LinearLayout
    private lateinit var setupPanel: LinearLayout
    private lateinit var setupQr: ImageView
    private lateinit var setupUrlText: TextView
    private lateinit var settingsOverlay: FrameLayout
    private lateinit var overlayQr: ImageView
    private lateinit var overlayUrl: TextView
    private lateinit var detailOverlay: FrameLayout
    private lateinit var detailTitle: TextView
    private lateinit var detailBody: TextView
    private lateinit var detailDeleteBtn: TextView
    private var detailEvent: EventInstance? = null
    private lateinit var dayOverlay: FrameLayout
    private lateinit var dayTitle: TextView
    private lateinit var dayListBox: LinearLayout
    private lateinit var addOverlay: FrameLayout
    private lateinit var addTitleInput: EditText
    private lateinit var addDateLabel: TextView
    private lateinit var addAllDayToggle: TextView
    private lateinit var addTimeRow: LinearLayout
    private lateinit var addTimeLabel: TextView
    private lateinit var addDurLabel: TextView
    private lateinit var addCalButton: TextView
    private lateinit var addMsg: TextView
    private lateinit var addSaveButton: TextView
    private var addDate: Calendar = Calendar.getInstance()
    private var addStartMinutes = 15 * 60
    private var addDurationMins = 60
    private var addAllDay = false
    private var addCalIndex = 0
    private var addBusy = false
    private lateinit var exitButton: TextView
    private lateinit var celebration: CelebrationView

    // Voice (tap-to-talk natural-language commands via the user's Gemini key).
    private lateinit var micFab: TextView
    private lateinit var voiceOverlay: FrameLayout
    private lateinit var voiceState: TextView
    private lateinit var voiceSub: TextView
    private lateinit var voiceBtn: TextView
    private val voice = VoiceInput()
    private val voiceExec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var voiceBusy = false

    private class MonthCell(val wrap: LinearLayout, val num: TextView,
                            val box: LinearLayout, val more: TextView)

    val view: FrameLayout

    init {
        Palette.dark = store.isDark(baseCtx) // set BEFORE any view is built
        view = buildUi()
        setView(store.defaultView()) // open on the user's chosen view
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }
    private val syncTick = object : Runnable {
        override fun run() {
            doSync()
            handler.postDelayed(this, SYNC_INTERVAL_MS)
        }
    }
    private var builtWeekStart = store.weekStart()
    private var builtOrientation = store.orientation()
    private var builtTheme = store.theme()
    private var builtDark = Palette.dark

    /** True when the board's built structure no longer matches the settings. */
    private fun needsRebuild() =
        store.uiScale() != uiScale ||
        store.weekStart() != builtWeekStart ||
        store.orientation() != builtOrientation ||
        store.theme() != builtTheme ||
        store.isDark(baseCtx) != builtDark

    private fun rebuild() = onScaleCommitted?.invoke(false)
        ?: (baseCtx as? android.app.Activity)?.recreate() ?: Unit

    private val configListener: () -> Unit = {
        // Scale, week-start, orientation, and theme all change the built
        // structure (densities, the month header, the layout axis, every
        // color), so a change to any swaps the board in place vs re-rendering.
        if (needsRebuild()) {
            rebuild()
        } else {
            // Commit at the unchanged value: no rebuild happens, so make sure
            // any live-preview transform is cleared.
            contentRow.scaleX = 1f
            contentRow.scaleY = 1f
            statusLine = "Config updated — syncing…"
            renderAll()
            doSync()
        }
    }

    /**
     * Live zoom while a slider is being dragged: a cheap GPU transform of the
     * board content (overlays stay unscaled). The real re-layout happens once,
     * on [commitScale].
     */
    fun previewScale(target: Float) {
        val f = target.coerceIn(0.7f, 1.6f) / uiScale
        contentRow.scaleX = f
        contentRow.scaleY = f
    }

    fun commitScale(target: Float, fromSettings: Boolean = false) {
        store.setUiScale(target)
        onScaleCommitted?.invoke(fromSettings)
            ?: (baseCtx as? android.app.Activity)?.recreate()
    }

    fun openSettings() = showSettingsOverlay()

    private val dataListener: () -> Unit = {
        applyFeatures()
        renderWeather()
        renderToday()
        when (currentTab) {
            0 -> renderCalendar() // weather in week headers
            1 -> choresTab.render()
            2 -> listsTab.render()
            3 -> mealsTab.render()
            4 -> routinesTab.render()
        }
        refreshFocusables()
    }

    /** Keep newly-rendered clickable views reachable by a Portal-TV remote. */
    private fun refreshFocusables() {
        // `view` is assigned by `view = buildUi()`, but buildUi() itself calls
        // setTab() → here before it returns, so the field is still null on that
        // first pass. Skip it; start()'s renderAll() runs the first real pass
        // once the tree (and the FocusHighlight overlay) actually exist.
        @Suppress("SENSELESS_COMPARISON")
        if (view != null) FocusHighlight.enableDpadFocus(view)
    }

    private fun renderWeather() {
        val line = Weather.summaryLine(ctx)
        weatherText.visibility = if (line != null) View.VISIBLE else View.GONE
        line?.let { weatherText.text = it }
    }

    /** Hides the tab pills for features switched off on the page. */
    private fun applyFeatures() {
        val flags = listOf(true,
            store.featureEnabled("chores"),
            store.featureEnabled("lists"),
            store.featureEnabled("meals"),
            store.featureEnabled("routines", default = false))
        tabButtons.forEachIndexed { i, b ->
            b.visibility = if (flags[i]) View.VISIBLE else View.GONE
        }
        (tabButtons[0].parent as View).visibility =
            if (flags.drop(1).any { it }) View.VISIBLE else View.GONE
        if (!flags[currentTab]) setTab(0)
        if (::micFab.isInitialized)
            micFab.visibility = if (Gemini.isReady(ctx)) View.VISIBLE else View.GONE
    }

    fun start() {
        App.instance.activeBoard = this
        App.instance.addConfigListener(configListener)
        App.instance.addDataListener(dataListener)
        events = App.instance.lastEvents // paint instantly; sync refreshes shortly
        applyFeatures()
        exitButton.visibility = if (onExit != null) View.VISIBLE else View.GONE
        renderAll()
        handler.post(clockTick)
        handler.post(syncTick)
    }

    @Volatile private var stopped = false

    fun stop() {
        stopped = true // in-flight async callbacks (voice, sync, delete) become no-ops
        handler.removeCallbacksAndMessages(null)
        App.instance.removeConfigListener(configListener)
        App.instance.removeDataListener(dataListener)
        if (App.instance.activeBoard === this) App.instance.activeBoard = null
        runCatching { voice.stop(); voiceExec.shutdownNow() }
    }

    /** Returns true if an overlay was open and got closed (for Back handling). */
    fun closeOverlays(): Boolean {
        var closed = false
        if (pinOverlay.visibility == View.VISIBLE) {
            pinOverlay.visibility = View.GONE; closed = true
        }
        if (confirmOverlay.visibility == View.VISIBLE) {
            confirmOverlay.visibility = View.GONE; closed = true
        }
        if (choreOverlay.visibility == View.VISIBLE) {
            hideKeyboard()
            choreOverlay.visibility = View.GONE; closed = true
        }
        if (routineOverlay.visibility == View.VISIBLE) {
            hideKeyboard()
            routineOverlay.visibility = View.GONE; closed = true
        }
        if (mealAiOverlay.visibility == View.VISIBLE) {
            hideKeyboard()
            mealAiOverlay.visibility = View.GONE; closed = true
        }
        if (detailOverlay.visibility == View.VISIBLE) {
            detailOverlay.visibility = View.GONE; closed = true
        }
        if (addOverlay.visibility == View.VISIBLE) {
            hideKeyboard()
            addOverlay.visibility = View.GONE; closed = true
        }
        if (dayOverlay.visibility == View.VISIBLE) {
            dayOverlay.visibility = View.GONE; closed = true
        }
        if (settingsOverlay.visibility == View.VISIBLE) {
            settingsOverlay.visibility = View.GONE; closed = true
        }
        if (voiceOverlay.visibility == View.VISIBLE) {
            voice.stop(); voiceOverlay.visibility = View.GONE; voiceBusy = false; closed = true
        }
        return closed
    }

    // ----------------------------------------------------------- voice input

    private fun startVoice() {
        if (!Gemini.isReady(ctx)) {
            showVoice("Voice needs AI", "Turn on AI and add a Gemini key in Settings first.", "Close")
            return
        }
        if (voiceBusy) return
        val act = baseCtx as? android.app.Activity
        if (act != null && act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            runCatching { act.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 71) }
            showVoice("Allow the mic", "Tap Allow, then tap the 🎤 again.", "Close")
            return
        }
        voiceBusy = true
        showVoice("Listening…", "Say it, then pause — e.g. \"add dentist Tuesday at 3\".", "Stop")
        voiceExec.execute {
            val wav = runCatching { voice.record() }.getOrNull()
            if (wav == null) { post { finishVoice("Didn't catch that", "Tap 🎤 to try again.") }; return@execute }
            post { if (voiceBusy) { voiceState.text = "Thinking…"; voiceSub.text = ""; voiceBtn.visibility = View.GONE } }
            try {
                val b64 = android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP)
                val res = Gemini.voiceCommand(ctx, b64)
                val transcript = res.optString("transcript").trim()
                if (transcript.isEmpty()) {
                    post { finishVoice("Didn't catch that", "Tap 🎤 to try again.") }
                    return@execute
                }
                val applyRes = org.json.JSONObject(Gemini.applyProposals(ctx, res))
                val applied = applyRes.optInt("applied")
                val failed = applyRes.optJSONArray("errors")?.length() ?: 0
                // Don't parrot the model's optimistic reply if things actually
                // failed (e.g. events need a connected calendar — common).
                val reply = when {
                    failed > 0 && applied > 0 ->
                        "Added $applied, but $failed couldn't be saved — events need a connected calendar (Settings → Two-way sync)."
                    failed > 0 ->
                        "I heard you, but couldn't save that — events need a connected calendar (Settings → Two-way sync)."
                    else -> res.optString("reply").ifEmpty {
                        if (applied > 0) "Done — added $applied." else "Okay."
                    }
                }
                post {
                    App.instance.notifyDataChanged()
                    doSync() // surface a new event/item on the board right away
                    finishVoice("“$transcript”", reply)
                }
            } catch (e: Exception) {
                post { finishVoice("Sorry — that didn't work", e.message ?: "Try again.") }
            }
        }
    }

    /** Run on the UI thread only if this board is still alive (post-rebuild safe). */
    private fun post(action: () -> Unit) { handler.post(Runnable { if (!stopped) action() }) }

    /** The overlay's button: Stop ends recording early; otherwise it dismisses. */
    private fun onVoiceButton() {
        if (voice.running) voice.stop() else hideVoice()
    }

    private fun showVoice(state: String, sub: String, btn: String) {
        voiceState.text = state
        voiceSub.text = sub
        voiceBtn.text = btn
        voiceBtn.visibility = View.VISIBLE
        voiceOverlay.visibility = View.VISIBLE
    }

    private fun finishVoice(state: String, sub: String) {
        voiceBusy = false
        voiceState.text = state
        voiceSub.text = sub
        voiceBtn.text = "Done"
        voiceBtn.visibility = View.VISIBLE
        // Auto-dismiss after a few seconds — but only THIS confirmation; a token
        // guards against a stale timer closing a newer voice session.
        val token = ++voiceSession
        handler.postDelayed({ if (!voiceBusy && voiceSession == token) hideVoice() }, 6000)
    }
    private var voiceSession = 0

    private fun hideVoice() {
        voiceOverlay.visibility = View.GONE
        voiceBusy = false
    }

    // ---------------------------------------------------------------- sync

    private fun doSync() {
        Thread { Weather.maybeRefresh(ctx) }.start()
        App.instance.kickTasksSync(0)
        if (store.feeds().isEmpty()) {
            events = emptyList()
            statusLine = "No calendars configured yet"
            renderAll()
            return
        }
        sync.requestSync { evs, problems ->
            if (stopped) return@requestSync // board rebuilt mid-sync — don't touch dead views
            events = evs
            App.instance.lastEvents = evs
            App.instance.lastSyncAt = System.currentTimeMillis()
            App.instance.lastSyncProblems = problems
            statusLine = if (problems.isEmpty())
                "Updated " + timeFormat().format(Calendar.getInstance().time)
            else problems.joinToString("\n")
            renderAll()
        }
    }

    // ------------------------------------------------------------------ ui

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private lateinit var contentRow: LinearLayout

    /** Portrait when explicitly chosen, or when "auto" and the screen is tall. */
    private fun isPortrait(): Boolean = when (store.orientation()) {
        "portrait" -> true
        "auto" -> ctx.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        else -> false
    }

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(BG)

        val row = LinearLayout(ctx)
        root.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        contentRow = row

        if (isPortrait()) {
            // Tall layout: the sidebar becomes a top strip (fixed height so its
            // weighted "today" scroll still has room), calendar fills below.
            row.orientation = LinearLayout.VERTICAL
            row.addView(buildSidebar(), LinearLayout.LayoutParams(MATCH, dp(360)))
            row.addView(buildMainArea(), LinearLayout.LayoutParams(MATCH, 0, 1f))
        } else {
            row.orientation = LinearLayout.HORIZONTAL
            row.addView(buildSidebar(), LinearLayout.LayoutParams(dp(300), MATCH))
            row.addView(buildMainArea(), LinearLayout.LayoutParams(0, MATCH, 1f))
        }

        settingsOverlay = buildSettingsOverlay()
        root.addView(settingsOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        dayOverlay = buildDayOverlay()
        root.addView(dayOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        addOverlay = buildAddOverlay()
        root.addView(addOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        detailOverlay = buildDetailOverlay()
        root.addView(detailOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        choreOverlay = buildChoreOverlay()
        root.addView(choreOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        routineOverlay = buildRoutineOverlay()
        root.addView(routineOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        mealAiOverlay = buildMealAiOverlay()
        root.addView(mealAiOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        confirmOverlay = buildConfirmOverlay()
        root.addView(confirmOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        pinOverlay = buildPinOverlay()
        root.addView(pinOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Tap-to-talk mic — floats bottom-end, shown only when AI is ready.
        micFab = TextView(ctx).apply {
            text = "🎤"
            textSize = 26f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ACCENT) }
            elevation = dp(6).toFloat()
            visibility = if (Gemini.isReady(ctx)) View.VISIBLE else View.GONE
            setOnClickListener { requirePin { startVoice() } }
        }
        root.addView(micFab, FrameLayout.LayoutParams(dp(60), dp(60)).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = dp(22); bottomMargin = dp(22)
        })
        voiceOverlay = buildVoiceOverlay()
        root.addView(voiceOverlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // Topmost, touch-transparent layer for chore-completion confetti.
        celebration = CelebrationView(ctx)
        root.addView(celebration, FrameLayout.LayoutParams(MATCH, MATCH))
        // Above everything: the remote-navigation focus outline (Portal TV).
        // Touch-transparent and invisible during touch use.
        root.addView(FocusHighlight(ctx), FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    /** Fire the celebration burst centered on a just-completed chore card. */
    private fun celebrateAt(anchor: View, goalReached: Boolean) {
        if (!store.choreEffects() || !::celebration.isInitialized) return
        val a = IntArray(2); anchor.getLocationInWindow(a)
        val c = IntArray(2); celebration.getLocationInWindow(c)
        celebration.burst(
            (a[0] - c[0] + anchor.width / 2).toFloat(),
            (a[1] - c[1] + anchor.height / 2).toFloat(),
            goalReached)
    }

    private fun buildVoiceOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { } // swallow taps behind the card
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, Palette.R_CARD)
            setPadding(dp(36), dp(32), dp(36), dp(28))
        }
        card.addView(TextView(ctx).apply {
            text = "🎤"; textSize = 40f; gravity = Gravity.CENTER
        })
        voiceState = TextView(ctx).apply {
            textSize = 22f; setTextColor(INK); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(voiceState, lpMatchWrap(top = dp(12)))
        voiceSub = TextView(ctx).apply {
            textSize = 15f; setTextColor(MUTED); gravity = Gravity.CENTER
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(voiceSub, lpMatchWrap(top = dp(8)))
        voiceBtn = accentButton("Stop") { onVoiceButton() }
        card.addView(voiceBtn, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = dp(20); gravity = Gravity.CENTER_HORIZONTAL
        })
        scrim.addView(card, FrameLayout.LayoutParams(dp(420), WRAP).apply {
            gravity = Gravity.CENTER
        })
        return scrim
    }

    // ------------------------------------------------------ chore composer

    private lateinit var choreOverlay: FrameLayout
    private lateinit var choreTitleInput: EditText
    private lateinit var choreMemberRow: LinearLayout
    private lateinit var choreBankRow: LinearLayout
    private val choreSelectedIds = HashSet<String>()
    private lateinit var choreRepeatBtn: TextView
    private lateinit var choreOnceBtn: TextView
    private lateinit var choreDaysRow: LinearLayout
    private lateinit var choreDateRow: LinearLayout
    private lateinit var choreDateLabel: TextView
    private lateinit var choreMsg: TextView
    private var choreIcon = "⭐"
    private var choreOneTime = false
    private var choreDate: Calendar = Calendar.getInstance()
    private val choreDays = sortedSetOf(1, 2, 3, 4, 5, 6, 7)
    // lateinit, NOT `= emptyList()`: these fields are declared after the init
    // block that builds the UI, so a default initializer would run AFTER
    // buildChoreOverlay() and wipe the chip references it stored.
    private lateinit var choreDayChips: List<TextView>

    private fun showChoreOverlay() {
        val members = Members.all(ctx)
        if (members.isEmpty()) {
            confirm("No family members yet", "Add them on the setup page (⚙ shows the address)", "OK") {}
            return
        }
        choreTitleInput.setText("")
        choreIcon = "⭐"
        choreSelectedIds.clear()
        members.firstOrNull()?.let { choreSelectedIds.add(it.id) }
        choreOneTime = false
        choreDate = Calendar.getInstance()
        choreDays.clear(); choreDays.addAll(1..7)
        choreMsg.text = ""
        refreshChoreOverlay()
        choreOverlay.visibility = View.VISIBLE
    }

    private fun refreshChoreOverlay() {
        // Learned suggestions (≥2 past adds) lead; static presets fill in after.
        choreBankRow.removeAllViews()
        val picks = ArrayList<Pair<String, String>>()
        runCatching {
            val sugg = org.json.JSONObject(Chores.statusJson(ctx)).optJSONArray("suggestions")
            for (i in 0 until (sugg?.length() ?: 0)) {
                val s = sugg!!.getJSONObject(i)
                picks.add(s.optString("icon") to s.optString("title"))
            }
        }
        for (preset in CHORE_BANK) {
            if (picks.none { MagicWords.fuzzyEquals(it.second.lowercase(), preset.second.lowercase()) })
                picks.add(preset)
        }
        for ((icon, title) in picks) {
            choreBankRow.addView(TextView(ctx).apply {
                text = "$icon $title"
                textSize = 13f
                setTextColor(INK)
                background = rounded(PILL, 16)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    choreIcon = icon
                    choreTitleInput.setText(title)
                    refreshChoreOverlay()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }

        val members = Members.all(ctx)
        choreMemberRow.removeAllViews()
        for (m in members) {
            val selected = choreSelectedIds.contains(m.id)
            choreMemberRow.addView(TextView(ctx).apply {
                text = m.name
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(if (selected) m.color else PILL, 18)
                setTextColor(if (selected) Color.WHITE else INK)
                setPadding(dp(16), dp(7), dp(16), dp(7))
                setOnClickListener {
                    if (!choreSelectedIds.remove(m.id)) choreSelectedIds.add(m.id)
                    refreshChoreOverlay()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        choreRepeatBtn.background = rounded(if (!choreOneTime) ACCENT else PILL, 18)
        choreRepeatBtn.setTextColor(if (!choreOneTime) Color.WHITE else INK)
        choreOnceBtn.background = rounded(if (choreOneTime) ACCENT else PILL, 18)
        choreOnceBtn.setTextColor(if (choreOneTime) Color.WHITE else INK)
        choreDaysRow.visibility = if (choreOneTime) View.GONE else View.VISIBLE
        choreDateRow.visibility = if (choreOneTime) View.VISIBLE else View.GONE
        choreDateLabel.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(choreDate.time)
        choreDayChips.forEachIndexed { i, chip ->
            val dow = chip.tag as Int
            val on = choreDays.contains(dow)
            chip.background = rounded(if (on) ACCENT else PILL, 16)
            chip.setTextColor(if (on) Color.WHITE else INK)
        }
    }

    private fun buildChoreOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { hideKeyboard(); visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(20), dp(28), dp(16))
            isClickable = true
        }
        card.addView(TextView(ctx).apply {
            text = "New chore"
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(10)))

        // Quick-pick bank: the family's learned favorites first, then presets.
        choreBankRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(choreBankRow)
        }, LinearLayout.LayoutParams(dp(520), WRAP).apply { bottomMargin = dp(12) })

        choreTitleInput = EditText(ctx).apply {
            hint = "Or type a chore…"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            background = roundedStroke(Palette.FIELD, 12, dp(1), Palette.FIELD_STROKE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        card.addView(choreTitleInput, LinearLayout.LayoutParams(dp(520), WRAP).apply { bottomMargin = dp(12) })

        // Who + when ---------------------------------------------------------
        val whoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        whoRow.addView(TextView(ctx).apply {
            text = "For"
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(50), WRAP)
        })
        choreMemberRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        whoRow.addView(choreMemberRow, LinearLayout.LayoutParams(0, WRAP, 1f))
        whoRow.addView(spacer(dp(8)))
        choreRepeatBtn = TextView(ctx).apply {
            text = "Repeats"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { choreOneTime = false; refreshChoreOverlay() }
        }
        choreOnceBtn = TextView(ctx).apply {
            text = "One time"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { choreOneTime = true; refreshChoreOverlay() }
        }
        whoRow.addView(choreRepeatBtn)
        whoRow.addView(spacer(dp(6)))
        whoRow.addView(choreOnceBtn)
        card.addView(whoRow, lpMatchWrap(bottom = dp(10)))

        // Weekday chips (repeating) ------------------------------------------
        choreDaysRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        val dowCal = Calendar.getInstance()
        while (dowCal.get(Calendar.DAY_OF_WEEK) != store.weekStartResolved())
            dowCal.add(Calendar.DAY_OF_MONTH, -1)
        val dowFmt = SimpleDateFormat("EEEEE", Locale.getDefault())
        repeat(7) {
            val dow = dowCal.get(Calendar.DAY_OF_WEEK)
            val chip = TextView(ctx).apply {
                text = dowFmt.format(dowCal.time)
                textSize = 14f
                gravity = Gravity.CENTER
                tag = dow
                setOnClickListener {
                    if (!choreDays.remove(dow)) choreDays.add(dow)
                    refreshChoreOverlay()
                }
            }
            choreDaysRow.addView(chip, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(6)
            })
            chips.add(chip)
            dowCal.add(Calendar.DAY_OF_MONTH, 1)
        }
        choreDayChips = chips
        card.addView(choreDaysRow, lpMatchWrap(bottom = dp(8)))

        // Date stepper (one-time) --------------------------------------------
        choreDateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        choreDateRow.addView(navButton("‹") {
            choreDate.add(Calendar.DAY_OF_MONTH, -1); refreshChoreOverlay()
        })
        choreDateLabel = TextView(ctx).apply {
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        choreDateRow.addView(choreDateLabel, LinearLayout.LayoutParams(dp(170), WRAP))
        choreDateRow.addView(navButton("›") {
            choreDate.add(Calendar.DAY_OF_MONTH, 1); refreshChoreOverlay()
        })
        card.addView(choreDateRow, lpMatchWrap(bottom = dp(8)))

        choreMsg = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ACCENT)
            minHeight = dp(18)
        }
        card.addView(choreMsg, lpMatchWrap())

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(navButton("Cancel") { hideKeyboard(); choreOverlay.visibility = View.GONE })
        buttons.addView(accentButton("Add chore") { saveNewChore() })
        card.addView(buttons, lpMatchWrap(top = dp(8)))

        scrim.addView(overlayScroll(card), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun saveNewChore() {
        val title = choreTitleInput.text.toString().trim()
        if (title.isEmpty()) { choreMsg.text = "Pick from the bank or type a chore"; return }
        if (!choreOneTime && choreDays.isEmpty()) { choreMsg.text = "Pick at least one day"; return }
        if (choreSelectedIds.isEmpty()) { choreMsg.text = "Pick at least one person"; return }
        runCatching {
            val action = org.json.JSONObject()
                .put("action", "addChore")
                .put("title", title)
                .put("icon", choreIcon)
                .put("memberIds", org.json.JSONArray(choreSelectedIds.toList()))
            if (choreOneTime) {
                action.put("oneTime", true)
                action.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(choreDate.time))
            } else {
                action.put("days", org.json.JSONArray(choreDays.toList()))
            }
            Chores.mutate(ctx, action)
        }.onSuccess {
            hideKeyboard()
            choreOverlay.visibility = View.GONE
        }.onFailure {
            choreMsg.text = it.message ?: "Couldn't add the chore"
        }
    }

    // ------------------------------------------------------ routine composer

    private lateinit var routineOverlay: FrameLayout
    private lateinit var routineTitleInput: EditText
    private lateinit var routineMemberRow: LinearLayout
    private lateinit var routineBankRow: LinearLayout
    private val routineSelectedIds = HashSet<String>()
    private lateinit var routineSectionRow: LinearLayout
    private lateinit var routineRepeatBtn: TextView
    private lateinit var routineOnceBtn: TextView
    private lateinit var routineDaysRow: LinearLayout
    private lateinit var routineDateRow: LinearLayout
    private lateinit var routineDateLabel: TextView
    private lateinit var routineMsg: TextView
    private var routineIcon = "✅"
    private var routineSection = "morning"
    private var routineOneTime = false
    private var routineDate: Calendar = Calendar.getInstance()
    private val routineDays = sortedSetOf(1, 2, 3, 4, 5, 6, 7)
    private lateinit var routineDayChips: List<TextView>
    private lateinit var routineSectionChips: List<TextView>

    private fun showRoutineOverlay() {
        val members = Members.all(ctx)
        if (members.isEmpty()) {
            confirm("No family members yet", "Add them on the setup page (⚙ shows the address)", "OK") {}
            return
        }
        routineTitleInput.setText("")
        routineIcon = "✅"
        routineSection = "morning"
        routineSelectedIds.clear()
        members.firstOrNull()?.let { routineSelectedIds.add(it.id) }
        routineOneTime = false
        routineDate = Calendar.getInstance()
        routineDays.clear(); routineDays.addAll(1..5) // default school days
        routineMsg.text = ""
        refreshRoutineOverlay()
        routineOverlay.visibility = View.VISIBLE
    }

    private fun refreshRoutineOverlay() {
        routineBankRow.removeAllViews()
        for ((icon, title) in ROUTINE_BANK) {
            routineBankRow.addView(TextView(ctx).apply {
                text = "$icon $title"
                textSize = 13f
                setTextColor(INK)
                background = rounded(PILL, 16)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener {
                    routineIcon = icon
                    routineTitleInput.setText(title)
                    refreshRoutineOverlay()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
        }

        val members = Members.all(ctx)
        routineMemberRow.removeAllViews()
        for (m in members) {
            val selected = routineSelectedIds.contains(m.id)
            routineMemberRow.addView(TextView(ctx).apply {
                text = m.name
                textSize = 14f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(if (selected) m.color else PILL, 18)
                setTextColor(if (selected) Color.WHITE else INK)
                setPadding(dp(16), dp(7), dp(16), dp(7))
                setOnClickListener {
                    if (!routineSelectedIds.remove(m.id)) routineSelectedIds.add(m.id)
                    refreshRoutineOverlay()
                }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }

        routineSectionChips.forEach { chip ->
            val key = chip.tag as String
            val on = routineSection == key
            chip.background = rounded(if (on) ACCENT else PILL, 16)
            chip.setTextColor(if (on) Color.WHITE else INK)
        }

        routineRepeatBtn.background = rounded(if (!routineOneTime) ACCENT else PILL, 18)
        routineRepeatBtn.setTextColor(if (!routineOneTime) Color.WHITE else INK)
        routineOnceBtn.background = rounded(if (routineOneTime) ACCENT else PILL, 18)
        routineOnceBtn.setTextColor(if (routineOneTime) Color.WHITE else INK)
        routineDaysRow.visibility = if (routineOneTime) View.GONE else View.VISIBLE
        routineDateRow.visibility = if (routineOneTime) View.VISIBLE else View.GONE
        routineDateLabel.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(routineDate.time)
        routineDayChips.forEach { chip ->
            val dow = chip.tag as Int
            val on = routineDays.contains(dow)
            chip.background = rounded(if (on) ACCENT else PILL, 16)
            chip.setTextColor(if (on) Color.WHITE else INK)
        }
    }

    private fun buildRoutineOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { hideKeyboard(); visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(20), dp(28), dp(16))
            isClickable = true
        }
        card.addView(TextView(ctx).apply {
            text = "New routine item"
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(10)))

        routineBankRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(routineBankRow)
        }, LinearLayout.LayoutParams(dp(520), WRAP).apply { bottomMargin = dp(12) })

        routineTitleInput = EditText(ctx).apply {
            hint = "Or type an item…"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            background = roundedStroke(Palette.FIELD, 12, dp(1), Palette.FIELD_STROKE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        card.addView(routineTitleInput, LinearLayout.LayoutParams(dp(520), WRAP).apply { bottomMargin = dp(12) })

        // Time-of-day section chips -----------------------------------------
        val sectionWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sectionWrap.addView(TextView(ctx).apply {
            text = "When"
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(50), WRAP)
        })
        routineSectionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val sChips = ArrayList<TextView>()
        for ((key, icon) in Routines.SECTIONS) {
            val chip = TextView(ctx).apply {
                text = "$icon ${key.replaceFirstChar { it.uppercase() }}"
                textSize = 13f
                gravity = Gravity.CENTER
                tag = key
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { routineSection = key; refreshRoutineOverlay() }
            }
            routineSectionRow.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(6) })
            sChips.add(chip)
        }
        routineSectionChips = sChips
        sectionWrap.addView(android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(routineSectionRow)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        card.addView(sectionWrap, lpMatchWrap(bottom = dp(10)))

        // Who + repeat/once --------------------------------------------------
        val whoRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        whoRow.addView(TextView(ctx).apply {
            text = "For"
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(50), WRAP)
        })
        routineMemberRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        whoRow.addView(routineMemberRow, LinearLayout.LayoutParams(0, WRAP, 1f))
        whoRow.addView(spacer(dp(8)))
        routineRepeatBtn = TextView(ctx).apply {
            text = "Repeats"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { routineOneTime = false; refreshRoutineOverlay() }
        }
        routineOnceBtn = TextView(ctx).apply {
            text = "One time"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { routineOneTime = true; refreshRoutineOverlay() }
        }
        whoRow.addView(routineRepeatBtn)
        whoRow.addView(spacer(dp(6)))
        whoRow.addView(routineOnceBtn)
        card.addView(whoRow, lpMatchWrap(bottom = dp(10)))

        // Weekday chips (repeating) ------------------------------------------
        routineDaysRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = ArrayList<TextView>()
        val dowCal = Calendar.getInstance()
        while (dowCal.get(Calendar.DAY_OF_WEEK) != store.weekStartResolved())
            dowCal.add(Calendar.DAY_OF_MONTH, -1)
        val dowFmt = SimpleDateFormat("EEEEE", Locale.getDefault())
        repeat(7) {
            val dow = dowCal.get(Calendar.DAY_OF_WEEK)
            val chip = TextView(ctx).apply {
                text = dowFmt.format(dowCal.time)
                textSize = 14f
                gravity = Gravity.CENTER
                tag = dow
                setOnClickListener {
                    if (!routineDays.remove(dow)) routineDays.add(dow)
                    refreshRoutineOverlay()
                }
            }
            routineDaysRow.addView(chip, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(6)
            })
            chips.add(chip)
            dowCal.add(Calendar.DAY_OF_MONTH, 1)
        }
        routineDayChips = chips
        card.addView(routineDaysRow, lpMatchWrap(bottom = dp(8)))

        // Date stepper (one-time) --------------------------------------------
        routineDateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        routineDateRow.addView(navButton("‹") {
            routineDate.add(Calendar.DAY_OF_MONTH, -1); refreshRoutineOverlay()
        })
        routineDateLabel = TextView(ctx).apply {
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        routineDateRow.addView(routineDateLabel, LinearLayout.LayoutParams(dp(170), WRAP))
        routineDateRow.addView(navButton("›") {
            routineDate.add(Calendar.DAY_OF_MONTH, 1); refreshRoutineOverlay()
        })
        card.addView(routineDateRow, lpMatchWrap(bottom = dp(8)))

        routineMsg = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ACCENT)
            minHeight = dp(18)
        }
        card.addView(routineMsg, lpMatchWrap())

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(navButton("Cancel") { hideKeyboard(); routineOverlay.visibility = View.GONE })
        buttons.addView(accentButton("Add item") { saveNewRoutine() })
        card.addView(buttons, lpMatchWrap(top = dp(8)))

        scrim.addView(overlayScroll(card), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun saveNewRoutine() {
        val title = routineTitleInput.text.toString().trim()
        if (title.isEmpty()) { routineMsg.text = "Pick from the bank or type an item"; return }
        if (!routineOneTime && routineDays.isEmpty()) { routineMsg.text = "Pick at least one day"; return }
        if (routineSelectedIds.isEmpty()) { routineMsg.text = "Pick at least one person"; return }
        runCatching {
            val action = org.json.JSONObject()
                .put("action", "addItem")
                .put("title", title)
                .put("icon", routineIcon)
                .put("section", routineSection)
                .put("memberIds", org.json.JSONArray(routineSelectedIds.toList()))
            if (routineOneTime) {
                action.put("oneTime", true)
                action.put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(routineDate.time))
            } else {
                action.put("days", org.json.JSONArray(routineDays.toList()))
            }
            Routines.mutate(ctx, action)
        }.onSuccess {
            hideKeyboard()
            routineOverlay.visibility = View.GONE
        }.onFailure {
            routineMsg.text = it.message ?: "Couldn't add the item"
        }
    }

    // ------------------------------------------------------ AI meal planner

    private lateinit var mealAiOverlay: FrameLayout
    private lateinit var mealDishInput: EditText
    private lateinit var mealDateLabel: TextView
    private lateinit var mealSlotRow: LinearLayout
    private lateinit var mealGroceriesToggle: TextView
    private lateinit var mealAiMsg: TextView
    private var mealDate: Calendar = Calendar.getInstance()
    private var mealSlot = "dinner"
    private var mealGroceries = true
    private var mealAiBusy = false

    private fun showMealAiOverlay() {
        mealDishInput.setText("")
        mealDate = Calendar.getInstance()
        mealSlot = "dinner"
        mealGroceries = true
        mealAiBusy = false
        mealAiMsg.text = ""
        refreshMealAiOverlay()
        mealAiOverlay.visibility = View.VISIBLE
        mealDishInput.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(mealDishInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun refreshMealAiOverlay() {
        mealDateLabel.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(mealDate.time)
        for (i in 0 until mealSlotRow.childCount) {
            val chip = mealSlotRow.getChildAt(i) as TextView
            val on = chip.tag == mealSlot
            chip.background = rounded(if (on) ACCENT else PILL, 18)
            chip.setTextColor(if (on) Color.WHITE else INK)
        }
        mealGroceriesToggle.background = rounded(if (mealGroceries) ACCENT else PILL, 18)
        mealGroceriesToggle.setTextColor(if (mealGroceries) Color.WHITE else INK)
    }

    private fun buildMealAiOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { hideKeyboard(); visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(20), dp(28), dp(16))
            isClickable = true
        }
        card.addView(TextView(ctx).apply {
            text = "✨ Plan a meal"
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(4)))
        card.addView(TextView(ctx).apply {
            text = "AI writes the recipe, puts it on the menu, and makes a\nshopping list just for it — check off what's already at home."
            textSize = 13f
            setTextColor(MUTED)
        }, lpMatchWrap(bottom = dp(12)))

        mealDishInput = EditText(ctx).apply {
            hint = "What are we making?"
            textSize = 16f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            background = roundedStroke(Palette.FIELD, 12, dp(1), Palette.FIELD_STROKE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        card.addView(mealDishInput, LinearLayout.LayoutParams(dp(480), WRAP).apply { bottomMargin = dp(12) })

        val dateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dateRow.addView(navButton("‹") {
            mealDate.add(Calendar.DAY_OF_MONTH, -1); refreshMealAiOverlay()
        })
        mealDateLabel = TextView(ctx).apply {
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        dateRow.addView(mealDateLabel, LinearLayout.LayoutParams(dp(170), WRAP))
        dateRow.addView(navButton("›") {
            mealDate.add(Calendar.DAY_OF_MONTH, 1); refreshMealAiOverlay()
        })
        card.addView(dateRow, lpMatchWrap(bottom = dp(10)))

        mealSlotRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((slot, label) in listOf("breakfast" to "Breakfast", "lunch" to "Lunch",
                                      "dinner" to "Dinner", "snack" to "Snack")) {
            mealSlotRow.addView(TextView(ctx).apply {
                text = label
                tag = slot
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { mealSlot = slot; refreshMealAiOverlay() }
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        card.addView(mealSlotRow, lpMatchWrap(bottom = dp(10)))

        mealGroceriesToggle = TextView(ctx).apply {
            text = "🧺 Make a shopping list for it"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setOnClickListener { mealGroceries = !mealGroceries; refreshMealAiOverlay() }
        }
        card.addView(mealGroceriesToggle, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(6) })

        mealAiMsg = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ACCENT)
            minHeight = dp(18)
        }
        card.addView(mealAiMsg, lpMatchWrap())

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(navButton("Cancel") { hideKeyboard(); mealAiOverlay.visibility = View.GONE })
        buttons.addView(accentButton("Generate") { saveAiMeal() })
        card.addView(buttons, lpMatchWrap(top = dp(8)))

        scrim.addView(overlayScroll(card), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun saveAiMeal() {
        if (mealAiBusy) return
        val dish = mealDishInput.text.toString().trim()
        if (dish.isEmpty()) { mealAiMsg.text = "What are we making?"; return }
        mealAiBusy = true
        mealAiMsg.text = "✨ Writing the recipe…"
        hideKeyboard()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(mealDate.time)
        Thread {
            try {
                val res = org.json.JSONObject(
                    Gemini.planMeal(ctx, dish, date, mealSlot, mealGroceries))
                post {
                    mealAiBusy = false
                    mealAiOverlay.visibility = View.GONE
                    statusLine = "Planned “${res.optString("title")}”" +
                        if (res.optInt("groceriesAdded") > 0)
                            " · shopping list in Lists" else ""
                    renderAll()
                }
            } catch (e: Exception) {
                post {
                    mealAiBusy = false
                    mealAiMsg.text = e.message ?: "Couldn't plan the meal"
                }
            }
        }.start()
    }

    // ------------------------------------------------------ confirm dialog

    private lateinit var confirmOverlay: FrameLayout
    private lateinit var confirmTitle: TextView
    private lateinit var confirmMsg: TextView
    private lateinit var confirmButton: TextView
    private var confirmAction: (() -> Unit)? = null

    private fun confirm(title: String, message: String, button: String, action: () -> Unit) {
        confirmTitle.text = title
        confirmMsg.text = message
        confirmButton.text = button
        confirmAction = action
        confirmOverlay.visibility = View.VISIBLE
    }

    private fun buildConfirmOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(22), dp(28), dp(14))
            isClickable = true
            minimumWidth = dp(380)
        }
        confirmTitle = TextView(ctx).apply {
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(confirmTitle)
        confirmMsg = TextView(ctx).apply {
            textSize = 15f
            setTextColor(MUTED)
        }
        card.addView(confirmMsg, lpMatchWrap(top = dp(8)))
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(navButton("Cancel") { confirmOverlay.visibility = View.GONE })
        confirmButton = accentButton("Remove") {
            confirmOverlay.visibility = View.GONE
            confirmAction?.invoke()
            confirmAction = null
        }
        buttons.addView(confirmButton)
        card.addView(buttons, lpMatchWrap(top = dp(14)))
        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    // ------------------------------------------------------------ kid lock

    private lateinit var pinOverlay: FrameLayout
    private lateinit var pinDots: TextView
    private lateinit var pinTitle: TextView
    private var pinEntry = ""
    private var pinExpected = ""
    private var pinAction: (() -> Unit)? = null

    /** Parent gate: runs [action] directly when no kid-lock PIN is set. */
    private fun requirePin(action: () -> Unit) =
        askPin(store.pin(), "Parents only 🔒", action)

    /** Generic gate — any expected PIN (e.g. a kid's own chore PIN). */
    private fun askPin(expected: String, title: String, action: () -> Unit) {
        if (expected.isEmpty()) { action(); return }
        pinExpected = expected
        pinTitle.text = title
        pinEntry = ""
        pinAction = action
        updatePinDots()
        pinOverlay.visibility = View.VISIBLE
    }

    private fun buildPinOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(34), dp(22), dp(34), dp(18))
            isClickable = true
        }
        pinTitle = TextView(ctx).apply {
            text = "Parents only 🔒"
            textSize = 18f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(pinTitle, lpMatchWrap(bottom = dp(10)))
        pinDots = TextView(ctx).apply {
            textSize = 26f
            setTextColor(INK)
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        card.addView(pinDots, lpMatchWrap(bottom = dp(12)))

        val keys = listOf("1","2","3","4","5","6","7","8","9","⌫","0","✕")
        for (r in 0..3) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (c in 0..2) {
                val label = keys[r * 3 + c]
                row.addView(TextView(ctx).apply {
                    text = label
                    textSize = 22f
                    setTextColor(INK)
                    gravity = Gravity.CENTER
                    background = rounded(PILL, 30)
                    setOnClickListener { pinKey(label) }
                }, LinearLayout.LayoutParams(dp(62), dp(62)).apply {
                    leftMargin = dp(5); rightMargin = dp(5); topMargin = dp(5); bottomMargin = dp(5)
                })
            }
            card.addView(row)
        }
        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun pinKey(label: String) {
        when (label) {
            "✕" -> { pinOverlay.visibility = View.GONE; return }
            "⌫" -> pinEntry = pinEntry.dropLast(1)
            else -> if (pinEntry.length < 4) pinEntry += label
        }
        updatePinDots()
        if (pinEntry.length == 4) {
            // The parent kid-lock PIN always works as an override.
            if (pinEntry == pinExpected || (store.pin().isNotEmpty() && pinEntry == store.pin())) {
                pinOverlay.visibility = View.GONE
                pinAction?.invoke()
                pinAction = null
            } else {
                pinDots.setTextColor(ACCENT)
                handler.postDelayed({
                    pinEntry = ""
                    pinDots.setTextColor(INK)
                    updatePinDots()
                }, 450)
            }
        }
    }

    private fun updatePinDots() {
        pinDots.text = "●".repeat(pinEntry.length) + "○".repeat(4 - pinEntry.length)
    }

    /** Back to the calendar tab, week view, current week (idle takeover lands here). */
    fun resetView() {
        weekOffset = 0
        monthOffset = 0
        dayOffset = 0
        scheduleOffset = 0
        mealsTab.reset() // its private week offset must come home too
        closeOverlays()
        if (currentTab != 0) setTab(0)
        setView(store.defaultView())
    }

    private fun buildSidebar(): View {
        val side = LinearLayout(ctx)
        side.orientation = LinearLayout.VERTICAL
        side.setBackgroundColor(CARD)
        side.elevation = dp(4).toFloat()
        side.setPadding(dp(24), dp(24), dp(24), dp(14))

        clockText = TextView(ctx).apply {
            textSize = 52f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        dateText = TextView(ctx).apply {
            textSize = 17f
            setTextColor(MUTED)
        }
        side.addView(clockText)
        side.addView(dateText)
        weatherText = TextView(ctx).apply {
            textSize = 15f
            setTextColor(INK)
            visibility = View.GONE
        }
        side.addView(weatherText, lpMatchWrap(top = dp(8)))

        side.addView(View(ctx).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(MATCH, dp(1)).apply {
                topMargin = dp(18); bottomMargin = dp(16)
            })

        side.addView(TextView(ctx).apply {
            text = "TODAY"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        })

        todayList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(todayList, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        side.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(6) })

        legendList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        side.addView(legendList, lpMatchWrap(top = dp(8)))

        statusText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(FAINT)
            maxLines = 3
        }
        side.addView(statusText, lpMatchWrap(top = dp(8)))

        val bottomRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bottomRow.addView(TextView(ctx).apply {
            text = "⚙ Settings"
            textSize = 14f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48) // wall-display touch target (1dp = 1px here)
            setPadding(0, dp(8), dp(12), dp(2))
            setOnClickListener { requirePin { showSettingsOverlay() } }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        exitButton = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            minimumWidth = dp(48)
            setPadding(dp(12), dp(8), dp(4), dp(2))
            setOnClickListener { onExit?.invoke() }
        }
        bottomRow.addView(exitButton)
        side.addView(bottomRow)
        return side
    }

    private fun buildMainArea(): View {
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Top-level tab strip ---------------------------------------------
        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val tabs = ArrayList<TextView>()
        listOf("Calendar", "Chores", "Lists", "Meals", "Routines").forEachIndexed { i, label ->
            val t = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minimumHeight = dp(48) // comfortable tap target at arm's length
                setPadding(dp(18), dp(8), dp(18), dp(8))
                setOnClickListener { setTab(i) }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                rightMargin = dp(8)
            })
            tabs.add(t)
        }
        tabButtons = tabs
        outer.addView(tabRow, lpMatchWrap())

        val area = FrameLayout(ctx)
        outer.addView(area, LinearLayout.LayoutParams(MATCH, 0, 1f))

        weekPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(16))
        }
        area.addView(weekPanel, FrameLayout.LayoutParams(MATCH, MATCH))

        // Other tabs ---------------------------------------------------------
        listsTab = ListsTab(ctx) { action -> requirePin(action) }
        area.addView(listsTab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        choresTab = ChoresTab(ctx,
            onAddChore = { requirePin { showChoreOverlay() } },
            onGate = { memberPin, memberName, action ->
                askPin(memberPin, "$memberName's PIN ⭐", action)
            },
            onRemoveChore = { id, label ->
                requirePin {
                    confirm("Remove this chore?", label, "Remove") {
                        runCatching {
                            Chores.mutate(ctx, org.json.JSONObject()
                                .put("action", "deleteChore").put("choreId", id))
                        }
                    }
                }
            },
            onCelebrate = { anchor, goalReached -> celebrateAt(anchor, goalReached) })
        area.addView(choresTab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        mealsTab = MealsTab(ctx, { title, body ->
            detailTitle.text = title
            detailBody.text = body
            detailOverlay.visibility = View.VISIBLE
        }, onPlanMeal = { requirePin { showMealAiOverlay() } })
        area.addView(mealsTab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        routinesTab = RoutinesTab(ctx,
            onAddItem = { requirePin { showRoutineOverlay() } },
            onGate = { memberPin, memberName, action ->
                askPin(memberPin, "$memberName's list ✅", action)
            },
            onRemoveItem = { id, label ->
                requirePin {
                    confirm("Remove this routine item?", label, "Remove") {
                        runCatching {
                            Routines.mutate(ctx, org.json.JSONObject()
                                .put("action", "deleteItem").put("itemId", id))
                        }
                    }
                }
            })
        area.addView(routinesTab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        tabPanels = listOf(weekPanel, choresTab.view, listsTab.view, mealsTab.view, routinesTab.view)

        // Nav row -------------------------------------------------------
        // Portrait is too narrow to fit the title + all controls on one line
        // (the weighted title gets starved to a sliver), so stack them.
        val portrait = isPortrait()
        monthLabel = TextView(ctx).apply {
            textSize = if (portrait) 23f else 27f
            setTextColor(INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(accentButton("+ Add") { requirePin { showAddOverlay(Calendar.getInstance()) } })
        controls.addView(spacer(dp(10)))
        val toggles = ArrayList<TextView>()
        listOf("Day", "Week", "Month", "Plan").forEachIndexed { i, label ->
            val t = navButton(label) { setView(i) }
            toggles.add(t)
            controls.addView(t)
        }
        viewToggles = toggles
        controls.addView(spacer(dp(10)))
        controls.addView(navButton("‹") { moveOffset(-1); renderCalendar() })
        controls.addView(navButton("Today") {
            weekOffset = 0; monthOffset = 0; dayOffset = 0; scheduleOffset = 0
            renderCalendar()
        })
        controls.addView(navButton("›") { moveOffset(1); renderCalendar() })

        if (portrait) {
            // Title on its own line; controls wrap below, scrollable if needed.
            weekPanel.addView(monthLabel, lpMatchWrap(bottom = dp(8)))
            weekPanel.addView(HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(controls)
            }, lpMatchWrap(bottom = dp(14)))
        } else {
            val nav = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nav.addView(monthLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
            nav.addView(controls)
            weekPanel.addView(nav, lpMatchWrap(bottom = dp(14)))
        }
        styleToggles()

        weekIsRows = portrait
        if (portrait) {
            // Portrait: the week is seven day-ROWS, stacked and scrollable, built
            // fresh each render. (See docs/PORTRAIT_DESIGN.md.) The column views
            // below aren't created; empty them so renderWeekColumns is skipped.
            dayNameViews = emptyList(); dayNumViews = emptyList(); dayWxViews = emptyList()
            dayColumnWraps = emptyList(); dayColumns = emptyList()
            weekRowsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            weekContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(ScrollView(ctx).apply {
                    isVerticalScrollBarEnabled = false
                    addView(weekRowsBox, FrameLayout.LayoutParams(MATCH, WRAP))
                }, LinearLayout.LayoutParams(MATCH, 0, 1f))
            }
        } else {
        // Day headers + columns (landscape) ------------------------------
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val columnsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val names = ArrayList<TextView>()
        val nums = ArrayList<TextView>()
        val wxList = ArrayList<TextView>()
        val columnWraps = ArrayList<LinearLayout>()
        val columns = ArrayList<LinearLayout>()
        for (i in 0..6) {
            val name = TextView(ctx).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val num = TextView(ctx).apply {
                textSize = 19f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            val wx = TextView(ctx).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
            }
            val headerCell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(2), 0, dp(6))
                addView(name, LinearLayout.LayoutParams(WRAP, WRAP))
                addView(num, LinearLayout.LayoutParams(dp(34), dp(34)).apply { topMargin = dp(3) })
                addView(wx, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
            }
            headerRow.addView(headerCell, LinearLayout.LayoutParams(0, WRAP, 1f))
            names.add(name)
            nums.add(num)
            wxList.add(wx)

            val chips = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(7), dp(8), dp(7), dp(8))
            }
            val colScroll = ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(chips, FrameLayout.LayoutParams(MATCH, WRAP))
            }
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                elevation = dp(2).toFloat()
                addView(colScroll, LinearLayout.LayoutParams(MATCH, MATCH))
            }
            columnsRow.addView(wrap, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
            columnWraps.add(wrap)
            columns.add(chips)
        }
        dayNameViews = names
        dayNumViews = nums
        dayWxViews = wxList
        dayColumnWraps = columnWraps
        dayColumns = columns
        weekContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        weekContainer.addView(headerRow, lpMatchWrap())
        weekContainer.addView(columnsRow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        } // end else (landscape columns)
        weekPanel.addView(weekContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))
        monthContainer = buildMonthContainer()
        monthContainer.visibility = View.GONE
        weekPanel.addView(monthContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))
        dayBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        dayContainer = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(dayBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        weekPanel.addView(dayContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))
        scheduleBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scheduleContainer = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(scheduleBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        weekPanel.addView(scheduleContainer, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // First-run setup panel ------------------------------------------
        setupPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        setupPanel.addView(TextView(ctx).apply {
            text = "Scan with your phone to add your family's calendars"
            textSize = 22f
            setTextColor(INK)
            gravity = Gravity.CENTER
        }, lpMatchWrap(bottom = dp(20)))
        setupQr = ImageView(ctx).apply {
            background = rounded(CARD, 16)
            elevation = dp(3).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        setupPanel.addView(setupQr, LinearLayout.LayoutParams(dp(280), dp(280)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        setupUrlText = TextView(ctx).apply {
            textSize = 18f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        setupPanel.addView(setupUrlText, lpMatchWrap(top = dp(16)))
        setupPanel.addView(TextView(ctx).apply {
            text = "Works from any phone or computer on the same Wi-Fi"
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lpMatchWrap(top = dp(6)))
        area.addView(setupPanel, FrameLayout.LayoutParams(MATCH, MATCH))
        setTab(0)
        return outer
    }

    private fun setTab(i: Int) {
        currentTab = i
        tabPanels.forEachIndexed { idx, p ->
            p.visibility = if (idx == i) View.VISIBLE else View.GONE
        }
        tabButtons.forEachIndexed { idx, b ->
            b.background = rounded(if (idx == i) ACCENT else PILL, 18)
            b.setTextColor(if (idx == i) Color.WHITE else INK)
        }
        when (i) {
            0 -> { renderCalendar(); updateEmptyState() }
            1 -> choresTab.render()
            2 -> listsTab.render()
            3 -> mealsTab.render()
            4 -> routinesTab.render()
        }
        refreshFocusables()
    }

    private fun placeholderPanel(message: String): FrameLayout =
        FrameLayout(ctx).apply {
            visibility = View.GONE
            addView(TextView(ctx).apply {
                text = message
                textSize = 18f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }

    /** Calendar tab shows either the board or the first-run QR panel. */
    private fun updateEmptyState() {
        val empty = store.feeds().isEmpty()
        val cal = currentTab == 0
        weekPanel.visibility = if (cal && !empty) View.VISIBLE else View.GONE
        setupPanel.visibility = if (cal && empty) View.VISIBLE else View.GONE
        if (cal && empty) {
            val url = setupUrl()
            setupUrlText.text = url
            setupQr.setImageBitmap(qrBitmap(url, dp(252)))
        }
    }

    private fun buildMonthContainer(): LinearLayout {
        val container = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val dowRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        val c = Calendar.getInstance()
        while (c.get(Calendar.DAY_OF_WEEK) != store.weekStartResolved()) c.add(Calendar.DAY_OF_MONTH, -1)
        for (i in 0..6) {
            dowRow.addView(TextView(ctx).apply {
                text = fmt.format(c.time).uppercase(Locale.getDefault())
                textSize = 12f
                setTextColor(FAINT)
                gravity = Gravity.CENTER
                letterSpacing = 0.12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, dp(2), 0, dp(6))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            c.add(Calendar.DAY_OF_MONTH, 1)
        }
        container.addView(dowRow, lpMatchWrap())

        val cells = ArrayList<MonthCell>()
        for (r in 0..5) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0..6) {
                val num = TextView(ctx).apply {
                    textSize = 13f
                    gravity = Gravity.CENTER
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
                val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                val more = TextView(ctx).apply {
                    textSize = 10f
                    setTextColor(FAINT)
                    setPadding(dp(2), 0, 0, 0)
                }
                val wrap = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    elevation = dp(1).toFloat()
                    setPadding(dp(6), dp(4), dp(6), dp(2))
                }
                wrap.addView(num, LinearLayout.LayoutParams(dp(26), dp(26)))
                wrap.addView(box, lpMatchWrap(top = dp(2)))
                wrap.addView(more, lpMatchWrap())
                row.addView(wrap, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                    leftMargin = dp(2); rightMargin = dp(2); topMargin = dp(2); bottomMargin = dp(2)
                })
                cells.add(MonthCell(wrap, num, box, more))
            }
            container.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        }
        monthCells = cells
        return container
    }

    private fun setView(mode: Int) {
        viewMode = mode
        dayContainer.visibility = if (mode == VIEW_DAY) View.VISIBLE else View.GONE
        weekContainer.visibility = if (mode == VIEW_WEEK) View.VISIBLE else View.GONE
        monthContainer.visibility = if (mode == VIEW_MONTH) View.VISIBLE else View.GONE
        scheduleContainer.visibility = if (mode == VIEW_SCHEDULE) View.VISIBLE else View.GONE
        styleToggles()
        renderCalendar()
    }

    private fun moveOffset(delta: Int) {
        when (viewMode) {
            VIEW_DAY -> dayOffset += delta
            VIEW_WEEK -> weekOffset += delta
            VIEW_MONTH -> monthOffset += delta
            VIEW_SCHEDULE -> scheduleOffset += delta
        }
    }

    private fun styleToggles() {
        viewToggles.forEachIndexed { i, btn ->
            btn.background = rounded(if (i == viewMode) ACCENT else PILL, 20)
            btn.setTextColor(if (i == viewMode) Color.WHITE else INK)
        }
    }

    private fun spacer(w: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(w, 1)
    }

    private fun buildSettingsOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(30), dp(24), dp(30), dp(18))
            isClickable = true // swallow clicks so the scrim doesn't close
        }
        card.addView(TextView(ctx).apply {
            text = "Manage calendars from your phone or computer"
            textSize = 19f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(14)))
        overlayQr = ImageView(ctx)
        card.addView(overlayQr, LinearLayout.LayoutParams(dp(210), dp(210)))
        overlayUrl = TextView(ctx).apply {
            textSize = 16f
            setTextColor(ACCENT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(overlayUrl, lpMatchWrap(top = dp(12)))
        card.addView(TextView(ctx).apply {
            text = "Works with Google and Apple (iCloud) calendars —\nstep-by-step instructions are on the page"
            textSize = 13f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, lpMatchWrap(top = dp(6)))

        val sizeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sizeRow.addView(TextView(ctx).apply {
            text = "Display size"
            textSize = 14f
            setTextColor(MUTED)
        })
        val sizeLabel = TextView(ctx).apply {
            text = "${(uiScale * 100).roundToInt()}%"
            textSize = 15f
            setTextColor(INK)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val slider = android.widget.SeekBar(ctx).apply {
            min = 70
            max = 160
            progress = (uiScale * 100).roundToInt()
            runCatching {
                thumb.setTint(ACCENT)
                progressDrawable.setTint(ACCENT)
            }
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                    sizeLabel.text = "$p%"
                    if (fromUser) previewScale(p / 100f) // live zoom while dragging
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    // Crisp re-layout on release; the host reopens this overlay.
                    commitScale(sb.progress / 100f, fromSettings = true)
                }
            })
        }
        sizeRow.addView(slider, LinearLayout.LayoutParams(dp(210), WRAP).apply {
            leftMargin = dp(10); rightMargin = dp(8)
        })
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(dp(52), WRAP))
        card.addView(sizeRow, lpMatchWrap(top = dp(12)))

        val buttons = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(navButton("Sync now") {
            statusLine = "Syncing…"
            renderAll()
            doSync()
            settingsOverlay.visibility = View.GONE
        })
        buttons.addView(navButton("Close") { settingsOverlay.visibility = View.GONE })
        card.addView(buttons, lpMatchWrap(top = dp(14)))

        scrim.addView(overlayScroll(card), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    /** Caps an overlay card at the screen height — scrolls at large UI scales. */
    private fun overlayScroll(card: View): View = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(card)
    }

    private fun buildAddOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { hideKeyboard(); visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(22), dp(28), dp(16))
            isClickable = true
        }
        card.addView(TextView(ctx).apply {
            text = "New event"
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(12)))

        addTitleInput = EditText(ctx).apply {
            hint = "What's happening?"
            textSize = 17f
            setTextColor(INK)
            setHintTextColor(FAINT)
            isSingleLine = true
            background = roundedStroke(Palette.FIELD, 12, dp(1), Palette.FIELD_STROKE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(addTitleInput, LinearLayout.LayoutParams(dp(480), WRAP).apply { bottomMargin = dp(14) })

        fun fieldLabel(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 13f
            setTextColor(MUTED)
            layoutParams = LinearLayout.LayoutParams(dp(74), WRAP)
        }
        fun stepRow(label: String, value: TextView, onMinus: () -> Unit, onPlus: () -> Unit): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(fieldLabel(label))
            row.addView(navButton("‹") { onMinus() })
            value.apply {
                textSize = 16f
                setTextColor(INK)
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            row.addView(value, LinearLayout.LayoutParams(dp(190), WRAP))
            row.addView(navButton("›") { onPlus() })
            return row
        }

        addDateLabel = TextView(ctx)
        val dateRow = stepRow("Date", addDateLabel,
            { addDate.add(Calendar.DAY_OF_MONTH, -1); refreshAddLabels() },
            { addDate.add(Calendar.DAY_OF_MONTH, 1); refreshAddLabels() })
        addAllDayToggle = TextView(ctx).apply {
            text = "All day"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { addAllDay = !addAllDay; refreshAddLabels() }
        }
        dateRow.addView(spacer(dp(14)))
        dateRow.addView(addAllDayToggle)
        card.addView(dateRow, lpMatchWrap(bottom = dp(10)))

        addTimeLabel = TextView(ctx)
        val startRow = stepRow("Starts", addTimeLabel,
            { addStartMinutes = (addStartMinutes - 15 + 1440) % 1440; refreshAddLabels() },
            { addStartMinutes = (addStartMinutes + 15) % 1440; refreshAddLabels() })
        addDurLabel = TextView(ctx)
        val durRow = stepRow("Length", addDurLabel,
            { addDurationMins = max(30, addDurationMins - 30); refreshAddLabels() },
            { addDurationMins = (addDurationMins + 30).coerceAtMost(480); refreshAddLabels() })
        addTimeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(startRow, lpMatchWrap(bottom = dp(10)))
            addView(durRow, lpMatchWrap())
        }
        card.addView(addTimeRow, lpMatchWrap(bottom = dp(10)))

        val calRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        calRow.addView(fieldLabel("Calendar"))
        addCalButton = TextView(ctx).apply {
            textSize = 15f
            setTextColor(INK)
            background = rounded(PILL, 18)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener {
                val cals = Writers.calendars(ctx)
                if (cals.size > 1) {
                    addCalIndex = (addCalIndex + 1) % cals.size
                    refreshAddLabels()
                }
            }
        }
        calRow.addView(addCalButton)
        card.addView(calRow, lpMatchWrap(bottom = dp(6)))

        addMsg = TextView(ctx).apply {
            textSize = 13f
            setTextColor(ACCENT)
            minHeight = dp(20)
        }
        card.addView(addMsg, lpMatchWrap())

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(navButton("Cancel") { hideKeyboard(); addOverlay.visibility = View.GONE })
        addSaveButton = accentButton("Add event") { saveNewEvent() }
        buttons.addView(addSaveButton)
        card.addView(buttons, lpMatchWrap(top = dp(8)))

        scrim.addView(overlayScroll(card), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun showAddOverlay(date: Calendar) {
        dayOverlay.visibility = View.GONE
        addDate = (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = Calendar.getInstance()
        addStartMinutes = ((now.get(Calendar.HOUR_OF_DAY) + 1) * 60).coerceAtMost(23 * 60)
        addDurationMins = 60
        addAllDay = false
        addBusy = false
        addTitleInput.setText("")
        addMsg.text = ""
        val target = Writers.target(ctx)
        addCalIndex = Writers.calendars(ctx).indexOfFirst {
            target != null && it.kind == target.kind && it.id == target.id
        }.coerceAtLeast(0)
        refreshAddLabels()
        addOverlay.visibility = View.VISIBLE
        addTitleInput.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(addTitleInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun refreshAddLabels() {
        addDateLabel.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(addDate.time)
        val t = (addDate.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, addStartMinutes / 60)
            set(Calendar.MINUTE, addStartMinutes % 60)
        }
        addTimeLabel.text = timeFormat().format(t.time)
        addDurLabel.text = when {
            addDurationMins < 60 -> "$addDurationMins min"
            addDurationMins % 60 == 0 -> "${addDurationMins / 60} hour" + (if (addDurationMins > 60) "s" else "")
            else -> "${addDurationMins / 60}½ hours"
        }
        addAllDayToggle.background = rounded(if (addAllDay) ACCENT else PILL, 18)
        addAllDayToggle.setTextColor(if (addAllDay) Color.WHITE else INK)
        addTimeRow.visibility = if (addAllDay) View.GONE else View.VISIBLE
        val cals = Writers.calendars(ctx)
        if (cals.isEmpty()) {
            addCalButton.text = "none connected"
            addMsg.text = "Connect iCloud or Google on the setup page (⚙) first"
        } else {
            addCalButton.text = cals[addCalIndex.coerceIn(cals.indices)].name
        }
    }

    private fun saveNewEvent() {
        if (addBusy) return
        val title = addTitleInput.text.toString().trim()
        if (title.isEmpty()) { addMsg.text = "Give it a name"; return }
        val cals = Writers.calendars(ctx)
        if (cals.isEmpty()) { addMsg.text = "Connect iCloud or Google on the setup page (⚙) first"; return }
        val cal = cals[addCalIndex.coerceIn(cals.indices)]

        val startCal = addDate.clone() as Calendar
        val start: Long
        val end: Long
        if (addAllDay) {
            start = startCal.timeInMillis
            end = (startCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        } else {
            startCal.set(Calendar.HOUR_OF_DAY, addStartMinutes / 60)
            startCal.set(Calendar.MINUTE, addStartMinutes % 60)
            start = startCal.timeInMillis
            end = start + addDurationMins * 60_000L
        }

        addBusy = true
        addMsg.text = "Adding…"
        hideKeyboard()
        Thread {
            try {
                Writers.setTarget(ctx, cal.kind, cal.id)
                Writers.addEvent(ctx, title, start, end, addAllDay)
                post {
                    addBusy = false
                    addOverlay.visibility = View.GONE
                    statusLine = "Added “$title” — waiting for the feed to update"
                    renderAll()
                    doSync()
                }
            } catch (e: Exception) {
                post {
                    addBusy = false
                    addMsg.text = e.message ?: "Couldn't add the event"
                }
            }
        }.start()
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addTitleInput.windowToken, 0)
    }

    private fun buildDetailOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(22), dp(28), dp(14))
            isClickable = true
            minimumWidth = dp(380)
        }
        detailTitle = TextView(ctx).apply {
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        card.addView(detailTitle)
        detailBody = TextView(ctx).apply {
            textSize = 15f
            setTextColor(MUTED)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        card.addView(detailBody, lpMatchWrap(top = dp(10)))
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        detailDeleteBtn = navButton("🗑 Remove") { requirePin { deleteCurrentEvent() } }.apply {
            setTextColor(0xFFE0556A.toInt())
        }
        btnRow.addView(detailDeleteBtn)
        btnRow.addView(spacer(dp(8)))
        btnRow.addView(navButton("Close") { detailOverlay.visibility = View.GONE })
        card.addView(btnRow, lpMatchWrap(top = dp(14)))
        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    /** Deletes the event being viewed from its calendar (syncs back). */
    private fun deleteCurrentEvent() {
        val ev = detailEvent ?: return
        val msg = if (ev.recurring)
            "This event repeats — removing it deletes every occurrence from the calendar it lives on."
        else "This also deletes it from the calendar it lives on (it syncs back to everyone)."
        confirm("Remove “${ev.title}”?", msg, "Remove") {
            detailOverlay.visibility = View.GONE
            statusLine = "Removing “${ev.title}”…"; statusText.text = statusLine
            Thread {
                val ok = runCatching { Writers.deleteEvent(ctx, ev.uid) }.getOrDefault(false)
                post {
                    if (ok) {
                        DeletedEvents.suppress(ctx, ev.uid)
                        events = events.filterNot { it.uid == ev.uid && it.uid.isNotEmpty() }
                        App.instance.lastEvents = events
                        // Don't bump lastSyncAt: this is a local edit, not a feed refresh.
                        // doSync() below sets it when the real sync lands.
                        renderAll()
                        doSync()
                    } else {
                        detailEvent = null
                        detailTitle.text = "Couldn't remove that"
                        detailBody.text = "“${ev.title}” isn't on a calendar connected here for " +
                            "two-way sync, so the Portal can't delete it. Connect that account under " +
                            "Settings → Two-way sync, then try again."
                        detailDeleteBtn.visibility = View.GONE
                        detailOverlay.visibility = View.VISIBLE
                    }
                }
            }.start()
        }
    }

    private fun showSettingsOverlay() {
        val url = setupUrl()
        overlayUrl.text = url
        overlayQr.setImageBitmap(qrBitmap(url, dp(210)))
        settingsOverlay.visibility = View.VISIBLE
    }

    private fun showDetails(ev: EventInstance) {
        detailEvent = ev
        // Removable only when it carries a UID and an account that holds it is
        // connected for two-way sync — otherwise the delete couldn't propagate.
        val canDelete = ev.uid.isNotEmpty() &&
            (GoogleCal.isConnected(ctx) || CalDav.isConnected(ctx))
        detailDeleteBtn.visibility = if (canDelete) View.VISIBLE else View.GONE
        val dayFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        detailTitle.text = ev.title
        detailBody.text = buildString {
            append(if (ev.allDay) "All day · " + dayFmt.format(ev.start)
                   else dayFmt.format(ev.start) + " · " +
                        timeFormat().format(ev.start) +
                        (if (ev.end > ev.start) " – " + timeFormat().format(ev.end) else ""))
            ev.location?.let { append("\n📍 ").append(it) }
            if (!ev.allDay) Weather.hourly(ctx, ev.start)?.let { (temp, code) ->
                append("\n").append(Weather.emoji(code)).append(" ")
                    .append(temp).append(Weather.unitSuffix(ctx)).append(" forecast")
            }
            append("\n").append(ev.feedName)
        }
        detailOverlay.visibility = View.VISIBLE
    }

    private fun buildDayOverlay(): FrameLayout {
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(SCRIM)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 20)
            elevation = dp(10).toFloat()
            setPadding(dp(28), dp(22), dp(28), dp(14))
            isClickable = true
            minimumWidth = dp(460)
        }
        dayTitle = TextView(ctx).apply {
            textSize = 20f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        card.addView(dayTitle)
        dayListBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(dayListBox, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        card.addView(scroll, lpMatchWrap(top = dp(12)))
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        btnRow.addView(accentButton("+ Add") { requirePin { showAddOverlay(dayOverlayDate) } })
        btnRow.addView(navButton("Close") { dayOverlay.visibility = View.GONE })
        card.addView(btnRow, lpMatchWrap(top = dp(12)))
        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private var dayOverlayDate: Calendar = Calendar.getInstance()

    private fun showDay(dayCal: Calendar) {
        dayOverlayDate = dayCal.clone() as Calendar
        val dayStart = dayCal.timeInMillis
        val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        dayTitle.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(dayCal.time)
        dayListBox.removeAllViews()
        val evs = eventsInRange(dayStart, dayEnd)
        if (evs.isEmpty()) {
            dayListBox.addView(TextView(ctx).apply {
                text = "Nothing scheduled"
                textSize = 15f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        for (ev in evs) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(mix(ev.color, CARD, 0.90f), 10)
                setOnClickListener { showDetails(ev) }
            }
            row.addView(View(ctx).apply { background = rounded(ev.color, 3) },
                LinearLayout.LayoutParams(dp(4), dp(26)).apply { rightMargin = dp(10) })
            row.addView(TextView(ctx).apply {
                text = if (ev.allDay) "All day" else timeFormat().format(ev.start)
                textSize = 13f
                setTextColor(MUTED)
            }, LinearLayout.LayoutParams(dp(82), WRAP))
            row.addView(TextView(ctx).apply {
                text = ev.title
                textSize = 15f
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            dayListBox.addView(row, lpMatchWrap(bottom = dp(6)))
        }
        dayOverlay.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- render

    private fun renderCalendar() {
        when (viewMode) {
            VIEW_DAY -> renderDay()
            VIEW_WEEK -> renderWeek()
            VIEW_MONTH -> renderMonth()
            VIEW_SCHEDULE -> renderSchedule()
        }
    }

    private fun renderDay() {
        val dayCal = Calendar.getInstance()
        dayCal.set(Calendar.HOUR_OF_DAY, 0); dayCal.set(Calendar.MINUTE, 0)
        dayCal.set(Calendar.SECOND, 0); dayCal.set(Calendar.MILLISECOND, 0)
        dayCal.add(Calendar.DAY_OF_MONTH, dayOffset)
        val dayStart = dayCal.timeInMillis
        val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

        val fmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        monthLabel.text = when (dayOffset) {
            0 -> "Today · " + fmt.format(dayCal.time)
            1 -> "Tomorrow · " + fmt.format(dayCal.time)
            else -> fmt.format(dayCal.time)
        }

        dayBox.removeAllViews()
        val evs = eventsInRange(dayStart, dayEnd)
        val allDay = evs.filter { it.allDay }
        val timed = evs.filterNot { it.allDay }.sortedBy { it.start }

        // All-day events ride a pinned strip above the timeline.
        for (ev in allDay) {
            dayBox.addView(TextView(ctx).apply {
                text = "  " + ev.title
                textSize = 15f
                setTextColor(INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = rounded(mix(ev.color, CARD, 0.80f), Palette.R_ELEMENT)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setOnClickListener { showDetails(ev) }
            }, lpMatchWrap(bottom = dp(6)))
        }

        if (timed.isEmpty()) {
            if (allDay.isEmpty()) dayBox.addView(TextView(ctx).apply {
                text = "Nothing scheduled ✨"
                textSize = 22f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(120), 0, 0)
            }, lpMatchWrap())
            return
        }

        // Window: a generous span that covers the day's events without a
        // pile of empty hours up top/bottom on a wall display.
        fun hourOf(ms: Long) = ((ms.coerceIn(dayStart, dayEnd) - dayStart) / 3_600_000L).toInt()
        var startHour = 24; var endHour = 0
        for (ev in timed) {
            startHour = minOf(startHour, hourOf(ev.start))
            endHour = maxOf(endHour, hourOf(ev.end - 1) + 1)
        }
        startHour = startHour.coerceAtMost(8).coerceAtLeast(0)
        endHour = endHour.coerceAtLeast(20).coerceAtMost(24)
        if (endHour - startHour < 8) endHour = (startHour + 8).coerceAtMost(24)

        val pxPerHour = dp(64)
        val pxPerMin = pxPerHour / 60f
        val winStartMin = startHour * 60
        val timeline = DayTimeline(ctx).apply {
            gutter = dp(50)
            totalHeight = (endHour - startHour) * pxPerHour
        }

        // Hour grid + labels.
        for (h in startHour..endHour) {
            val y = (h - startHour) * pxPerHour
            timeline.addView(View(ctx).apply { setBackgroundColor(LINE) },
                DayTimeline.LP(y, maxOf(1, dp(1)), DayTimeline.FULL))
            if (h < endHour) {
                val cal = (dayCal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, h) }
                timeline.addView(TextView(ctx).apply {
                    text = hourLabel(cal.time)
                    textSize = 11f
                    setTextColor(FAINT)
                    setPadding(0, dp(2), dp(8), 0)
                    gravity = Gravity.END
                }, DayTimeline.LP(y - dp(7), -1, DayTimeline.LABEL))
            }
        }

        // Overlap-packed event blocks.
        val intervals = timed.map {
            it.start.coerceAtLeast(dayStart) to it.end.coerceAtMost(dayEnd)
        }
        val gap = dp(3)
        for (p in timeline.pack(intervals)) {
            val ev = timed[p.index]
            val sMin = ((intervals[p.index].first - dayStart) / 60_000L).toInt() - winStartMin
            val eMin = ((intervals[p.index].second - dayStart) / 60_000L).toInt() - winStartMin
            val top = (sMin * pxPerMin).toInt().coerceAtLeast(0)
            val h = ((eMin - sMin) * pxPerMin).toInt().coerceAtLeast(dp(26))
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(mix(ev.color, CARD, 0.78f), Palette.R_ELEMENT)
                elevation = dp(1).toFloat()
                setPadding(dp(2), dp(4), dp(8), dp(4))
                setOnClickListener { showDetails(ev) }
            }
            card.addView(View(ctx).apply { background = rounded(ev.color, 2) },
                LinearLayout.LayoutParams(dp(4), MATCH).apply { rightMargin = dp(7) })
            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(ctx).apply {
                text = ev.title
                textSize = 14f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = if (h > dp(46)) 2 else 1
                ellipsize = TextUtils.TruncateAt.END
            })
            // Only enough room to show the time on taller blocks.
            if (h > dp(40)) textCol.addView(TextView(ctx).apply {
                text = if (ev.start < dayStart) "ends " + timeFormat().format(ev.end)
                       else timeFormat().format(ev.start)
                textSize = 11f
                setTextColor(MUTED)
                maxLines = 1
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, MATCH, 1f))
            timeline.addView(card, DayTimeline.LP(
                top, h - gap, DayTimeline.EVENT,
                colFrac = p.col.toFloat() / p.cols,
                wFrac = 1f / p.cols))
        }

        // "Now" indicator on today.
        var nowY = -1
        if (dayOffset == 0) {
            val nowMin = ((System.currentTimeMillis() - dayStart) / 60_000L).toInt() - winStartMin
            if (nowMin in 0..((endHour - startHour) * 60)) {
                nowY = (nowMin * pxPerMin).toInt()
                timeline.addView(View(ctx).apply { setBackgroundColor(ACCENT) },
                    DayTimeline.LP(nowY, maxOf(2, dp(2)), DayTimeline.FULL))
            }
        }

        dayBox.addView(timeline, lpMatchWrap())

        // Auto-scroll to "now"/first event ONLY when the viewed day changes —
        // re-renders from a 15-min sync mustn't yank a manually-scrolled view.
        val dayKey = "$dayOffset"
        if (dayKey != lastDayScrollKey) {
            lastDayScrollKey = dayKey
            val firstTop = ((((intervals.firstOrNull()?.first ?: dayStart) - dayStart) / 60_000L)
                .toInt() - winStartMin) * pxPerMin
            val target = (if (nowY >= 0) nowY else firstTop.toInt()) - dp(70)
            dayContainer.post { dayContainer.scrollTo(0, target.coerceAtLeast(0)) }
        }
    }
    private var lastDayScrollKey = ""

    /** "7 AM" style hour label, locale-aware (24h shows "07"). */
    private fun hourLabel(time: java.util.Date): String =
        (if (android.text.format.DateFormat.is24HourFormat(ctx))
            SimpleDateFormat("HH", Locale.getDefault())
        else SimpleDateFormat("h a", Locale.getDefault())).format(time)

    private fun renderSchedule() {
        val start = Calendar.getInstance()
        start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0)
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0)
        start.add(Calendar.DAY_OF_MONTH, scheduleOffset * 14)
        val rangeFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val endCal = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 13) }
        monthLabel.text = rangeFmt.format(start.time) + " – " + rangeFmt.format(endCal.time)

        scheduleBox.removeAllViews()
        var any = false
        val dayHeaderFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        for (i in 0 until 14) {
            val dayCal = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val dayStart = dayCal.timeInMillis
            val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val evs = eventsInRange(dayStart, dayEnd)
            if (evs.isEmpty()) continue
            any = true
            val isToday = scheduleOffset == 0 && i == 0
            val prefix = if (scheduleOffset == 0) when (i) {
                0 -> "Today · "; 1 -> "Tomorrow · "; else -> ""
            } else ""
            scheduleBox.addView(TextView(ctx).apply {
                text = prefix + dayHeaderFmt.format(dayCal.time)
                textSize = 15f
                setTextColor(if (isToday) ACCENT else MUTED)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(4), dp(12), 0, dp(6))
            }, lpMatchWrap())
            for (ev in evs) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = rounded(CARD, 12)
                    elevation = dp(1).toFloat()
                    setPadding(dp(14), dp(11), dp(14), dp(11))
                    setOnClickListener { showDetails(ev) }
                }
                row.addView(View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(ev.color)
                    }
                }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(12) })
                row.addView(TextView(ctx).apply {
                    text = if (ev.allDay) "All day" else timeFormat().format(ev.start)
                    textSize = 14f
                    setTextColor(MUTED)
                }, LinearLayout.LayoutParams(dp(96), WRAP))
                row.addView(TextView(ctx).apply {
                    text = ev.title
                    textSize = 16f
                    setTextColor(INK)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                scheduleBox.addView(row, lpMatchWrap(bottom = dp(6)))
            }
        }
        if (!any) {
            scheduleBox.addView(TextView(ctx).apply {
                text = "Nothing in these two weeks ✨"
                textSize = 22f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(120), 0, 0)
            }, lpMatchWrap())
        }
    }

    private fun renderMonth() {
        val first = Calendar.getInstance()
        first.set(Calendar.HOUR_OF_DAY, 0); first.set(Calendar.MINUTE, 0)
        first.set(Calendar.SECOND, 0); first.set(Calendar.MILLISECOND, 0)
        first.set(Calendar.DAY_OF_MONTH, 1)
        first.add(Calendar.MONTH, monthOffset)
        monthLabel.text = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(first.time)

        val gridStart = first.clone() as Calendar
        while (gridStart.get(Calendar.DAY_OF_WEEK) != store.weekStartResolved())
            gridStart.add(Calendar.DAY_OF_MONTH, -1)

        val today = Calendar.getInstance()
        for (i in 0..41) {
            val cell = monthCells[i]
            val dayCal = (gridStart.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val dayStart = dayCal.timeInMillis
            val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val inMonth = dayCal.get(Calendar.MONTH) == first.get(Calendar.MONTH)
            val isToday = dayCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    dayCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

            cell.num.text = dayCal.get(Calendar.DAY_OF_MONTH).toString()
            when {
                isToday -> { cell.num.setTextColor(Color.WHITE); cell.num.background = circle(ACCENT) }
                inMonth -> { cell.num.setTextColor(INK); cell.num.background = null }
                else -> { cell.num.setTextColor(FAINT); cell.num.background = null }
            }
            cell.wrap.background =
                if (isToday) roundedStroke(TODAY_CARD, 12, dp(2), ACCENT)
                else rounded(if (inMonth) CARD else OUT_CARD, 12)

            val evs = eventsInRange(dayStart, dayEnd)
            cell.box.removeAllViews()
            for (ev in evs.take(3)) {
                cell.box.addView(TextView(ctx).apply {
                    text = ev.title
                    textSize = 10.5f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = rounded(ev.color, 6)
                    setPadding(dp(6), dp(2), dp(6), dp(3))
                }, lpMatchWrap(bottom = dp(3)))
            }
            cell.more.text = if (evs.size > 3) "+${evs.size - 3} more" else ""
            cell.wrap.setOnClickListener { showDay(dayCal.clone() as Calendar) }
        }
    }

    private fun renderAll() {
        updateClock()
        renderWeather()
        renderToday()
        renderCalendar()
        renderLegend()
        statusText.text = statusLine
        updateEmptyState()
    }

    // Cached: this runs every second, 24/7 — building two SimpleDateFormats
    // per tick is ~170k allocations a day, and unconditional setText forces a
    // relayout/redraw every second even though the string changes per minute.
    private var clockFmt: SimpleDateFormat? = null
    private var clockFmt24 = false
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    private fun updateClock() {
        // "auto" theme flips on the clock (7pm/7am) — catch the crossing here
        // and rebuild into the new palette. (No-op for fixed light/dark.)
        if (store.isDark(baseCtx) != builtDark) { rebuild(); return }
        val now = Calendar.getInstance()
        val is24 = android.text.format.DateFormat.is24HourFormat(ctx)
        if (clockFmt == null || is24 != clockFmt24) {
            clockFmt24 = is24
            clockFmt = timeFormat()
        }
        val time = clockFmt!!.format(now.time)
        if (clockText.text?.toString() != time) clockText.text = time
        val date = dateFmt.format(now.time)
        if (dateText.text?.toString() != date) dateText.text = date
        val stamp = "${now.get(Calendar.YEAR)}-${now.get(Calendar.DAY_OF_YEAR)}"
        if (stamp != lastDayStamp) {
            val firstRun = lastDayStamp.isEmpty()
            lastDayStamp = stamp
            if (!firstRun) {
                weekOffset = 0
                monthOffset = 0
                dayOffset = 0
                scheduleOffset = 0
                renderToday()
                renderCalendar()
                // The tabs capture "today" at render time too — a board left
                // on Chores overnight kept showing yesterday's chores.
                when (currentTab) {
                    1 -> choresTab.render()
                    2 -> listsTab.render()
                    3 -> mealsTab.render()
                    4 -> routinesTab.render()
                }
            }
        }
    }

    private fun weekStartCal(): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != store.weekStartResolved()) c.add(Calendar.DAY_OF_MONTH, -1)
        c.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
        return c
    }

    private fun renderWeek() {
        val ws = weekStartCal()
        val weekEnd = (ws.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 6) }
        monthLabel.text = if (ws.get(Calendar.MONTH) == weekEnd.get(Calendar.MONTH))
            SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(ws.time)
        else
            SimpleDateFormat("MMM", Locale.getDefault()).format(ws.time) + " – " +
            SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(weekEnd.time)
        if (weekIsRows) renderWeekRows(ws) else renderWeekColumns(ws)
    }

    /** Landscape week: seven vertical day columns. */
    private fun renderWeekColumns(ws: Calendar) {
        val today = Calendar.getInstance()
        val nameFmt = SimpleDateFormat("EEE", Locale.getDefault())
        for (i in 0..6) {
            val dayCal = (ws.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val dayStart = dayCal.timeInMillis
            val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val isToday = dayCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    dayCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

            dayNameViews[i].text = nameFmt.format(dayCal.time).uppercase(Locale.getDefault())
            dayNameViews[i].setTextColor(if (isToday) ACCENT else FAINT)
            val wx = Weather.daily(ctx, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dayCal.time))
            dayWxViews[i].text = if (wx != null) "${Weather.emoji(wx.third)} ${wx.first}°" else ""
            dayNumViews[i].text = dayCal.get(Calendar.DAY_OF_MONTH).toString()
            if (isToday) {
                dayNumViews[i].setTextColor(Color.WHITE)
                dayNumViews[i].background = circle(ACCENT)
            } else {
                dayNumViews[i].setTextColor(INK)
                dayNumViews[i].background = null
            }
            dayColumnWraps[i].background =
                if (isToday) roundedStroke(TODAY_CARD, 16, dp(2), ACCENT)
                else rounded(CARD, 16)

            val col = dayColumns[i]
            col.removeAllViews()
            for (ev in eventsInRange(dayStart, dayEnd)) col.addView(chip(ev, dayStart), lpMatchWrap(bottom = dp(6)))
        }
    }

    /** Portrait week: seven stacked day ROWS (day rail + agenda chips). */
    private fun renderWeekRows(ws: Calendar) {
        val today = Calendar.getInstance()
        val nameFmt = SimpleDateFormat("EEE", Locale.getDefault())
        weekRowsBox.removeAllViews()
        for (i in 0..6) {
            val dayCal = (ws.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val dayStart = dayCal.timeInMillis
            val dayEnd = (dayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            val isToday = dayCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    dayCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = if (isToday) roundedStroke(TODAY_CARD, 16, dp(2), ACCENT) else rounded(CARD, 16)
                elevation = dp(1).toFloat()
                setPadding(dp(10), dp(10), dp(12), dp(10))
                minimumHeight = dp(76)
            }
            // Left rail: weekday · big number · weather. Tap → that day's timeline.
            val rail = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { dayOffset = dayOffsetFor(dayCal); setView(VIEW_DAY) }
            }
            rail.addView(TextView(ctx).apply {
                text = nameFmt.format(dayCal.time).uppercase(Locale.getDefault())
                textSize = 12f; letterSpacing = 0.12f
                setTextColor(if (isToday) ACCENT else FAINT)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            rail.addView(TextView(ctx).apply {
                text = dayCal.get(Calendar.DAY_OF_MONTH).toString()
                textSize = 22f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                if (isToday) { setTextColor(Color.WHITE); background = circle(ACCENT) } else setTextColor(INK)
            }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { topMargin = dp(2) })
            val wx = Weather.daily(ctx, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dayCal.time))
            rail.addView(TextView(ctx).apply {
                text = if (wx != null) "${Weather.emoji(wx.third)} ${wx.first}°" else ""
                textSize = 11f; setTextColor(MUTED); gravity = Gravity.CENTER
            }, lpMatchWrap(top = dp(2)))
            row.addView(rail, LinearLayout.LayoutParams(dp(96), WRAP))

            // Events: agenda chips, all-day first then by time. Empty → faint dash.
            val evBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            val evs = eventsInRange(dayStart, dayEnd)
            if (evs.isEmpty()) {
                evBox.addView(TextView(ctx).apply {
                    text = "—"; textSize = 16f; setTextColor(FAINT); setPadding(dp(8), dp(6), 0, 0)
                })
            } else {
                for (ev in evs) evBox.addView(rowChip(ev, dayStart), lpMatchWrap(bottom = dp(5)))
            }
            row.addView(evBox, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
            weekRowsBox.addView(row, lpMatchWrap(bottom = dp(8)))
        }
    }

    /** Days from today (midnight) to [target] — DST-safe via rounding. */
    private fun dayOffsetFor(target: Calendar): Int {
        val a = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val b = (target.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt()
    }

    /** A slim horizontal agenda chip for the portrait week rows. */
    private fun rowChip(ev: EventInstance, dayStart: Long): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(mix(ev.color, CARD, 0.86f), 10)
            setPadding(dp(4), dp(8), dp(12), dp(8))
            minimumHeight = dp(44)
            setOnClickListener { showDetails(ev) }
        }
        box.addView(View(ctx).apply { background = rounded(ev.color, 2) },
            LinearLayout.LayoutParams(dp(4), dp(26)).apply { leftMargin = dp(4); rightMargin = dp(10) })
        box.addView(TextView(ctx).apply {
            text = when {
                ev.allDay -> "All day"
                ev.start < dayStart -> "…cont."
                else -> timeFormat().format(ev.start)
            }
            textSize = 13f; setTextColor(MUTED); minWidth = dp(70)
        })
        box.addView(TextView(ctx).apply {
            text = ev.title
            textSize = 14.5f; setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })
        return box
    }

    private fun renderToday() {
        val today = Calendar.getInstance()
        today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0)
        today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0)
        val dayStart = today.timeInMillis
        val dayEnd = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis

        todayList.removeAllViews()
        val todays = eventsInRange(dayStart, dayEnd)
        if (todays.isEmpty()) {
            todayList.addView(TextView(ctx).apply {
                text = "Nothing scheduled ✨"
                textSize = 15f
                setTextColor(MUTED)
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        for (ev in todays) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(9), dp(10), dp(9))
                background = rounded(mix(ev.color, CARD, 0.90f), 12)
                setOnClickListener { showDetails(ev) }
            }
            row.addView(View(ctx).apply { background = rounded(ev.color, 3) },
                LinearLayout.LayoutParams(dp(4), dp(30)).apply { rightMargin = dp(10) })
            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(ctx).apply {
                text = ev.title
                textSize = 15f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(ctx).apply {
                text = if (ev.allDay) "All day"
                       else if (ev.start < dayStart) "until " + timeFormat().format(ev.end)
                       else timeFormat().format(ev.start)
                textSize = 12f
                setTextColor(MUTED)
            })
            row.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
            todayList.addView(row, lpMatchWrap(bottom = dp(6)))
        }
    }

    private fun renderLegend() {
        legendList.removeAllViews()
        for (f in store.feeds().filter { it.kind != "inbox" }) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(View(ctx).apply { background = circle(f.color) },
                LinearLayout.LayoutParams(dp(11), dp(11)).apply { rightMargin = dp(8) })
            row.addView(TextView(ctx).apply {
                text = f.name
                textSize = 13f
                setTextColor(MUTED)
            })
            legendList.addView(row)
        }
    }

    private fun eventsInRange(rangeStart: Long, rangeEnd: Long): List<EventInstance> =
        events.filter { it.start < rangeEnd && max(it.end, it.start + 1) > rangeStart }
            .sortedWith(compareByDescending<EventInstance> { it.allDay }.thenBy { it.start })

    private fun chip(ev: EventInstance, dayStart: Long): View {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ev.color, 10)
            setPadding(dp(9), dp(6), dp(9), dp(7))
            setOnClickListener { showDetails(ev) }
        }
        box.addView(TextView(ctx).apply {
            text = when {
                ev.allDay -> "All day"
                ev.start < dayStart -> "…continues"
                else -> timeFormat().format(ev.start)
            }
            textSize = 11f
            setTextColor(0xD9FFFFFF.toInt())
        })
        box.addView(TextView(ctx).apply {
            text = ev.title
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        return box
    }

    // ------------------------------------------------------------ helpers

    private fun timeFormat(): SimpleDateFormat =
        if (android.text.format.DateFormat.is24HourFormat(ctx))
            SimpleDateFormat("HH:mm", Locale.getDefault())
        else SimpleDateFormat("h:mm a", Locale.getDefault())

    private fun accentButton(label: String, onClick: () -> Unit): TextView =
        navButton(label, onClick).apply {
            background = rounded(ACCENT, 20)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    private fun navButton(label: String, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = label
            textSize = 16f
            setTextColor(INK)
            gravity = Gravity.CENTER
            background = rounded(PILL, 20)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        }

    private fun lpMatchWrap(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun roundedStroke(fill: Int, radiusDp: Int, strokeW: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(strokeW, strokeColor)
        }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Blend [c1] toward [c2] by fraction [f] (0 = all c1, 1 = all c2). */
    private fun mix(c1: Int, c2: Int, f: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(255,
            ch(Color.red(c1), Color.red(c2)),
            ch(Color.green(c1), Color.green(c2)),
            ch(Color.blue(c1), Color.blue(c2)))
    }

    private fun setupUrl(): String =
        "http://" + (wifiIp() ?: "<portal-ip>") + ":" + ConfigServer.PORT

    private fun wifiIp(): String? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo?.ipAddress ?: 0
        if (ip == 0) return null
        return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size)
            bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        return bmp
    }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val SYNC_INTERVAL_MS = 15L * 60 * 1000
        private const val VIEW_DAY = 0
        private const val VIEW_WEEK = 1
        private const val VIEW_MONTH = 2
        private const val VIEW_SCHEDULE = 3

        /** Quick-pick chores for the on-device composer. */
        private val CHORE_BANK = listOf(
            "🛏️" to "Make the bed", "🦷" to "Brush teeth", "📚" to "Homework",
            "🐕" to "Feed the pet", "🍽️" to "Set the table", "🥣" to "Clear the dishes",
            "🧺" to "Put laundry away", "🧸" to "Tidy your room", "🗑️" to "Take out the trash",
            "🚿" to "Shower", "🎵" to "Practice music", "🪴" to "Water the plants")

        /** Quick-pick get-ready items for the on-device routine composer. */
        private val ROUTINE_BANK = listOf(
            "🎒" to "Pack the backpack", "📚" to "Pack homework", "🥪" to "Pack lunch",
            "💧" to "Fill water bottle", "👟" to "Shoes on", "🧥" to "Grab a coat",
            "🦷" to "Brush teeth", "🛏️" to "Make the bed", "👕" to "Get dressed",
            "🍎" to "Eat breakfast", "📱" to "Charge devices", "🌙" to "Pajamas on")

        // Single source of truth: Palette.kt. Getters (not cached vals) so a
        // theme switch — which rebuilds the board — is reflected live.
        private val BG get() = Palette.BG
        private val CARD get() = Palette.CARD
        private val TODAY_CARD get() = Palette.TODAY_CARD
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val FAINT get() = Palette.FAINT
        private val ACCENT get() = Palette.ACCENT
        private val OUT_CARD get() = Palette.OUT_CARD
        private val LINE get() = Palette.LINE
        private val SCRIM get() = Palette.SCRIM
    }
}
