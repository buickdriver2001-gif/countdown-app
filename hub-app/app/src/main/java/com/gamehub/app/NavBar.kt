package com.gamehub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout

/**
 * Back, Home and Recent apps buttons along the bottom of NEXUS (NEXUS hides the phone's own bar for its
 * full-screen look). The order can be swapped like on most phones: NEXUS settings → Navigation buttons.
 */
class NavBar(c: Context, private val onBack: () -> Unit, private val onHome: () -> Unit, private val onRecent: () -> Unit) : LinearLayout(c) {

    companion object {
        const val HEIGHT_DP = 46f
        /** "brh" Back · Home · Recent, "rhb" Recent · Home · Back, or "off". */
        fun order(c: Context): String = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("navOrder", "brh") ?: "brh"
        fun setOrder(c: Context, o: String) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("navOrder", o).apply()
        fun name(o: String) = when (o) { "brh" -> "Back · Home · Recent"; "rhb" -> "Recent · Home · Back"; else -> "Off" }
        fun next(o: String) = when (o) { "brh" -> "rhb"; "rhb" -> "off"; else -> "brh" }
    }

    private val d = resources.displayMetrics.density
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        arrange(order(c))
    }

    /** Lays the three buttons out in [o]'s order. */
    fun arrange(o: String) {
        if (o == current) return
        current = o
        removeAllViews()
        val keys = if (o == "rhb") "rhb" else "brh"
        for (k in keys) addView(Button(context, k), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    override fun onDraw(c: Canvas) {
        // A faint glass strip with a fine glowing line on top, in the NEXUS style
        strip.color = Color.argb(120, 4, 8, 22)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), strip)
        strip.color = Color.argb(60, 140, 190, 255)
        c.drawRect(0f, 0f, width.toFloat(), 1f * d, strip)
    }

    /** One button: its symbol drawn in light blue, with a soft glow while pressed. */
    private inner class Button(c: Context, val key: Char) : View(c) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tri = Path()

        init {
            isClickable = true; isFocusable = true
            contentDescription = when (key) { 'b' -> "Back"; 'h' -> "Home"; else -> "Recent apps" }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (key) { 'b' -> onBack(); 'h' -> onHome(); else -> onRecent() }
            }
        }

        override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            if (isPressed) { glow.color = Color.argb(70, 90, 150, 255); c.drawCircle(cx, cy, 20 * d, glow) }
            line.color = Color.argb(if (isPressed) 255 else 200, 200, 222, 255)
            when (key) {
                'b' -> {
                    tri.reset()
                    tri.moveTo(cx + 6 * d, cy - 7 * d); tri.lineTo(cx - 6 * d, cy); tri.lineTo(cx + 6 * d, cy + 7 * d); tri.close()
                    c.drawPath(tri, line)
                }
                'h' -> c.drawCircle(cx, cy, 7.5f * d, line)
                else -> c.drawRoundRect(cx - 6.5f * d, cy - 6.5f * d, cx + 6.5f * d, cy + 6.5f * d, 2 * d, 2 * d, line)
            }
        }
    }
}
