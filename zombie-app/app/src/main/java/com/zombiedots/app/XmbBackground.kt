package com.zombiedots.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
    private val ribbonFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbonLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
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

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val t = SystemClock.uptimeMillis() / 1000f
        // Several thin ribbons that weave over each other, like the PS3 home screen
        for (i in 0 until 6) {
            val phase = i * 0.9f
            val speed = 0.18f + i * 0.04f
            val amp = h * (0.05f + 0.025f * i)
            val baseY = h * (0.58f + 0.02f * i)
            val freq = (1.2f + i * 0.15f) * PI.toFloat() / w
            path.reset()
            var x = -10f
            var first = true
            while (x <= w + 10f) {
                val y = baseY + sin(x * freq + t * speed + phase) * amp +
                    sin(x * freq * 0.5f - t * speed * 0.7f + phase * 2f) * amp * 0.5f
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                x += 8f * d
            }
            ribbonLine.strokeWidth = (if (i == 2) 2.2f else 1.1f) * d
            ribbonLine.color = Color.argb(((if (i == 2) 150 else 70) * opacity).toInt(), 220, 235, 255)
            canvas.drawPath(path, ribbonLine)
            // A soft glow band under the brightest ribbon
            if (i == 2 || i == 4) {
                ribbonLine.strokeWidth = 18f * d
                ribbonLine.color = Color.argb((22 * opacity).toInt(), 160, 200, 255)
                canvas.drawPath(path, ribbonLine)
            }
        }
        postInvalidateOnAnimation()
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
