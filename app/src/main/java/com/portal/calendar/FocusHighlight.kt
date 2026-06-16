package com.portal.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.roundToInt

/**
 * A touch-transparent overlay that draws a rounded accent outline around the
 * currently focused view — so people navigating the board with a remote (Portal
 * TV, or any paired D-pad) can see where they are as they scroll around. It is
 * invisible during touch use: the highlight only appears when the window is in
 * non-touch (directional) mode, so the touchscreen Portals never see it.
 *
 * It tracks the live focused view every frame (via an [OnPreDrawListener]) so
 * the outline stays glued to it through scrolling, scaling and the card-bounce
 * animations, and follows focus moves via an [OnGlobalFocusChangeListener].
 * Nothing else in the app has to know it exists.
 */
class FocusHighlight(ctx: Context) : View(ctx) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private var target: View? = null
    private val rect = RectF()              // current animated outline, in our coords
    private val want = RectF()              // where the outline should be this frame
    private var hasRect = false
    private var settled = false             // true once rect == want (stop invalidating)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
    }
    private val radius = dp(14f)
    private val pad = dp(3f)

    private val myLoc = IntArray(2)
    private val tLoc = IntArray(2)

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        target = newFocus
        // Snap straight to a fresh target the first time so it doesn't slide in
        // from a stale position across the screen.
        if (newFocus != null && !hasRect) measureTarget(snap = true)
        settled = false
        invalidate()
    }

    // Re-measure the focused view; keeps the outline pinned through scroll/scale/
    // bounce. Only invalidates while something is actually moving — once the
    // outline reaches its target it goes quiet, so this isn't a per-frame loop.
    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (visibleForFocus()) {
            if (measureTarget(snap = false)) invalidate()
        }
        true
    }

    init {
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalFocusChangeListener(focusListener)
        viewTreeObserver.addOnPreDrawListener(preDraw)
        target = rootView.findFocus()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalFocusChangeListener(focusListener)
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        super.onDetachedFromWindow()
    }

    // Never eat touches — purely decorative.
    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    private fun visibleForFocus(): Boolean {
        // isInTouchMode flips to false the moment a D-pad/remote key arrives,
        // and back to true on the next touch — exactly the gate we want.
        if (isInTouchMode) return false
        val t = target ?: return false
        return t.isShown && t.width > 0 && t.height > 0 && t !== this
    }

    /** Returns true if the outline moved (a redraw is needed). */
    private fun measureTarget(snap: Boolean): Boolean {
        val t = target
        if (t == null || !visibleForFocus()) { hasRect = false; return false }
        getLocationInWindow(myLoc)
        t.getLocationInWindow(tLoc)
        val left = (tLoc[0] - myLoc[0]).toFloat() - pad
        val top = (tLoc[1] - myLoc[1]).toFloat() - pad
        want.set(left, top, left + t.width + pad * 2, top + t.height + pad * 2)
        if (snap || !hasRect) { rect.set(want); hasRect = true; settled = true; return true }
        // Close enough? snap and go quiet so we stop invalidating every frame.
        val gap = kotlin.math.abs(want.left - rect.left) + kotlin.math.abs(want.top - rect.top) +
                  kotlin.math.abs(want.right - rect.right) + kotlin.math.abs(want.bottom - rect.bottom)
        if (gap < 1f) {
            if (settled) return false
            rect.set(want); settled = true; return true
        }
        // Ease toward the new position so a focus move glides ("scroll around").
        val f = 0.35f
        rect.left += (want.left - rect.left) * f
        rect.top += (want.top - rect.top) * f
        rect.right += (want.right - rect.right) * f
        rect.bottom += (want.bottom - rect.bottom) * f
        settled = false
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!visibleForFocus() || !hasRect) return
        val accent = Palette.ACCENT
        glow.color = (accent and 0x00FFFFFF) or 0x40000000   // ~25% alpha halo
        stroke.color = accent
        canvas.drawRoundRect(rect, radius, radius, glow)
        canvas.drawRoundRect(rect, radius, radius, stroke)
    }

    companion object {
        /**
         * Make every clickable view in [root] reachable by a D-pad. Touch
         * "buttons" here are plain TextViews with click listeners, which aren't
         * focusable by default — without this a remote can't land on them. Safe
         * on touch devices (focusable-not-in-touch-mode changes nothing there).
         */
        fun enableDpadFocus(root: View) {
            if (root is android.view.ViewGroup) {
                for (i in 0 until root.childCount) enableDpadFocus(root.getChildAt(i))
            }
            val interactive = root.isClickable || root.isLongClickable
            if (interactive && !root.isFocusable) {
                root.isFocusable = true
                // Don't steal the soft-keyboard focus model from inputs.
                root.isFocusableInTouchMode = false
            }
        }
    }
}
