package com.synthpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.View

/** Multi-touch piano keyboard. Slide your finger across keys to glide between notes. */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onNoteOn: (Int) -> Unit,
    private val onNoteOff: (Int) -> Unit
) : View(context) {

    var baseNote = 48 // C3
        private set
    private val whiteCount = 15 // two octaves + top C
    private val whiteSemis = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackAfterWhite = booleanArrayOf(true, true, false, true, true, true, false)

    private val d = resources.displayMetrics.density
    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.WHITE_KEY }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.BLACK_KEY }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.ACCENT }
    private val bgPaint = Paint().apply { color = Theme.BG }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_MUTED; textSize = 11 * d; textAlign = Paint.Align.CENTER
    }

    private class Key(val note: Int, val black: Boolean, val rect: RectF)
    private val keys = ArrayList<Key>()
    private val pointerNotes = SparseIntArray()
    private val held = IntArray(128)

    fun setBase(note: Int) {
        releaseAll()
        baseNote = note
        layoutKeys()
        invalidate()
    }

    fun releaseAll() {
        for (i in 0 until pointerNotes.size()) onNoteOff(pointerNotes.valueAt(i))
        pointerNotes.clear()
        held.fill(0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        keys.clear()
        if (width == 0) return
        val gap = 2 * d
        val kw = width.toFloat() / whiteCount
        val h = height.toFloat()
        val blacks = ArrayList<Key>()
        for (i in 0 until whiteCount) {
            val oct = i / 7
            val idx = i % 7
            val note = baseNote + oct * 12 + whiteSemis[idx]
            keys.add(Key(note, false, RectF(i * kw + gap / 2, 0f, (i + 1) * kw - gap / 2, h)))
            if (blackAfterWhite[idx] && i < whiteCount - 1) {
                val bw = kw * 0.62f
                val cx = (i + 1) * kw
                blacks.add(Key(note + 1, true, RectF(cx - bw / 2, 0f, cx + bw / 2, h * 0.6f)))
            }
        }
        keys.addAll(blacks) // blacks drawn last, hit-tested first
    }

    private fun keyAt(x: Float, y: Float): Int {
        for (k in keys.asReversed()) if (k.black && k.rect.contains(x, y)) return k.note
        for (k in keys) if (!k.black && x >= k.rect.left - 2 * d && x <= k.rect.right + 2 * d) return k.note
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val r = 6 * d
        for (k in keys) {
            val paint = if (held[k.note] > 0) pressedPaint else if (k.black) blackPaint else whitePaint
            canvas.drawRoundRect(k.rect, r, r, paint)
            if (!k.black && (k.note % 12 == 0)) {
                canvas.drawText("C${k.note / 12 - 1}", k.rect.centerX(), k.rect.bottom - 10 * d, labelPaint)
            }
        }
    }

    private fun press(note: Int) {
        if (note < 0) return
        held[note]++
        if (held[note] == 1) onNoteOn(note)
    }

    private fun release(note: Int) {
        if (note < 0 || held[note] == 0) return
        held[note]--
        if (held[note] == 0) onNoteOff(note)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val note = keyAt(e.getX(i), e.getY(i))
                pointerNotes.put(e.getPointerId(i), note)
                press(note)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var changed = false
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val old = pointerNotes.get(id, -1)
                    val note = keyAt(e.getX(i), e.getY(i))
                    if (note != old) {
                        release(old)
                        press(note)
                        pointerNotes.put(id, note)
                        changed = true
                    }
                }
                if (changed) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                release(pointerNotes.get(id, -1))
                pointerNotes.delete(id)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }
}
