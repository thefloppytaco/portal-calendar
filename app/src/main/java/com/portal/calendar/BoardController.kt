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

    private var events: List<EventInstance> = emptyList()
    private var weekOffset = 0
    private var monthOffset = 0
    private var dayOffset = 0
    private var scheduleOffset = 0
    private var viewMode = VIEW_WEEK
    private var lastDayStamp = ""
    private var statusLine = "Starting…"

    // Top-level tabs: 0=Calendar 1=Chores 2=Lists 3=Meals
    private var currentTab = 0
    private lateinit var tabButtons: List<TextView>
    private lateinit var tabPanels: List<View>
    private lateinit var listsTab: ListsTab
    private lateinit var choresTab: ChoresTab

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

    private class MonthCell(val wrap: LinearLayout, val num: TextView,
                            val box: LinearLayout, val more: TextView)

    val view: FrameLayout

    init {
        view = buildUi()
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
    private val configListener: () -> Unit = {
        if (store.uiScale() != uiScale) {
            // Scale changed (from the config page or ⚙) — rebuild the whole UI.
            (baseCtx as? android.app.Activity)?.recreate()
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

    fun commitScale(target: Float) {
        store.setUiScale(target)
        (baseCtx as? android.app.Activity)?.recreate()
    }

    private val dataListener: () -> Unit = {
        renderWeather()
        renderToday() // sidebar chores widget lives there
        when (currentTab) {
            0 -> renderCalendar() // weather in week headers
            1 -> choresTab.render()
            2 -> listsTab.render()
        }
    }

    private fun renderWeather() {
        val line = Weather.summaryLine(ctx)
        weatherText.visibility = if (line != null) View.VISIBLE else View.GONE
        line?.let { weatherText.text = it }
    }

    fun start() {
        App.instance.activeBoard = this
        App.instance.addConfigListener(configListener)
        App.instance.addDataListener(dataListener)
        exitButton.visibility = if (onExit != null) View.VISIBLE else View.GONE
        renderAll()
        handler.post(clockTick)
        handler.post(syncTick)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        App.instance.removeConfigListener(configListener)
        App.instance.removeDataListener(dataListener)
        if (App.instance.activeBoard === this) App.instance.activeBoard = null
    }

    /** Returns true if an overlay was open and got closed (for Back handling). */
    fun closeOverlays(): Boolean {
        var closed = false
        if (pinOverlay.visibility == View.VISIBLE) {
            pinOverlay.visibility = View.GONE; closed = true
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
        return closed
    }

    // ---------------------------------------------------------------- sync

    private fun doSync() {
        Thread { Weather.maybeRefresh(ctx) }.start()
        if (store.feeds().isEmpty()) {
            events = emptyList()
            statusLine = "No calendars configured yet"
            renderAll()
            return
        }
        sync.requestSync { evs, problems ->
            events = evs
            statusLine = if (problems.isEmpty())
                "Updated " + timeFormat().format(Calendar.getInstance().time)
            else problems.joinToString("\n")
            renderAll()
        }
    }

    // ------------------------------------------------------------------ ui

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private lateinit var contentRow: LinearLayout

    private fun buildUi(): FrameLayout {
        val root = FrameLayout(ctx)
        root.setBackgroundColor(BG)

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        root.addView(row, FrameLayout.LayoutParams(MATCH, MATCH))
        contentRow = row

        row.addView(buildSidebar(), LinearLayout.LayoutParams(dp(300), MATCH))
        row.addView(buildMainArea(), LinearLayout.LayoutParams(0, MATCH, 1f))

        settingsOverlay = buildSettingsOverlay()
        root.addView(settingsOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        dayOverlay = buildDayOverlay()
        root.addView(dayOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        addOverlay = buildAddOverlay()
        root.addView(addOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        detailOverlay = buildDetailOverlay()
        root.addView(detailOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        pinOverlay = buildPinOverlay()
        root.addView(pinOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    // ------------------------------------------------------------ kid lock

    private lateinit var pinOverlay: FrameLayout
    private lateinit var pinDots: TextView
    private var pinEntry = ""
    private var pinAction: (() -> Unit)? = null

    /** Runs [action] directly when no PIN is set; otherwise asks for it. */
    private fun requirePin(action: () -> Unit) {
        if (store.pin().isEmpty()) { action(); return }
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
        card.addView(TextView(ctx).apply {
            text = "Parents only 🔒"
            textSize = 18f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, lpMatchWrap(bottom = dp(10)))
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
            if (pinEntry == store.pin()) {
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
        closeOverlays()
        if (currentTab != 0) setTab(0)
        setView(VIEW_WEEK)
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
            setPadding(0, dp(8), dp(12), dp(2))
            setOnClickListener { requirePin { showSettingsOverlay() } }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        exitButton = TextView(ctx).apply {
            text = "✕"
            textSize = 16f
            setTextColor(MUTED)
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
        listOf("Calendar", "Chores", "Lists", "Meals").forEachIndexed { i, label ->
            val t = TextView(ctx).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        choresTab = ChoresTab(ctx)
        area.addView(choresTab.view, FrameLayout.LayoutParams(MATCH, MATCH))
        val mealsPlaceholder = placeholderPanel("Meal planning is coming in the next update")
        area.addView(mealsPlaceholder, FrameLayout.LayoutParams(MATCH, MATCH))
        tabPanels = listOf(weekPanel, choresTab.view, listsTab.view, mealsPlaceholder)

        // Nav row -------------------------------------------------------
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        monthLabel = TextView(ctx).apply {
            textSize = 27f
            setTextColor(INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        nav.addView(monthLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(accentButton("+ Add") { requirePin { showAddOverlay(Calendar.getInstance()) } })
        nav.addView(spacer(dp(10)))
        val toggles = ArrayList<TextView>()
        listOf("Day", "Week", "Month", "Plan").forEachIndexed { i, label ->
            val t = navButton(label) { setView(i) }
            toggles.add(t)
            nav.addView(t)
        }
        viewToggles = toggles
        nav.addView(spacer(dp(10)))
        nav.addView(navButton("‹") { moveOffset(-1); renderCalendar() })
        nav.addView(navButton("Today") {
            weekOffset = 0; monthOffset = 0; dayOffset = 0; scheduleOffset = 0
            renderCalendar()
        })
        nav.addView(navButton("›") { moveOffset(1); renderCalendar() })
        weekPanel.addView(nav, lpMatchWrap(bottom = dp(14)))
        styleToggles()

        // Day headers + columns ------------------------------------------
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
        }
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
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_MONTH, -1)
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
                    commitScale(sb.progress / 100f) // crisp re-layout on release
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

        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
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
            background = roundedStroke(0xFFFBFAF6.toInt(), 12, dp(1), 0xFFDDD8CC.toInt())
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

        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
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
                handler.post {
                    addBusy = false
                    addOverlay.visibility = View.GONE
                    statusLine = "Added “$title” — waiting for the feed to update"
                    renderAll()
                    doSync()
                }
            } catch (e: Exception) {
                handler.post {
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
        btnRow.addView(navButton("Close") { detailOverlay.visibility = View.GONE })
        card.addView(btnRow, lpMatchWrap(top = dp(14)))
        scrim.addView(card, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        return scrim
    }

    private fun showSettingsOverlay() {
        val url = setupUrl()
        overlayUrl.text = url
        overlayQr.setImageBitmap(qrBitmap(url, dp(210)))
        settingsOverlay.visibility = View.VISIBLE
    }

    private fun showDetails(ev: EventInstance) {
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
        if (evs.isEmpty()) {
            dayBox.addView(TextView(ctx).apply {
                text = "Nothing scheduled ✨"
                textSize = 22f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(120), 0, 0)
            }, lpMatchWrap())
            return
        }
        for (ev in evs) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { showDetails(ev) }
            }
            row.addView(TextView(ctx).apply {
                text = if (ev.allDay) "All day"
                       else if (ev.start < dayStart) "…" + timeFormat().format(ev.end)
                       else timeFormat().format(ev.start)
                textSize = 19f
                setTextColor(MUTED)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(dp(130), WRAP))
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(mix(ev.color, CARD, 0.88f), 14)
                elevation = dp(1).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            card.addView(View(ctx).apply { background = rounded(ev.color, 3) },
                LinearLayout.LayoutParams(dp(5), dp(38)).apply { rightMargin = dp(14) })
            val textCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            textCol.addView(TextView(ctx).apply {
                text = ev.title
                textSize = 18f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            textCol.addView(TextView(ctx).apply {
                text = buildString {
                    if (!ev.allDay) {
                        append(timeFormat().format(ev.start))
                        if (ev.end > ev.start) append(" – ").append(timeFormat().format(ev.end))
                    } else append("All day")
                    ev.location?.let { append("   📍 ").append(it) }
                }
                textSize = 13f
                setTextColor(MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            card.addView(textCol, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(card, LinearLayout.LayoutParams(0, WRAP, 1f))
            dayBox.addView(row, lpMatchWrap(bottom = dp(10)))
        }
    }

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
        while (gridStart.get(Calendar.DAY_OF_WEEK) != gridStart.firstDayOfWeek)
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

    private fun updateClock() {
        val now = Calendar.getInstance()
        clockText.text = timeFormat().format(now.time)
        dateText.text = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now.time)
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
            }
        }
    }

    private fun weekStartCal(): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        while (c.get(Calendar.DAY_OF_WEEK) != c.firstDayOfWeek) c.add(Calendar.DAY_OF_MONTH, -1)
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
            appendSidebarChores()
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
        appendSidebarChores()
    }

    /** Compact "chores due today" block under the Today agenda. */
    private fun appendSidebarChores() {
        val status = try { org.json.JSONObject(Chores.statusJson(ctx)) } catch (e: Exception) { return }
        val chores = status.getJSONArray("chores")
        if (chores.length() == 0) return
        val doneArr = status.getJSONArray("doneToday")
        val done = (0 until doneArr.length()).map { doneArr.optString(it) }.toSet()
        val members = status.getJSONArray("members")
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

        val due = (0 until chores.length()).map { chores.getJSONObject(it) }
            .filter { Chores.isDue(it, dow) }
            .sortedBy { done.contains(it.optString("id")) }
        if (due.isEmpty()) return

        todayList.addView(TextView(ctx).apply {
            text = "CHORES"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.12f
            setPadding(0, dp(14), 0, dp(4))
        })
        for (c in due.take(6)) {
            val isDone = done.contains(c.optString("id"))
            val member = (0 until members.length()).map { members.getJSONObject(it) }
                .find { it.optString("id") == c.optString("memberId") }
            val color = Members.parse(member?.optString("color") ?: "#A3A8B0")
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    runCatching {
                        Chores.mutate(ctx, org.json.JSONObject()
                            .put("action", "toggle").put("choreId", c.optString("id")))
                    }
                }
            }
            row.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isDone) setColor(color) else { setColor(Color.TRANSPARENT); setStroke(dp(2), color) }
                }
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
            row.addView(TextView(ctx).apply {
                text = "${c.optString("icon")} ${c.optString("title")}"
                textSize = 14f
                setTextColor(if (isDone) FAINT else INK)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            todayList.addView(row, lpMatchWrap())
        }
    }

    private fun renderLegend() {
        legendList.removeAllViews()
        for (f in store.feeds()) {
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

        // Warm-paper light theme
        private val BG = 0xFFF4F1EA.toInt()          // warm paper background
        private val CARD = 0xFFFFFFFF.toInt()        // white cards
        private val TODAY_CARD = 0xFFFFF9F2.toInt()  // cream tint for today
        private val PILL = 0xFFEAE6DC.toInt()        // button pills
        private val INK = 0xFF333A45.toInt()         // primary text
        private val MUTED = 0xFF737983.toInt()       // secondary text
        private val FAINT = 0xFFA3A8B0.toInt()       // tertiary text
        private val ACCENT = 0xFFF0584C.toInt()      // coral
        private val OUT_CARD = 0xFFF8F5EE.toInt()    // outside-month cells
        private val LINE = 0xFFECE8DF.toInt()        // hairlines
        private val SCRIM = 0x59000000               // overlay dim
    }
}
