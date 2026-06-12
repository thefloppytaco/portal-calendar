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
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The board's Chores tab: one column per family member with today's chores as
 * big tappable cards, plus a weekly star tally with a progress bar toward each
 * member's goal. Tapping a card bounces it and toggles completion (a star).
 */
class ChoresTab(
    private val ctx: Context,
    private val onAddChore: () -> Unit = {},
    private val onGate: (memberPin: String, memberName: String, action: () -> Unit) -> Unit =
        { _, _, action -> action() },
    private val onRemoveChore: (id: String, label: String) -> Unit = { _, _ -> }
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
            text = "TODAY'S CHORES"
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
            setOnClickListener { onAddChore() }
        })
        root.addView(head, lp(bottom = dp(12)))
        columnsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(columnsRow, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    fun render() {
        // includePins: the board's own PIN pad gates chore check-offs.
        val status = JSONObject(Chores.statusJson(ctx, includePins = true))
        val chores = status.getJSONArray("chores")
        val doneToday = toSet(status.getJSONArray("doneToday"))
        val stars = status.getJSONObject("stars")
        val goals = status.getJSONObject("goals")
        val members = status.getJSONArray("members")
        val todayCal = Calendar.getInstance()
        val todayDow = todayCal.get(Calendar.DAY_OF_WEEK)
        val todayDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(todayCal.time)

        columnsRow.removeAllViews()

        if (chores.length() == 0) {
            columnsRow.addView(TextView(ctx).apply {
                text = "No chores yet — set them up from your phone\n(⚙ Settings shows the address)"
                textSize = 17f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, MATCH))
            return
        }

        val memberIds = (0 until members.length()).map { members.getJSONObject(it).optString("id") }
        val groups = LinkedHashMap<String, MutableList<JSONObject>>() // memberId ("" = anyone)
        for (i in 0 until members.length()) groups[members.getJSONObject(i).optString("id")] = ArrayList()
        for (i in 0 until chores.length()) {
            val c = chores.getJSONObject(i)
            if (!Chores.isDueOn(c, todayDate, todayDow)) continue
            val mid = c.optString("memberId").takeIf { it in memberIds } ?: ""
            groups.getOrPut(mid) { ArrayList() }.add(c)
        }

        for ((mid, list) in groups) {
            if (mid.isEmpty() && list.isEmpty()) continue
            val member = (0 until members.length()).map { members.getJSONObject(it) }
                .find { it.optString("id") == mid }
            val name = member?.optString("name") ?: "Anyone"
            val color = Members.parse(member?.optString("color") ?: "#A3A8B0")
            val starCount = stars.optInt(mid, 0)
            val goal = goals.optInt(mid, Chores.DEFAULT_GOAL)

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(CARD, 16)
                elevation = dp(2).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            // Header: name + weekly stars + progress toward goal -------------
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
                text = "⭐ $starCount/$goal"
                textSize = 15f
                setTextColor(INK)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            })
            col.addView(head, lp(bottom = dp(8)))

            // Progress bar ----------------------------------------------------
            val track = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(PILL, 5)
            }
            val frac = min(1f, starCount.toFloat() / goal)
            if (frac > 0f) track.addView(View(ctx).apply { background = rounded(color, 5) },
                LinearLayout.LayoutParams(0, dp(10), frac))
            track.addView(View(ctx), LinearLayout.LayoutParams(0, dp(10), 1f - frac))
            col.addView(track, lp(bottom = dp(12)))

            // Chore cards -----------------------------------------------------
            val cardsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            if (list.isEmpty()) {
                cardsBox.addView(TextView(ctx).apply {
                    text = "Nothing today 🎉"
                    textSize = 15f
                    setTextColor(MUTED)
                })
            }
            for (c in list.sortedBy { doneToday.contains(it.optString("id")) }) {
                val done = doneToday.contains(c.optString("id"))
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = if (done) rounded(color, Palette.R_ELEMENT)
                                 else roundedStroke(BG_SOFT, Palette.R_ELEMENT, dp(2), mix(color, Color.WHITE, 0.55f))
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                }
                card.addView(TextView(ctx).apply {
                    text = c.optString("icon").ifEmpty { "⭐" }
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
                    onRemoveChore(c.optString("id"), "${c.optString("icon")} ${c.optString("title")} ($name)")
                    true
                }
                card.setOnClickListener {
                    // Quick bounce, then toggle — behind the kid's own PIN if set.
                    card.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).withEndAction {
                        card.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        onGate(member?.optString("pin") ?: "", name) {
                            runCatching {
                                Chores.mutate(ctx, JSONObject()
                                    .put("action", "toggle").put("choreId", c.optString("id")))
                            }
                        }
                    }.start()
                }
                cardsBox.addView(card, lp(bottom = dp(10)))
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
        private val CARD = Palette.CARD
        private val BG_SOFT = Palette.CARD_SOFT
        private val PILL = Palette.PILL
        private val INK = Palette.INK
        private val MUTED = Palette.MUTED
        private val ACCENT = Palette.ACCENT
    }
}
