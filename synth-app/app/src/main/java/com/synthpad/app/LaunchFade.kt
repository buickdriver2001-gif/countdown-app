package com.synthpad.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup

/**
 * Covers the screen in NEXUS's deep blue while the app starts, then fades away once it's ready,
 * so opening an app from NEXUS is one smooth fade instead of a jump.
 */
object LaunchFade {
    const val BLUE = 0xFF02040E.toInt()

    fun attach(a: Activity, title: String, ready: () -> Boolean = { true }) {
        val root = a.window.decorView as? ViewGroup ?: return
        val d = a.resources.displayMetrics.density
        val start = SystemClock.uptimeMillis()
        val cover = object : View(a) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 200, 215, 245); textAlign = Paint.Align.CENTER
                textSize = 18 * d; letterSpacing = 0.4f; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            val dot = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                c.drawColor(BLUE)
                val t = (SystemClock.uptimeMillis() - start) / 1000f
                val a0 = (t / 0.5f).coerceIn(0f, 1f)
                p.alpha = (150 * a0).toInt()
                c.drawText(title, width / 2f, height / 2f, p)
                for (k in 0 until 3) {
                    val pulse = 0.3f + 0.7f * Math.max(0.0, Math.sin(t * 5.0 - k * 0.8)).toFloat()
                    dot.color = Color.argb((160 * pulse * a0).toInt(), 140, 190, 255)
                    c.drawCircle(width / 2f + (k - 1) * 14 * d, height / 2f + 26 * d, 3 * d, dot)
                }
                postInvalidateOnAnimation()
            }
        }.apply { isClickable = true }
        root.addView(cover, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val h = Handler(Looper.getMainLooper())
        val check = object : Runnable {
            override fun run() {
                val waited = SystemClock.uptimeMillis() - start
                if ((ready() && waited > 250) || waited > 6000) {
                    cover.animate().alpha(0f).setDuration(650).withEndAction { root.removeView(cover) }.start()
                } else h.postDelayed(this, 40)
            }
        }
        h.postDelayed(check, 40)
    }
}
