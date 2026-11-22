package com.gamehub.app

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil

/**
 * Soft glows and dark outlines behind text, blurred once and then reused. (A text shadow is blurred afresh on
 * every frame it's drawn, and again for every size it's drawn at, which is a lot of work for a screen that
 * moves all the time: here each glow is made once, kept as a small see-through picture, and only coloured,
 * faded and scaled when drawn.)
 *
 * Each glow is made at a fixed reference size and scaled to the size it's drawn at (a blur looks the same
 * scaled). The oldest are let go once there are more than [max].
 */
class TextGlow(private val typeface: Typeface, private val refSize: Float, private val letterSpacing: Float, private val max: Int = 48) {
    private class Mask(val bmp: Bitmap, val pad: Float, val base: Float)

    private val masks = object : LinkedHashMap<String, Mask>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Mask>?): Boolean = size > max
    }
    private val maker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = this@TextGlow.typeface; textSize = refSize; letterSpacing = this@TextGlow.letterSpacing
        color = android.graphics.Color.WHITE
    }
    private val dst = RectF()
    private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    private fun mask(text: String, blur: Float): Mask? {
        val key = text + "\u0000" + blur.toInt()
        masks[key]?.let { return it }
        return try {
            val pad = ceil(blur * 1.6f) + 2f
            val fm = maker.fontMetrics
            val w = (maker.measureText(text) + pad * 2).toInt().coerceIn(1, 4096)
            val h = (fm.descent - fm.ascent + pad * 2).toInt().coerceIn(1, 4096)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            maker.maskFilter = if (blur > 0f) BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL) else null
            val base = pad - fm.ascent
            Canvas(b).drawText(text, pad, base, maker)
            maker.maskFilter = null
            Mask(b, pad, base).also { masks[key] = it }
        } catch (_: Throwable) { null }
    }

    /**
     * Draws the glow of [text] in [color], as if the text were drawn left-aligned at ([x], [y]) at [size], with a
     * blur of [blur] (at the reference size). [grow] makes it a little bigger around its middle.
     */
    fun draw(c: Canvas, text: String, x: Float, y: Float, size: Float, blur: Float, color: Int, grow: Float = 1f) {
        if (android.graphics.Color.alpha(color) == 0 || text.isEmpty()) return
        val m = mask(text, blur) ?: return
        val s = size / refSize
        dst.set(x - m.pad * s, y - m.base * s, x + (m.bmp.width - m.pad) * s, y + (m.bmp.height - m.base) * s)
        if (grow != 1f) {
            val cx = dst.centerX(); val cy = dst.centerY(); val hw = dst.width() / 2 * grow; val hh = dst.height() / 2 * grow
            dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
        }
        p.color = color
        c.drawBitmap(m.bmp, null, dst, p)
    }

    /** Lets go of every glow kept (they're made again when next needed). */
    fun clear() { masks.clear() }
}
