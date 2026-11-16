package com.countdown.app

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.SystemClock

private fun c(hex: String) = Color.parseColor(hex)

/**
 * Widget outline styles. Moving ones have colors that travel around the border
 * (like hovering loot in Arc Raiders); the rest are still glows.
 */
enum class Glow(
    val label: String,
    val drawable: Int,
    val color: Int,
    val palette: IntArray? = null
) {
    AURORA("Aurora (moving)", 0, c("#7FF5E0"),
        intArrayOf(c("#3DF5D0"), c("#3DA5FF"), c("#8A5CFF"), c("#FF5CC8"), c("#3DF5D0"))),
    EMBER("Ember (moving)", 0, c("#FFC24D"),
        intArrayOf(c("#FFD23D"), c("#FF8A3D"), c("#FF3D5A"), c("#FF8A3D"), c("#FFD23D"))),
    TOXIC("Toxic (moving)", 0, c("#B6FF3D"),
        intArrayOf(c("#B6FF3D"), c("#3DFF8A"), c("#3DE0FF"), c("#3DFF8A"), c("#B6FF3D"))),
    RAINBOW("Full rainbow (moving)", 0, c("#FFFFFF"),
        intArrayOf(c("#FF4D4D"), c("#FFA64D"), c("#FFF04D"), c("#4DFF88"), c("#4DD8FF"),
            c("#6E5CFF"), c("#FF4DE1"), c("#FF4D4D"))),
    COMMON("Common (white, still)", R.drawable.glow_common, c("#C9CED6")),
    UNCOMMON("Uncommon (green, still)", R.drawable.glow_uncommon, c("#4BE36B")),
    RARE("Rare (blue, still)", R.drawable.glow_rare, c("#3DA5FF")),
    EPIC("Epic (purple, still)", R.drawable.glow_epic, c("#C65BFF")),
    LEGENDARY("Legendary (gold, still)", R.drawable.glow_legendary, c("#FFB13B")),
    AUTO("Auto rarity: rarer as it gets closer", R.drawable.glow_legendary, c("#FFB13B")),
    NONE("No glow", R.drawable.widget_bg, c("#FFB547"));

    val moving get() = palette != null

    /** The glow actually shown, resolving AUTO from how much time is left. */
    fun resolve(remainingMs: Long): Glow {
        if (this != AUTO) return this
        val hours = remainingMs / 3_600_000.0
        return when {
            hours > 24 * 30 -> COMMON
            hours > 24 * 7 -> UNCOMMON
            hours > 24 -> RARE
            hours > 1 -> EPIC
            else -> LEGENDARY
        }
    }

    /** Short name for the button. */
    val shortLabel get() = label.substringBefore(" (").substringBefore(":")
}

/** Draws the glowing border with the color band rotated to [angle] degrees. */
object BorderPainter {
    const val FRAMES = 16

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()

    /** [s] = pixels per dp. */
    fun draw(canvas: Canvas, w: Float, h: Float, s: Float, palette: IntArray, angle: Float) {
        val inset = 9f * s
        rect.set(inset, inset, w - inset, h - inset)
        val r = 20f * s
        val cx = w / 2f
        val cy = h / 2f

        fill.shader = LinearGradient(0f, rect.top, 0f, rect.bottom,
            Color.parseColor("#F21F2733"), Color.parseColor("#F2141A22"), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, fill)

        val sweep = SweepGradient(cx, cy, palette, null)
        matrix.setRotate(angle, cx, cy)
        sweep.setLocalMatrix(matrix)

        glow.shader = sweep
        glow.strokeWidth = 7f * s
        glow.maskFilter = BlurMaskFilter(6f * s, BlurMaskFilter.Blur.NORMAL)
        glow.alpha = 200
        canvas.drawRoundRect(rect, r, r, glow)

        edge.shader = sweep
        edge.strokeWidth = 2.2f * s
        canvas.drawRoundRect(rect, r, r, edge)
    }

    /** All animation frames for a widget of [wDp] x [hDp], kept small enough for the home screen. */
    fun frames(wDp: Int, hDp: Int, palette: IntArray): List<Bitmap> {
        var s = 1.25f
        val maxPx = 440f
        if (wDp * s > maxPx) s = maxPx / wDp
        if (hDp * s > maxPx) s = minOf(s, maxPx / hDp)
        val w = (wDp * s).toInt().coerceAtLeast(40)
        val h = (hDp * s).toInt().coerceAtLeast(40)
        return (0 until FRAMES).map { i ->
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp), w.toFloat(), h.toFloat(), s, palette, 360f * i / FRAMES)
            bmp
        }
    }
}

/** Smoothly animated version for the preview card inside the app. */
class MovingGlowDrawable(private val palette: IntArray, private val density: Float) : Drawable() {
    override fun draw(canvas: Canvas) {
        val angle = (SystemClock.uptimeMillis() % 2000L) / 2000f * 360f
        BorderPainter.draw(canvas, bounds.width().toFloat(), bounds.height().toFloat(), density, palette, angle)
        invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
