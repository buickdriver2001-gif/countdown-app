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
 * The same buttons can also take the place of the phone's own while you use other apps
 * (NEXUS settings → NEXUS buttons in all apps; shown by the helper, see NexusAccessibility).
 */
class NavBar(c: Context, private val onBack: () -> Unit, private val onHome: () -> Unit, private val onRecent: () -> Unit) : LinearLayout(c) {

    companion object {
        const val HEIGHT_DP = 46f
        /** "brh" Back · Home · Recent, "rhb" Recent · Home · Back, or "off". */
        fun order(c: Context): String = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("navOrder", "brh") ?: "brh"
        fun setOrder(c: Context, o: String) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("navOrder", o).apply()
        fun name(o: String) = when (o) { "brh" -> "Back · Home · Recent"; "rhb" -> "Recent · Home · Back"; else -> "Off" }
        fun next(o: String) = when (o) { "brh" -> "rhb"; "rhb" -> "off"; else -> "brh" }

        /**
         * How tall the buttons are: exactly the phone's own button bar once the helper has measured it (so they
         * sit in the same place in NEXUS and in your apps), otherwise the standard 46dp.
         */
        fun heightPx(c: Context): Int {
            val d = c.resources.displayMetrics.density
            val saved = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getInt("navPx", 0)
            return if (saved >= (36 * d).toInt() && saved <= (80 * d).toInt()) saved else (HEIGHT_DP * d).toInt()
        }
        fun saveHeight(c: Context, px: Int) {
            val p = c.getSharedPreferences("hub", Context.MODE_PRIVATE)
            if (p.getInt("navPx", 0) != px) p.edit().putInt("navPx", px).apply()
        }

        private val sPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        private val sTri = Path()

        /**
         * Draws the buttons (as a picture) along the bottom of [c], exactly where the real ones sit: used on the
         * cover while Recent Apps opens, so the buttons stay put instead of disappearing for a moment.
         */
        fun drawPicture(c: Canvas, w: Float, h: Float, order: String, d: Float, bh: Float = HEIGHT_DP * d) {
            if (order == "off") return
            val top = h - bh
            sPaint.color = Color.argb(120, 4, 8, 22); c.drawRect(0f, top, w, h, sPaint)
            sPaint.color = Color.argb(60, 140, 190, 255); c.drawRect(0f, top, w, top + 1f * d, sPaint)
            sLine.strokeWidth = 1.8f * d; sLine.color = Color.argb(200, 200, 222, 255)
            val keys = if (order == "rhb") "rhb" else "bhr"
            for ((i, k) in keys.withIndex()) {
                val cx = w * (i * 2 + 1) / 6f; val cy = top + bh / 2
                glyph(c, k, cx, cy, d, sLine, sTri)
            }
        }

        fun glyph(c: Canvas, key: Char, cx: Float, cy: Float, d: Float, line: Paint, tri: Path) {
            when (key) {
                'b' -> {
                    tri.reset()
                    tri.moveTo(cx + 6 * d, cy - 7 * d); tri.lineTo(cx - 6 * d, cy); tri.lineTo(cx + 6 * d, cy + 7 * d); tri.close()
                    c.drawPath(tri, line)
                }
                'v' -> {
                    // Back while the keyboard is open: a down arrow (it closes the keyboard), as on the phone's own bar
                    tri.reset()
                    tri.moveTo(cx - 7 * d, cy - 3.5f * d); tri.lineTo(cx, cy + 3.5f * d); tri.lineTo(cx + 7 * d, cy - 3.5f * d)
                    c.drawPath(tri, line)
                }
                'h' -> c.drawCircle(cx, cy, 7.5f * d, line)
                else -> c.drawRoundRect(cx - 6.5f * d, cy - 6.5f * d, cx + 6.5f * d, cy + 6.5f * d, 2 * d, 2 * d, line)
            }
        }
    }

    private val d = resources.displayMetrics.density
    private val strip = Paint(Paint.ANTI_ALIAS_FLAG)
    private var current = ""
    /** Over other apps: a solid bar, so the phone's own buttons underneath don't show through. */
    var solid = false
        set(v) { if (field != v) { field = v; invalidate() } }
    /** The keyboard is open: Back shows as a down arrow. */
    var ime = false
        set(v) { if (field != v) { field = v; for (i in 0 until childCount) getChildAt(i).invalidate() } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        setWillNotDraw(false)
        arrange(order(c))
    }

    /** Lays the three buttons out in [o]'s order. */
    fun arrange(o: String) {
        if (o == current) return
        current = o
        removeAllViews()
        // Left to right (b = Back, h = Home, r = Recent apps)
        val keys = if (o == "rhb") "rhb" else "bhr"
        for (k in keys) addView(Button(context, k), LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
    }

    override fun onDraw(c: Canvas) {
        // A faint glass strip with a fine glowing line on top, in the NEXUS style
        strip.color = if (solid) Color.rgb(5, 9, 24) else Color.argb(120, 4, 8, 22)
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
            isSoundEffectsEnabled = false
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
            glyph(c, if (key == 'b' && ime) 'v' else key, cx, cy, d, line, tri)
        }
    }
}
