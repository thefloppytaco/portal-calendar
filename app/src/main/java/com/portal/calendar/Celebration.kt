package com.portal.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Transparent, non-interactive overlay that fires a short burst of emoji
 * particles (stars/confetti) from a point — the chore-completion celebration.
 *
 * Physics + fade advance on the view's own animation pulse; the moment no
 * particles remain it stops re-invalidating, so it costs nothing at rest. It
 * sits on top of the whole board but consumes no touches (a kid can keep
 * tapping cards while it plays).
 */
class CelebrationView(ctx: Context) : View(ctx) {

    private class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var rot: Float, val vrot: Float,
        val text: String, val size: Float,
        var life: Float // 1 → 0
    )

    private val particles = ArrayList<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val density = ctx.resources.displayMetrics.density
    private var lastFrame = 0L

    // small, frequent reward vs. the big weekly-goal payoff
    private val popEmojis = arrayOf("⭐", "🎉", "✨", "🌟", "🎊", "💫")
    private val bigEmojis = arrayOf("⭐", "🎉", "🏆", "🎈", "✨", "🌟", "🎊", "💯")

    init {
        isClickable = false
        isFocusable = false
    }

    /**
     * @param cx,cy burst origin in this view's pixels
     * @param big   a weekly star goal was just reached → a bigger, taller burst
     */
    fun burst(cx: Float, cy: Float, big: Boolean) {
        val count = if (big) 46 else 22
        val set = if (big) bigEmojis else popEmojis
        val spread = if (big) 160f else 120f          // cone width, degrees
        val baseSpeed = (if (big) 9f else 6.5f) * density
        repeat(count) {
            // Fan upward (-90° is straight up) within the cone, then let gravity arc them down.
            val ang = (-90f + (Random.nextFloat() - 0.5f) * spread) * Math.PI.toFloat() / 180f
            val speed = baseSpeed * (0.5f + Random.nextFloat())
            particles.add(Particle(
                x = cx, y = cy,
                vx = cos(ang) * speed,
                vy = sin(ang) * speed,
                rot = Random.nextFloat() * 360f,
                vrot = (Random.nextFloat() - 0.5f) * 24f,
                text = set[Random.nextInt(set.size)],
                size = (if (big) 30f else 22f) * (0.7f + Random.nextFloat() * 0.6f) * density,
                life = 1f
            ))
        }
        if (particles.isNotEmpty() && lastFrame == 0L) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (particles.isEmpty()) { lastFrame = 0L; return }
        val now = System.nanoTime()
        // Frames since last draw (1.0 == one 60Hz frame); clamp so a stutter or
        // first frame can't fling everything off-screen in one step.
        val dt = if (lastFrame == 0L) 1f else ((now - lastFrame) / 16_666_667f).coerceIn(0.2f, 3f)
        lastFrame = now
        val gravity = 0.5f * density

        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.vy += gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vrot * dt
            p.life -= 0.012f * dt
            if (p.life <= 0f || p.y > height + 100) { it.remove(); continue }
            paint.textSize = p.size
            paint.alpha = (255 * p.life.coerceIn(0f, 1f)).toInt()
            canvas.save()
            canvas.rotate(p.rot, p.x, p.y)
            canvas.drawText(p.text, p.x, p.y + p.size * 0.35f, paint) // nudge baseline to center
            canvas.restore()
        }
        if (particles.isNotEmpty()) postInvalidateOnAnimation() else lastFrame = 0L
    }
}
