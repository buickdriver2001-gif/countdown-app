package com.torquestreet.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** The game's look for text and buttons (shared by the menus, pause and results). */
object Ui {
    val bold: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    val reg: Typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    const val ORANGE = 0xFFFF7A1A.toInt()
    const val GOLD = 0xFFFFC93C.toInt()
    const val BLUE = 0xFF5AB4FF.toInt()

    fun button(c: Canvas, r: RectF, label: String, d: Float, primary: Boolean, pressed: Boolean, p: Paint) {
        p.shader = null
        p.style = Paint.Style.FILL
        if (primary) {
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, if (pressed) 0xFFFF9A40.toInt() else ORANGE, if (pressed) 0xFFFF5020.toInt() else 0xFFE0401A.toInt(), Shader.TileMode.CLAMP)
        } else p.color = if (pressed) Color.argb(200, 60, 70, 95) else Color.argb(170, 22, 28, 42)
        c.drawRoundRect(r, 10 * d, 10 * d, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d
        p.color = if (primary) Color.argb(200, 255, 220, 170) else Color.argb(120, 160, 190, 255)
        c.drawRoundRect(r, 10 * d, 10 * d, p)
        p.style = Paint.Style.FILL
        p.typeface = bold; p.textSize = min(r.height() * 0.42f, 20 * d); p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + p.textSize * 0.36f, p)
    }
}

/** A panel over the race: a title, a few lines, and buttons (pause, results, errors). */
@SuppressLint("ViewConstructor")
class PanelView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var title = ""; var titleColor = Color.WHITE
    var lines: List<String> = emptyList()
    private var buttons: List<Pair<String, () -> Unit>> = emptyList()
    private val rects = ArrayList<RectF>()
    private var pressed = -1
    private var shownAt = 0L

    init { isClickable = true; visibility = GONE }

    fun show(title: String, color: Int, lines: List<String>, buttons: List<Pair<String, () -> Unit>>) {
        this.title = title; titleColor = color; this.lines = lines; this.buttons = buttons
        shownAt = System.currentTimeMillis()
        visibility = VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(220).start()
        invalidate()
    }
    fun hide() { visibility = GONE }
    val showing get() = visibility == VISIBLE

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.argb(190, 3, 5, 10))
        // Sized to fit: two columns of buttons when there isn't the height for one
        val titleSize = min(40 * d, h * 0.1f)
        val lineH = min(24 * d, h * 0.062f)
        val bh = min(46 * d, h * 0.115f)
        val gap = min(10 * d, h * 0.025f)
        val oneCol = titleSize + 16 * d + lines.size * lineH + 14 * d + buttons.size * (bh + gap)
        val cols = if (buttons.size > 2 && oneCol > h - 24 * d) 2 else 1
        val rows = (buttons.size + cols - 1) / cols
        val total = titleSize + 16 * d + lines.size * lineH + 14 * d + rows * (bh + gap) - gap
        var y = max(12 * d, (h - total) / 2) + titleSize * 0.85f
        p.typeface = Ui.bold; p.textAlign = Paint.Align.CENTER
        p.textSize = titleSize; p.color = titleColor
        c.drawText(title, w / 2, y, p)
        y += titleSize * 0.15f + 16 * d
        p.typeface = Ui.reg; p.textSize = lineH * 0.66f; p.color = Color.argb(230, 220, 228, 245)
        for (l in lines) { c.drawText(l, w / 2, y + lineH * 0.7f, p); y += lineH }
        y += 14 * d
        rects.clear()
        val bw = if (cols == 2) min(250 * d, w * 0.3f) else min(300 * d, w * 0.4f)
        val rowW = cols * bw + (cols - 1) * 12 * d
        for ((i, b) in buttons.withIndex()) {
            val col = i % cols; val row = i / cols
            val x = w / 2 - rowW / 2 + col * (bw + 12 * d)
            val top = y + row * (bh + gap)
            val r = RectF(x, top, x + bw, top + bh)
            rects.add(r)
            Ui.button(c, r, b.first, d, i == 0, i == pressed, p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // (A moment's grace after showing, so a tap meant for the race doesn't hit a button)
        if (System.currentTimeMillis() - shownAt < 350) return true
        val i = rects.indexOfFirst { it.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = i; invalidate() }
            MotionEvent.ACTION_MOVE -> if (i != pressed) { pressed = -1; invalidate() }
            MotionEvent.ACTION_UP -> { val hit = pressed; pressed = -1; invalidate(); if (hit >= 0 && hit == i) buttons[hit].second() }
            MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
        }
        return true
    }
}
