package com.zombiedots.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Movie-style launch button: tap to flip open the clear safety case, then press and HOLD
 * the red button while it counts 3, 2, 1. Letting go early cancels the launch.
 */
@SuppressLint("ViewConstructor")
class NukeButtonView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onLaunch()
        fun onCountdown(n: Int)
        fun onMessage(text: String)
    }

    private enum class State { CLOSED, OPEN, HOLDING }

    /** Usable right now (game running and nuke not spent). */
    var available = false
        set(v) {
            if (field != v && !v) close()
            field = v
            invalidate()
        }
    /** The nuke has been spent this game. */
    var used = false
        set(v) { field = v; invalidate() }
    var vibrate = true

    private val d = resources.displayMetrics.density
    private var state = State.CLOSED
    private var lid = 0f          // 0 = case closed, 1 = flipped fully open
    private var holdStart = 0L
    private var lastCount = 0
    private var lastTouch = 0L
    private var lastFrame = 0L

    companion object {
        const val HOLD_MS = 3000L
        const val AUTO_CLOSE_MS = 6000L
    }

    // ---------- paints
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2A2D35") }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progress = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#FFE066")
    }
    private val lidFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lidEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d }
    private val hinge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9AA3B2") }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12.5f * d; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE2C8"); textSize = 10.5f * d; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 22 * d; typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, Color.RED)
    }
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    private fun buzz(type: Int) { if (vibrate) performHapticFeedback(type) }

    fun close() {
        if (state == State.HOLDING) listener.onCountdown(0)
        state = State.CLOSED
        updateDescription()
        invalidate()
    }

    private fun updateDescription() {
        contentDescription = when {
            used -> "Nuke. Used up."
            !available -> "Nuke. Not ready."
            state == State.CLOSED -> "Nuke. Tap to open the safety case."
            else -> "Nuke. Case is open. Press and hold for 3 seconds to launch."
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouch = SystemClock.uptimeMillis()
                if (!available) {
                    listener.onMessage(if (used) "The nuke is used up. You only get one each game." else "Put your team on the map first.")
                    return true
                }
                when (state) {
                    State.CLOSED -> {
                        state = State.OPEN
                        buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener.onMessage("Safety case open. Press and hold the red button to launch.")
                    }
                    State.OPEN -> if (lid > 0.8f) {
                        state = State.HOLDING
                        holdStart = SystemClock.uptimeMillis()
                        lastCount = 0
                        buzz(HapticFeedbackConstants.LONG_PRESS)
                    }
                    State.HOLDING -> {}
                }
                updateDescription()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouch = SystemClock.uptimeMillis()
                if (state == State.HOLDING) {
                    state = State.OPEN
                    listener.onCountdown(0)
                    listener.onMessage("Launch cancelled.")
                    updateDescription()
                    invalidate()
                }
            }
        }
        return true
    }

    // Lets TalkBack users launch with a long press
    override fun performLongClick(): Boolean {
        if (available && state == State.OPEN) {
            state = State.HOLDING
            holdStart = SystemClock.uptimeMillis()
            invalidate()
        }
        return super.performLongClick()
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1000f, 0.05f)
        lastFrame = now
        var animating = false

        // Lid animation
        val target = if (state == State.CLOSED) 0f else 1f
        if (lid != target) {
            lid = if (lid < target) min(target, lid + dt / 0.35f) else max(target, lid - dt / 0.3f)
            animating = true
        }
        // Countdown
        if (state == State.HOLDING) {
            animating = true
            val elapsed = now - holdStart
            val n = (3 - elapsed / 1000).toInt().coerceIn(1, 3)
            if (n != lastCount) {
                lastCount = n
                listener.onCountdown(n)
                buzz(HapticFeedbackConstants.LONG_PRESS)
            }
            if (elapsed >= HOLD_MS) {
                state = State.CLOSED
                listener.onCountdown(0)
                listener.onLaunch()
            }
        }
        // Close the case again if nobody touches it
        if (state == State.OPEN && now - lastTouch > AUTO_CLOSE_MS) { state = State.CLOSED; updateDescription() }
        if (state == State.OPEN) animating = true

        drawPanel(c, now)
        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun drawPanel(c: Canvas, now: Long) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = 14 * d
        alpha = if (available) 1f else 0.45f

        // Orange glass panel
        rect.set(0f, 0f, w, h)
        panel.shader = LinearGradient(0f, 0f, 0f, h,
            Color.argb(160, 255, 138, 61), Color.argb(80, 255, 138, 61), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, panel)
        panelStroke.color = Color.argb(230, 255, 138, 61)
        c.drawRoundRect(rect, r, r, panelStroke)

        // Hazard stripes along the bottom edge
        c.save()
        rect.set(0f, h - 5 * d, w, h)
        c.clipRect(rect)
        var x = -h
        var i = 0
        while (x < w + h) {
            stripe.color = if (i % 2 == 0) Color.argb(170, 255, 210, 40) else Color.argb(170, 20, 20, 20)
            val p = android.graphics.Path()
            p.moveTo(x, h); p.lineTo(x + 8 * d, h - 5 * d); p.lineTo(x + 16 * d, h - 5 * d); p.lineTo(x + 8 * d, h); p.close()
            c.drawPath(p, stripe)
            x += 8 * d; i++
        }
        c.restore()

        // The big red button (with metal base)
        val bx = 26 * d
        val by = h / 2f - 1 * d
        val br = min(13 * d, h * 0.27f)
        c.drawCircle(bx, by, br + 4 * d, baseRing)
        val pressed = state == State.HOLDING
        val open = lid > 0.5f
        if (open && available) {
            val pulse = 0.5f + 0.5f * kotlin.math.sin(now / 180.0).toFloat()
            glow.shader = RadialGradient(bx, by, br * 2.4f,
                Color.argb((90 + 80 * pulse).toInt(), 255, 40, 30), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(bx, by, br * 2.4f, glow)
        }
        buttonPaint.shader = RadialGradient(bx - br * 0.3f, by - br * 0.4f, br * 1.3f,
            if (pressed) Color.parseColor("#C41E1E") else Color.parseColor("#FF5A4A"),
            if (pressed) Color.parseColor("#6E0B0B") else Color.parseColor("#B01414"), Shader.TileMode.CLAMP)
        c.drawCircle(bx, by, if (pressed) br * 0.92f else br, buttonPaint)
        if (pressed) {
            val p = ((now - holdStart).toFloat() / HOLD_MS).coerceIn(0f, 1f)
            rect.set(bx - br - 6 * d, by - br - 6 * d, bx + br + 6 * d, by + br + 6 * d)
            c.drawArc(rect, -90f, 360f * p, false, progress)
        }

        // Clear safety case: hinged at the top, flips up as it opens
        val lx1 = bx - br - 9 * d
        val lx2 = bx + br + 9 * d
        val ly1 = by - br - 9 * d
        val fullH = (br + 9 * d) * 2
        val lidH = fullH * (1f - 0.86f * lid)
        rect.set(lx1, ly1 - lid * 3 * d, lx2, ly1 - lid * 3 * d + lidH)
        lidFill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Color.argb((95 - 40 * lid).toInt(), 200, 235, 255), Color.argb((45 - 20 * lid).toInt(), 200, 235, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, 6 * d, 6 * d, lidFill)
        lidEdge.color = Color.argb((210 - 60 * lid).toInt(), 230, 245, 255)
        c.drawRoundRect(rect, 6 * d, 6 * d, lidEdge)
        // shine
        lidEdge.color = Color.argb((120 * (1f - lid)).toInt(), 255, 255, 255)
        c.drawLine(rect.left + 5 * d, rect.top + 4 * d, rect.left + 5 * d + (rect.width() * 0.35f), rect.top + 4 * d, lidEdge)
        // hinge
        c.drawRect(lx1 + 4 * d, ly1 - 4 * d, lx2 - 4 * d, ly1 - 1.5f * d, hinge)

        // Labels
        val tx = lx2 + 8 * d
        when {
            used -> { c.drawText("☢ NUKE", tx, h / 2f - 2 * d, title); c.drawText("used up", tx, h / 2f + 13 * d, sub) }
            !available -> { c.drawText("☢ NUKE", tx, h / 2f + 5 * d, title) }
            state == State.HOLDING -> {
                c.drawText("$lastCount", tx, h / 2f + 9 * d, big)
                c.drawText("hold…", tx + 20 * d, h / 2f + 5 * d, sub)
            }
            state == State.OPEN -> { c.drawText("HOLD", tx, h / 2f - 2 * d, title); c.drawText("red button", tx, h / 2f + 13 * d, sub) }
            else -> { c.drawText("☢ NUKE", tx, h / 2f - 2 * d, title); c.drawText("tap to open", tx, h / 2f + 13 * d, sub) }
        }
    }
}
