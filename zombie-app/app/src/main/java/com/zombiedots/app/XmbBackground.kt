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
    }

    // Ribbon geometry, reused every frame (no allocations while animating)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var stripUp = FloatArray(0)
    private var stripDown = FloatArray(0)
    private var stripColors = IntArray(0)
    private val linePath = android.graphics.Path()
    private val vertPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val t = SystemClock.uptimeMillis() / 1000f
        val step = 4f * d
        val n = (w / step).toInt() + 3
        if (xs.size < n) {
            xs = FloatArray(n); ys = FloatArray(n)
            stripUp = FloatArray(n * 4); stripDown = FloatArray(n * 4); stripColors = IntArray(n * 2)
        }

        // Several thin ribbons weaving over each other, like the PS3 home screen.
        // Each one is a single smooth stroke, so there are no bumps where pieces overlap.
        for (i in 0 until 6) {
            val phase = i * 0.9f
            val speed = 0.18f + i * 0.04f
            val amp = h * (0.05f + 0.025f * i)
            val baseY = h * (0.58f + 0.02f * i)
            val freq = (1.2f + i * 0.15f) * PI.toFloat() / w
            for (j in 0 until n) {
                val x = -step + j * step
                xs[j] = x
                ys[j] = baseY + sin(x * freq + t * speed + phase) * amp +
                    sin(x * freq * 0.5f - t * speed * 0.7f + phase * 2f) * amp * 0.5f
            }

            // Soft glow under the brightest ribbons: a seamless band that fades at its edges
            if ((i == 2 || i == 4) && android.os.Build.VERSION.SDK_INT >= 29) {
                val half = 9f * d
                val glowA = (34 * opacity).toInt()
                for (j in 0 until n) {
                    val j0 = maxOf(0, j - 1); val j1 = minOf(n - 1, j + 1)
                    var tx = xs[j1] - xs[j0]; var ty = ys[j1] - ys[j0]
                    val len = kotlin.math.sqrt(tx * tx + ty * ty).coerceAtLeast(0.001f)
                    tx /= len; ty /= len
                    val nx = -ty * half; val ny = tx * half
                    stripUp[j * 4] = xs[j]; stripUp[j * 4 + 1] = ys[j]
                    stripUp[j * 4 + 2] = xs[j] + nx; stripUp[j * 4 + 3] = ys[j] + ny
                    stripDown[j * 4] = xs[j]; stripDown[j * 4 + 1] = ys[j]
                    stripDown[j * 4 + 2] = xs[j] - nx; stripDown[j * 4 + 3] = ys[j] - ny
                    stripColors[j * 2] = Color.argb(glowA, 170, 205, 255)
                    stripColors[j * 2 + 1] = Color.argb(0, 170, 205, 255)
                }
                canvas.drawVertices(Canvas.VertexMode.TRIANGLE_STRIP, n * 4, stripUp, 0, null, 0, stripColors, 0, null, 0, 0, vertPaint)
                canvas.drawVertices(Canvas.VertexMode.TRIANGLE_STRIP, n * 4, stripDown, 0, null, 0, stripColors, 0, null, 0, 0, vertPaint)
            }

            linePath.rewind()
            linePath.moveTo(xs[0], ys[0])
            for (j in 1 until n) linePath.lineTo(xs[j], ys[j])
            ribbonLine.strokeWidth = (if (i == 2) 2.2f else 1.1f) * d
            ribbonLine.color = Color.argb(((if (i == 2) 160 else 75) * opacity).toInt(), 220, 235, 255)
            canvas.drawPath(linePath, ribbonLine)
        }
        if (isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) postInvalidateOnAnimation()
    }

    companion object {
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
