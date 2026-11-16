package com.synthpad.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Oscilloscope: draws the live output waveform, locked to a rising zero crossing so it stands still. */
class ScopeView(context: Context, private val engine: SynthEngine) : View(context) {

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.PANEL }
    private val grid = Paint().apply { color = Theme.KNOB_TRACK; strokeWidth = 1f }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 7 * d; color = Theme.ACCENT; alpha = 45
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.2f * d; color = Theme.ACCENT
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.ACCENT_DIM; textSize = 10 * d; letterSpacing = 0.15f
    }
    private val path = Path()
    private val rect = RectF()
    private val window = 700
    private val snapshot = FloatArray(engine.scope.size)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 14 * d, 14 * d, bg)

        for (i in 1..3) canvas.drawLine(0f, h * i / 4, w, h * i / 4, grid)
        for (i in 1..7) canvas.drawLine(w * i / 8, 0f, w * i / 8, h, grid)

        val buf = engine.scope
        val len = buf.size
        val pos = engine.scopePos
        // Copy oldest->newest
        for (i in 0 until len) snapshot[i] = buf[(pos + i) % len]

        // Find a rising zero crossing in the older part of the buffer
        var startIdx = len - window - 1
        var i = len - window - 1
        val limit = len - window - 1600
        while (i > limit && i > 1) {
            if (snapshot[i - 1] < 0f && snapshot[i] >= 0f) { startIdx = i; break }
            i--
        }

        var peak = 0.05f
        for (j in 0 until window) {
            val a = kotlin.math.abs(snapshot[startIdx + j])
            if (a > peak) peak = a
        }
        val gain = (0.42f / peak).coerceAtMost(6f)

        path.reset()
        val mid = h / 2f
        for (j in 0 until window) {
            val x = w * j / (window - 1)
            val y = mid - snapshot[startIdx + j] * gain * h
            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawPath(path, glow)
        canvas.drawPath(path, line)
        canvas.restore()

        canvas.drawText("OSCILLOSCOPE", 10 * d, 16 * d, label)
        postInvalidateOnAnimation()
    }
}
