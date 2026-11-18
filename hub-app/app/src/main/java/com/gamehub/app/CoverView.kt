package com.gamehub.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * What the helper's full-screen cover shows:
 *  - plain black: while the screen is off, so you wake to black and the NEXUS lock screen rises out of it
 *  - the opening of NEXUS Recent Apps (square button): the app you were in shrinks into its card over the
 *    NEXUS backdrop, exactly where Recent Apps puts that card, then the cover fades and Recent Apps carries on.
 * It never takes touches. Icons and the app's own last picture only, never anything else.
 */
@SuppressLint("ViewConstructor")
class CoverView(c: Context) : View(c) {
    companion object {
        const val ZOOM_MS = 300L

        /** The centre card in NEXUS Recent Apps for a screen [w] × [h] (the same sums as SwitcherView). */
        fun cardRect(w: Float, h: Float, out: RectF): RectF {
            val cw = w * 0.62f; val ch = min(cw * 1.9f, h * 0.6f); val cy = h * 0.47f
            out.set(w / 2 - cw / 2, cy - ch / 2, w / 2 + cw / 2, cy + ch / 2)
            return out
        }

        /** The Recent Apps backdrop: deep blue with a faint grid (shared so both screens match exactly). */
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
    private var shot: Bitmap? = null
    private var start = 0L
    private var bg: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()
    private val rr = RectF(); private val card = RectF(); private val dst = RectF()
    private val src = Rect()

    /** Shows the Recent Apps opening. [s] is the last picture of the app you were in (null: just the backdrop). */
    fun showRecents(s: Bitmap?) {
        recents = true; shot = s; start = SystemClock.uptimeMillis()
        invalidate()
    }

    /** Back to plain black (ready for the screen turning off). */
    fun showBlack() {
        if (!recents && shot == null) return
        recents = false; shot = null
        invalidate()
    }

    /** When the shrinking finishes (uptime ms); 0 if there's nothing to wait for. */
    fun zoomEnd(): Long = if (recents && shot != null) start + ZOOM_MS else 0L

    override fun onDraw(c: Canvas) {
        if (!recents) { c.drawColor(Color.BLACK); return }
        val cur = bg
        val back = if (cur != null && cur.width == width && cur.height == height) cur else backdrop(width, height, d).also { bg = it }
        c.drawBitmap(back, 0f, 0f, null)
        val s = shot ?: return
        val w = width.toFloat(); val h = height.toFloat()
        val k = ((SystemClock.uptimeMillis() - start) / ZOOM_MS.toFloat()).coerceIn(0f, 1f)
        val e = 1f - (1f - k) * (1f - k) * (1f - k) // ease out
        cardRect(w, h, card)
        rr.set(card.left * e, card.top * e, w + (card.right - w) * e, h + (card.bottom - h) * e)
        val rad = 16 * d * e
        // The soft glow Recent Apps has behind its centre card, growing in as the card settles
        paint.shader = RadialGradient(rr.centerX(), rr.centerY(), rr.width(), intArrayOf(Color.argb((90 * e).toInt(), 90, 150, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(rr.left - rr.width() * 0.5f, rr.top - 60 * d, rr.right + rr.width() * 0.5f, rr.bottom + 60 * d, paint); paint.shader = null
        // The app's picture, filling the card from the top (as Recent Apps draws it)
        clip.reset(); clip.addRoundRect(rr, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        paint.color = Color.rgb(10, 16, 34); c.drawRect(rr, paint)
        val scale = rr.width() / s.width
        src.set(0, 0, s.width, min(s.height, (rr.height() / scale).toInt()))
        dst.set(rr.left, rr.top, rr.right, rr.top + src.height() * scale)
        paint.color = Color.WHITE
        c.drawBitmap(s, src, dst, paint)
        c.restore()
        line.strokeWidth = 1.8f * d; line.color = Color.argb((210 * e).toInt(), 150, 195, 255)
        c.drawRoundRect(rr, rad, rad, line)
        if (k < 1f) postInvalidateOnAnimation()
    }
}
