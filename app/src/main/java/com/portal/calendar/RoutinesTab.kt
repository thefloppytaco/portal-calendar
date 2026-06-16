package com.portal.calendar

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * The board's Routines tab: one column per family member with today's checklist
 * items as big tappable cards, grouped by time-of-day section (morning / evening
 * …). A header shows how much of today's routine is done with a progress bar.
 * Tapping a card bounces it and toggles it complete — behind the kid's own PIN
 * if set, just like [ChoresTab]. No stars: finishing the list is the point.
 */
class RoutinesTab(
    private val ctx: Context,
    private val onAddItem: () -> Unit = {},
    private val onGate: (memberPin: String, memberName: String, action: () -> Unit) -> Unit =
        { _, _, action -> action() },
    private val onRemoveItem: (id: String, label: String) -> Unit = { _, _ -> }
) {

    private lateinit var columnsRow: LinearLayout
    val view: LinearLayout = build()

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            text = "TODAY'S ROUTINES"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(TextView(ctx).apply {
            text = "+ Add"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            background = rounded(ACCENT, 18)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            setOnClickListener { onAddItem() }
        })
        root.addView(head, lp(bottom = dp(12)))
        columnsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columnsRow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    fun render() {
        // includePins: the board's own PIN pad gates check-offs.
        val status = JSONObject(Routines.statusJson(ctx, includePins = true))
        val items = status.getJSONArray("items")
        val doneToday = toSet(status.getJSONArray("doneToday"))
        val members = status.getJSONArray("members")
        val todayCal = Calendar.getInstance()
        val todayDow = todayCal.get(Calendar.DAY_OF_WEEK)
        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(todayCal.time)

        columnsRow.removeAllViews()

        if (items.length() == 0) {
            columnsRow.addView(TextView(ctx).apply {
                text = "No routines yet — set them up from your phone\n(⚙ Settings shows the address)"
                textSize = 17f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, MATCH))
            return
        }

        val memberIds = (0 until members.length()).map { members.getJSONObject(it).optString("id") }
        val groups = LinkedHashMap<String, MutableList<JSONObject>>() // memberId ("" = anyone)
        for (i in 0 until members.length()) groups[members.getJSONObject(i).optString("id")] = ArrayList()
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i)
            if (!Routines.isDueOn(c, todayDate, todayDow)) continue
            val mid = c.optString("memberId").takeIf { it in memberIds } ?: ""
            groups.getOrPut(mid) { ArrayList() }.add(c)
        }

        for ((mid, list) in groups) {
            if (mid.isEmpty() && list.isEmpty()) continue
            val member = (0 until members.length()).map { members.getJSONObject(it) }
                .find { it.optString("id") == mid }
            val name = member?.optString("name") ?: "Anyone"
            val color = Members.parse(member?.optString("color") ?: "#A3A8B0")
            val doneCount = list.count { doneToday.contains(it.optString("id")) }
            val total = list.size

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, 16)
                elevation = dp(2).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            // Header: name + done count -------------------------------------
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(8) })
            head.addView(TextView(ctx).apply {
                text = name
                textSize = 18f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            head.addView(TextView(ctx).apply {
                text = if (total > 0 && doneCount == total) "✓ all done" else "$doneCount/$total"
                textSize = 15f
                setTextColor(if (total > 0 && doneCount == total) color else INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(head, lp(bottom = dp(8)))

            // Progress bar ----------------------------------------------------
            val track = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(PILL, 5)
            }
            val frac = if (total == 0) 0f else doneCount.toFloat() / total
            if (frac > 0f) track.addView(View(ctx).apply { background = rounded(color, 5) },
                LinearLayout.LayoutParams(0, dp(10), frac))
            track.addView(View(ctx), LinearLayout.LayoutParams(0, dp(10), 1f - frac))
            col.addView(track, lp(bottom = dp(12)))

            // Item cards, grouped by section ---------------------------------
            val cardsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            if (list.isEmpty()) {
                cardsBox.addView(TextView(ctx).apply {
                    text = "Nothing today 🎉"
                    textSize = 15f
                    setTextColor(MUTED)
                })
            }
            val order = Routines.SECTIONS.map { it.first }
            val sectionIcon = Routines.SECTIONS.toMap()
            val usedSections = list.map { it.optString("section").ifEmpty { "morning" } }.toSet()
            val showHeaders = usedSections.size > 1
            for (section in order) {
                val inSection = list.filter {
                    (it.optString("section").ifEmpty { "morning" }) == section
                }
                if (inSection.isEmpty()) continue
                if (showHeaders) cardsBox.addView(TextView(ctx).apply {
                    text = "${sectionIcon[section] ?: ""}  ${section.replaceFirstChar { it.uppercase() }}"
                    textSize = 12f
                    setTextColor(MUTED)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    letterSpacing = 0.08f
                }, lp(top = dp(2), bottom = dp(6)))
                for (c in inSection.sortedBy { doneToday.contains(it.optString("id")) }) {
                    cardsBox.addView(itemCard(c, color, name, member, doneToday), lp(bottom = dp(10)))
                }
            }
            col.addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(cardsBox, FrameLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, 0, 1f))

            columnsRow.addView(col, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                leftMargin = dp(5); rightMargin = dp(5)
            })
        }
    }

    private fun itemCard(
        c: JSONObject, color: Int, name: String, member: JSONObject?, doneToday: Set<String>
    ): View {
        val done = doneToday.contains(c.optString("id"))
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (done) rounded(color, Palette.R_ELEMENT)
                         else roundedStroke(BG_SOFT, Palette.R_ELEMENT, dp(2), mix(color, Color.WHITE, 0.55f))
            setPadding(dp(14), dp(13), dp(14), dp(13))
        }
        card.addView(TextView(ctx).apply {
            text = c.optString("icon").ifEmpty { "✅" }
            textSize = 24f
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(12) })
        card.addView(TextView(ctx).apply {
            text = c.optString("title")
            textSize = 16.5f
            setTextColor(if (done) Color.WHITE else INK)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (c.optBoolean("oneTime")) card.addView(TextView(ctx).apply {
            text = "1×"
            textSize = 12f
            setTextColor(if (done) Color.WHITE else MUTED)
            setPadding(0, 0, dp(8), 0)
        })
        card.addView(TextView(ctx).apply {
            text = if (done) "✓" else ""
            textSize = 22f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        card.setOnLongClickListener {
            onRemoveItem(c.optString("id"), "${c.optString("icon")} ${c.optString("title")} ($name)")
            true
        }
        card.setOnClickListener {
            card.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).withEndAction {
                card.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                onGate(member?.optString("pin") ?: "", name) {
                    runCatching {
                        Routines.mutate(ctx, JSONObject()
                            .put("action", "toggle").put("itemId", c.optString("id")))
                    }
                }
            }.start()
        }
        return card
    }

    // ------------------------------------------------------------ helpers

    private fun toSet(arr: JSONArray): Set<String> =
        (0 until arr.length()).map { arr.optString(it) }.toSet()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun roundedStroke(fill: Int, radius: Int, w: Int, stroke: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(w, stroke)
    }

    private fun mix(c1: Int, c2: Int, f: Float): Int {
        fun ch(a: Int, b: Int) = (a + (b - a) * f).roundToInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(c1), Color.red(c2)),
            ch(Color.green(c1), Color.green(c2)), ch(Color.blue(c1), Color.blue(c2)))
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val CARD get() = Palette.CARD
        private val BG_SOFT get() = Palette.CARD_SOFT
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val ACCENT get() = Palette.ACCENT
    }
}
