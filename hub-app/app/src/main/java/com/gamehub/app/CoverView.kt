package com.gamehub.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import kotlin.math.min

/**
 * What the helper's full-screen cover shows:
 *  - plain black: while the screen is off, so you wake to black and the NEXUS lock screen rises out of it
 *  - the opening of NEXUS Recent Apps (square button): the app you were in shrinks into its card over the
 *    NEXUS backdrop, exactly where Recent Apps puts that card, then the cover fades and Recent Apps carries on.
 *
 * The shrinking and the fading run on the phone's render thread, so they stay smooth even while
 * Recent Apps is starting up underneath. It never takes touches.
 */
@SuppressLint("ViewConstructor")
class CoverView(c: Context) : FrameLayout(c) {
    companion object {
        const val ZOOM_MS = 240L
        /** Cards in Recent Apps are the whole screen, scaled down to this. */
        const val CARD_SCALE = 0.6f
        private const val CARD_CENTRE_Y = 0.47f

        /** The centre card in NEXUS Recent Apps for a screen [w] × [h] (shared with SwitcherView). */
        fun cardRect(w: Float, h: Float, out: RectF): RectF {
            val cw = w * CARD_SCALE; val ch = h * CARD_SCALE; val cy = h * CARD_CENTRE_Y
            out.set(w / 2 - cw / 2, cy - ch / 2, w / 2 + cw / 2, cy + ch / 2)
            return out
        }

        /** The Recent Apps backdrop: deep blue with a faint grid and a glow (shared so both screens match exactly). */
        fun backdrop(w: Int, h: Int, d: Float): Bitmap {
            val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.rgb(3, 6, 18), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            p.strokeWidth = 1f; p.color = Color.argb(14, 140, 190, 255)
            var gx = 0f; while (gx < w) { c.drawLine(gx, 0f, gx, h.toFloat(), p); gx += 40 * d }
            var gy = 0f; while (gy < h) { c.drawLine(0f, gy, w.toFloat(), gy, p); gy += 40 * d }
            p.shader = RadialGradient(w / 2f, h * 0.46f, w * 0.8f, intArrayOf(Color.argb(70, 50, 100, 230), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            return b
        }
    }

    private val d = resources.displayMetrics.density
    private var recents = false
    private var start = 0L
    private var bg: Bitmap? = null

    /** The backdrop (or plain black). */
    private val back = object : View(c) {
        override fun onDraw(canvas: Canvas) {
            if (!recents) { canvas.drawColor(Color.BLACK); return }
            val cur = bg
            val b = if (cur != null && cur.width == width && cur.height == height) cur else backdrop(width, height, d).also { bg = it }
            canvas.drawBitmap(b, 0f, 0f, null)
        }
    }

    /** The app's last picture, full screen at first; shrinks into its card with rounded corners. */
    private inner class Card(c: Context) : View(c) {
        var shot: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val src = Rect(); private val dst = RectF()
        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(10, 16, 34))
            val s = shot ?: return
            // Filling the width from the top, as Recent Apps draws it
            val scale = width.toFloat() / s.width
            src.set(0, 0, s.width, min(s.height, (height / scale).toInt()))
            dst.set(0f, 0f, width.toFloat(), src.height() * scale)
            canvas.drawBitmap(s, src, dst, paint)
        }
    }
    private val card = Card(c)

    init {
        addView(back, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        card.visibility = GONE
        // Rounded corners that end up matching the cards in Recent Apps once shrunk
        card.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, 16 * d / CARD_SCALE) }
        }
        card.clipToOutline = true
    }

    /** Gets the Recent Apps opening ready. [s] is the last picture of the app you were in (null: just the backdrop). */
    fun showRecents(s: Bitmap?) {
        recents = true; start = 0L
        resetCard()
        card.shot = s
        card.visibility = if (s != null) VISIBLE else GONE
        back.invalidate(); card.invalidate()
    }

    /** The cover is now on screen: start shrinking the picture into its card (on the render thread). */
    fun beginZoom() {
        if (!recents || card.visibility != VISIBLE || height == 0) return
        start = SystemClock.uptimeMillis()
        val h = height.toFloat()
        card.animate().scaleX(CARD_SCALE).scaleY(CARD_SCALE).translationY(h * CARD_CENTRE_Y - h / 2f)
            .setDuration(ZOOM_MS).setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
    }

    /** Back to plain black (ready for the screen turning off). */
    fun showBlack() {
        if (!recents && card.visibility == GONE) return
        recents = false; start = 0L
        resetCard()
        card.shot = null; card.visibility = GONE
        back.invalidate()
    }

    private fun resetCard() {
        card.animate().cancel()
        // Set each value twice so it always reaches the render thread (where the shrinking ran)
        card.scaleX = 0.5f; card.scaleX = 1f; card.scaleY = 0.5f; card.scaleY = 1f
        card.translationY = 1f; card.translationY = 0f; card.translationX = 0f
    }

    /** When the shrinking finishes (uptime ms); 0 if there's nothing to wait for. */
    fun zoomEnd(): Long = when {
        !recents || card.visibility != VISIBLE -> 0L
        start == 0L -> SystemClock.uptimeMillis() + ZOOM_MS // not on screen yet
        else -> start + ZOOM_MS
    }
}
