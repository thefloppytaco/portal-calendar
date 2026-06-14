package com.portal.calendar

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The board's Meals tab: a week grid of breakfast/lunch/dinner/snack slots.
 * Viewing is touch-first (tap a planned meal with a recipe to read it);
 * editing happens on the phone page.
 */
class MealsTab(
    private val ctx: Context,
    private val showDetail: (title: String, body: String) -> Unit,
    private val onPlanMeal: () -> Unit = {}
) {

    private var weekOffset = 0
    private lateinit var rangeLabel: TextView
    private lateinit var planButton: TextView
    private lateinit var grid: LinearLayout
    val view: LinearLayout = build()

    /** Back to the current week (called on takeover relaunch resets). */
    fun reset() { weekOffset = 0 }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun build(): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
        }
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nav.addView(TextView(ctx).apply {
            text = "MEALS"
            textSize = 13f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.14f
        })
        rangeLabel = TextView(ctx).apply {
            textSize = 15f
            setTextColor(MUTED)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(12), 0, 0, 0)
        }
        nav.addView(rangeLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        planButton = TextView(ctx).apply {
            text = "✨ Plan"
            textSize = 15f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = rounded(ACCENT, 18)
            setPadding(dp(16), dp(7), dp(16), dp(7))
            visibility = android.view.View.GONE
            setOnClickListener { onPlanMeal() }
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
        }
        nav.addView(planButton)
        nav.addView(pill("‹") { weekOffset--; render() })
        nav.addView(pill("This week") { weekOffset = 0; render() })
        nav.addView(pill("›") { weekOffset++; render() })
        root.addView(nav, lp(bottom = dp(12)))

        grid = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(grid, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    fun render() {
        planButton.visibility =
            if (Gemini.isReady(ctx)) android.view.View.VISIBLE else android.view.View.GONE
        val ws = Calendar.getInstance()
        ws.set(Calendar.HOUR_OF_DAY, 0); ws.set(Calendar.MINUTE, 0)
        ws.set(Calendar.SECOND, 0); ws.set(Calendar.MILLISECOND, 0)
        val wkStart = ConfigStore(ctx).weekStartResolved()
        while (ws.get(Calendar.DAY_OF_WEEK) != wkStart) ws.add(Calendar.DAY_OF_MONTH, -1)
        ws.add(Calendar.DAY_OF_MONTH, weekOffset * 7)
        val rangeFmt = SimpleDateFormat("MMM d", Locale.getDefault())
        val weekEnd = (ws.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 6) }
        rangeLabel.text = rangeFmt.format(ws.time) + " – " + rangeFmt.format(weekEnd.time)

        val today = Calendar.getInstance()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val nameFmt = SimpleDateFormat("EEE d", Locale.getDefault())
        // One file read for the whole week, not one per day.
        val weekPlan = Meals.planForWeek(ctx)

        grid.removeAllViews()
        for (i in 0..6) {
            val dayCal = (ws.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val isToday = dayCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    dayCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            val plan = weekPlan[dayFmt.format(dayCal.time)] ?: emptyMap()

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = if (isToday) strokeCard() else rounded(CARD, Palette.R_CARD)
                elevation = dp(1).toFloat()
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            col.addView(TextView(ctx).apply {
                text = nameFmt.format(dayCal.time)
                textSize = 14f
                setTextColor(if (isToday) ACCENT else MUTED)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            }, lp(bottom = dp(8)))

            var anyMeal = false
            for ((slot, emoji) in SLOT_EMOJI) {
                val entry = plan[slot] ?: continue // only show planned slots
                anyMeal = true
                col.addView(TextView(ctx).apply {
                    text = emoji
                    textSize = 12f
                    setTextColor(FAINT)
                }, lp())
                col.addView(TextView(ctx).apply {
                    text = entry.optString("text").ifEmpty { "—" }
                    textSize = 13.5f
                    setTextColor(INK)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    minimumHeight = dp(44) // the row IS the tap target for the recipe
                    gravity = Gravity.CENTER_VERTICAL
                    run {
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setOnClickListener {
                            val r = Meals.recipe(ctx, entry.optString("recipeId"))
                            if (r != null) {
                                showDetail(r.optString("title"), buildString {
                                    val ing = r.optString("ingredients")
                                    val steps = r.optString("steps")
                                    if (ing.isNotEmpty()) append(ing)
                                    if (steps.isNotEmpty()) {
                                        if (isNotEmpty()) append("\n\n")
                                        append(steps)
                                    }
                                    if (isEmpty()) append("No details yet — add them on the page")
                                })
                            } else {
                                showDetail(entry.optString("text"),
                                    "Planned for ${slot.replaceFirstChar { c -> c.uppercase() }}")
                            }
                        }
                    }
                }, lp(bottom = dp(8)))
            }
            if (!anyMeal) {
                col.addView(TextView(ctx).apply {
                    text = "—"
                    textSize = 16f
                    setTextColor(FAINT)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(20), 0, 0)
                }, lp())
            }
            grid.addView(col, LinearLayout.LayoutParams(0, MATCH, 1f).apply {
                leftMargin = dp(3); rightMargin = dp(3)
            })
        }
    }

    // ------------------------------------------------------------ helpers

    private fun pill(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 15f
        setTextColor(INK)
        gravity = Gravity.CENTER
        minimumHeight = dp(44) // arm's-length tap target
        background = rounded(PILL, 18)
        setPadding(dp(16), dp(7), dp(16), dp(7))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun strokeCard() = GradientDrawable().apply {
        setColor(Palette.TODAY_CARD)
        cornerRadius = dp(Palette.R_CARD).toFloat()
        setStroke(dp(2), ACCENT)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = top; bottomMargin = bottom }

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private val SLOT_EMOJI = listOf(
            "breakfast" to "🍳 BREAKFAST", "lunch" to "🥪 LUNCH",
            "dinner" to "🍝 DINNER", "snack" to "🍎 SNACK")
        private val CARD get() = Palette.CARD
        private val PILL get() = Palette.PILL
        private val INK get() = Palette.INK
        private val MUTED get() = Palette.MUTED
        private val FAINT get() = Palette.FAINT
        private val ACCENT get() = Palette.ACCENT
    }
}
