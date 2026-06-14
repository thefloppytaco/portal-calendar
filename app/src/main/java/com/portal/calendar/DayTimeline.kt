package com.portal.calendar

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A Google-Calendar-style day timeline: a fixed-height canvas where hour lines,
 * labels, a "now" indicator and event blocks are positioned absolutely by time.
 * Overlapping events are packed into side-by-side columns so you can SEE the
 * overlap (the old day view only showed start-time tiles).
 *
 * Layout math is done in onLayout from the measured width, so it's correct
 * regardless of when the data is set or how the parent scroll sizes it.
 */
class DayTimeline(ctx: Context) : ViewGroup(ctx) {

    var totalHeight = 0   // px — the full scrollable height
    var gutter = 0        // px — left strip reserved for hour labels

    /** Per-child placement. kind picks the horizontal rule. */
    class LP(
        val top: Int,
        val h: Int,             // px height; < 0 = wrap content
        val kind: Int,
        val colFrac: Float = 0f, // event: fraction across the event area
        val wFrac: Float = 1f    // event: fraction of the event area width
    ) : ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    companion object {
        const val FULL = 0   // hour line / now line: gutter → right edge
        const val LABEL = 1  // hour label: left edge, gutter-wide
        const val EVENT = 2  // event block: positioned within the event area
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val eventArea = (w - gutter).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            val cw = when (lp.kind) {
                LABEL -> gutter
                FULL -> eventArea
                else -> (eventArea * lp.wFrac).toInt().coerceAtLeast(1)
            }
            if (lp.h >= 0) {
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(lp.h, MeasureSpec.EXACTLY))
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            }
        }
        setMeasuredDimension(w, totalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val eventArea = (w - gutter).coerceAtLeast(1)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as LP
            val x = when (lp.kind) {
                LABEL -> 0
                FULL -> gutter
                else -> gutter + (eventArea * lp.colFrac).toInt()
            }
            c.layout(x, lp.top, x + c.measuredWidth, lp.top + c.measuredHeight)
        }
    }

    // ---- overlap column packing -------------------------------------------

    class Placed(val index: Int, val col: Int, val cols: Int)

    /**
     * Greedy interval-graph column assignment. Events that transitively overlap
     * form a cluster; within a cluster each event takes the first free column,
     * and every event in the cluster is widened to 1/clusterColumns.
     * [items] must be sorted by start. Returns placement per input index.
     */
    fun pack(items: List<Pair<Long, Long>>): List<Placed> {
        val out = arrayOfNulls<Placed>(items.size)
        var i = 0
        while (i < items.size) {
            // Grow a cluster while the next event starts before the cluster ends.
            var clusterEnd = items[i].second
            var j = i + 1
            while (j < items.size && items[j].first < clusterEnd) {
                if (items[j].second > clusterEnd) clusterEnd = items[j].second
                j++
            }
            // Assign columns greedily across [i, j).
            val colEnds = ArrayList<Long>()
            val cols = IntArray(j - i)
            for (k in i until j) {
                val start = items[k].first
                var placed = -1
                for (c in colEnds.indices) {
                    if (colEnds[c] <= start) { placed = c; colEnds[c] = items[k].second; break }
                }
                if (placed < 0) { placed = colEnds.size; colEnds.add(items[k].second) }
                cols[k - i] = placed
            }
            val nCols = colEnds.size
            for (k in i until j) out[k] = Placed(k, cols[k - i], nCols)
            i = j
        }
        return out.filterNotNull()
    }
}
