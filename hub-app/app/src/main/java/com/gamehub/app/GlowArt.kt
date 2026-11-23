package com.gamehub.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.abs
import kotlin.math.exp

/**
 * The soft light shapes NEXUS's world is drawn with, made once in high resolution so they stay smooth at any
 * size: a round glow with a bell-shaped falloff (no rings or banding), a small hot core, and a thin streak for
 * glints. All white: they're tinted where they're drawn.
 */
object GlowArt {
    /** A round glow: bright middle, falling away smoothly to nothing at the edge. */
    val soft: Bitmap by lazy {
        radial(256, intArrayOf(255, 222, 168, 110, 62, 28, 9, 0), floatArrayOf(0f, 0.1f, 0.22f, 0.36f, 0.52f, 0.68f, 0.85f, 1f))
    }

    /** A small, dense white core, for the very middle of a ball of light. */
    val core: Bitmap by lazy {
        radial(128, intArrayOf(255, 255, 210, 110, 30, 0), floatArrayOf(0f, 0.16f, 0.32f, 0.52f, 0.76f, 1f))
    }

    /** A thin streak of light, brightest in the middle and fading towards both ends (and across): for glints. */
    val streak: Bitmap by lazy {
        val w = 512; val h = 24
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val dy = (y - (h - 1) / 2f) / (h / 2f)
            val across = exp(-dy * dy * 6f)
            for (x in 0 until w) {
                val dx = abs(x - (w - 1) / 2f) / (w / 2f)
                val along = (1f - dx).coerceAtLeast(0f).let { it * it * it }
                val a = (255 * across * along).toInt().coerceIn(0, 255)
                px[y * w + x] = Color.argb(a, 255, 255, 255)
            }
        }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun radial(s: Int, alphas: IntArray, stops: FloatArray): Bitmap {
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, IntArray(alphas.size) { Color.argb(alphas[it], 255, 255, 255) }, stops, Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        return b
    }
}
