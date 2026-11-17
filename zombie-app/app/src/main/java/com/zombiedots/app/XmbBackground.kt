package com.zombiedots.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/** PS3 XMB-style backdrop: a deep gradient with slow, glowing ribbons of light drifting across. */
class XmbBackground(context: Context, private val opacity: Float = 1f) : View(context) {

    private val d = resources.displayMetrics.density
    private val bgPaint = Paint()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /**
     * The ribbons are drawn ONCE into images (with a real blur for the glow), then each frame
     * those images just slide sideways at different speeds. That's almost free for the GPU,
     * so it runs at the screen's full refresh rate.
     */
    private class Layer(val bmp: android.graphics.Bitmap, val speed: Float, val bobAmp: Float, val bobSpeed: Float, val phase: Float)

    private val layers = ArrayList<Layer>()
    private var bandTop = 0f
    private var bandHeight = 0f
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val src = android.graphics.Rect()
    private val dst = android.graphics.RectF()
    private val res = 0.75f // layers are rendered at 75% size and scaled up smoothly

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val a = (opacity * 255).toInt()
        bgPaint.shader = LinearGradient(
            0f, 0f, w * 0.3f, h.toFloat(),
            intArrayOf(Color.argb(a, 8, 22, 58), Color.argb(a, 22, 12, 52), Color.argb(a, 4, 6, 18)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        glowPaint.shader = RadialGradient(
            w * 0.5f, h * 0.45f, maxOf(w, h) * 0.6f,
            Color.argb((70 * opacity).toInt(), 90, 140, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
        buildLayers(w, h)
    }

    private fun buildLayers(w: Int, h: Int) {
        bandTop = h * 0.38f
        bandHeight = h * 0.55f
        // All menus share one set of wave images
        val key = "${w}x$h"
        if (sharedKey == key && sharedLayers.isNotEmpty()) {
            layers.clear(); layers.addAll(sharedLayers)
            return
        }
        for (l in sharedLayers) l.bmp.recycle()
        sharedLayers.clear()
        layers.clear()
        val bw = (w * res).toInt().coerceAtLeast(1)
        val bh = (bandHeight * res).toInt().coerceAtLeast(1)
        // Two layers drifting in opposite directions, so the ribbons keep weaving across each other
        val specs = listOf(
            Triple(intArrayOf(0, 2, 4), 26f * d, 1),
            Triple(intArrayOf(1, 3, 5), -38f * d, 2)
        )
        for ((ribbons, speed, cycles) in specs) {
            val bmp = try {
                android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) { continue }
            val c = Canvas(bmp)
            val k = res
            for (i in ribbons) {
                val amp = bandHeight * (0.09f + 0.035f * i) * k
                val mid = (h * (0.58f + 0.02f * i) - bandTop) * k
                val phase = i * 0.9f
                val path = android.graphics.Path()
                val step = 3f
                var x = 0f
                // Exactly whole waves across the width, so the image tiles seamlessly as it slides
                val f1 = 2f * PI.toFloat() * cycles / bw
                path.moveTo(0f, mid + sin(phase) * amp + sin(phase * 2f) * amp * 0.45f)
                while (x <= bw) {
                    x += step
                    val y = mid + sin(x * f1 + phase) * amp + sin(x * f1 * 2f + phase * 2f) * amp * 0.45f
                    path.lineTo(x, y)
                }
                val bright = i == 2 || i == 3
                if (bright || i == 4) {
                    val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = 14f * d * k
                        color = Color.argb(60, 160, 200, 255)
                        maskFilter = android.graphics.BlurMaskFilter(9f * d * k, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    c.drawPath(path, glow)
                }
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
                    strokeWidth = (if (bright) 2.2f else 1.2f) * d * k
                    color = Color.argb(if (bright) 175 else 80, 220, 235, 255)
                }
                c.drawPath(path, line)
            }
            layers.add(Layer(bmp, speed, bandHeight * 0.03f, 0.25f + layers.size * 0.12f, layers.size * 1.7f))
        }
        sharedLayers.clear(); sharedLayers.addAll(layers)
        sharedKey = key
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val t = SystemClock.uptimeMillis() / 1000f
        for (l in layers) {
            // Slide sideways (wrapping) and bob gently up and down
            var off = (t * l.speed) % w
            if (off < 0f) off += w
            val bob = sin(t * l.bobSpeed + l.phase) * l.bobAmp
            src.set(0, 0, l.bmp.width, l.bmp.height)
            dst.set(off - w, bandTop + bob, off, bandTop + bob + bandHeight)
            canvas.drawBitmap(l.bmp, src, dst, blit)
            dst.set(off, bandTop + bob, off + w, bandTop + bob + bandHeight)
            canvas.drawBitmap(l.bmp, src, dst, blit)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) postInvalidateOnAnimation()
    }

    companion object {
        private val sharedLayers = ArrayList<Layer>()
        private var sharedKey = ""

        /** Glassy panel background used across the UI. */
        fun glass(d: Float, radius: Float = 18f, strong: Boolean = false): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (strong) intArrayOf(Color.parseColor("#55FFFFFF"), Color.parseColor("#22FFFFFF"))
                else intArrayOf(Color.parseColor("#2AFFFFFF"), Color.parseColor("#10FFFFFF"))
            ).apply {
                cornerRadius = radius * d
                setStroke((1.2f * d).toInt().coerceAtLeast(1), Color.parseColor(if (strong) "#CCFFFFFF" else "#55FFFFFF"))
            }

        fun tinted(d: Float, color: Int, radius: Float = 18f): GradientDrawable =
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(70, Color.red(color), Color.green(color), Color.blue(color)))
            ).apply {
                cornerRadius = radius * d
                setStroke((1.5f * d).toInt().coerceAtLeast(1), Color.argb(230, Color.red(color), Color.green(color), Color.blue(color)))
            }
    }
}
