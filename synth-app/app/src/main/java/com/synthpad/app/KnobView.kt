package com.synthpad.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A knob you turn by dragging up/down. Double-tap puts it back to its default.
 */
@SuppressLint("ViewConstructor")
class KnobView(
    context: Context,
    val param: P,
    initial: Float,
    private val onChange: (P, Float) -> Unit
) : View(context) {

    var value = initial
        private set

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND
        color = Theme.KNOB_TRACK
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5 * d; strokeCap = Paint.Cap.ROUND
        color = Theme.ACCENT
    }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.KNOB_BODY }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.TEXT }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT; textSize = 11 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Theme.TEXT_MUTED; textSize = 11 * d; textAlign = Paint.Align.CENTER
        letterSpacing = 0.05f
    }
    private val rect = RectF()

    fun setValue(v: Float) {
        value = v
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize((86 * d).toInt(), heightMeasureSpec)
        setMeasuredDimension((70 * d).toInt(), h)
    }

    override fun onDraw(canvas: Canvas) {
        val labelSpace = 18 * d
        val size = minOf(width.toFloat(), height - labelSpace) - 10 * d
        val cx = width / 2f
        val cy = 5 * d + size / 2f
        val r = size / 2f
        rect.set(cx - r, cy - r, cx + r, cy + r)

        canvas.drawArc(rect, 135f, 270f, false, track)
        if (param == P.PAN) {
            val sweep = (value - 0.5f) * 270f
            canvas.drawArc(rect, 270f, sweep, false, arc)
        } else {
            canvas.drawArc(rect, 135f, 270f * value, false, arc)
        }

        val inner = r - 8 * d
        canvas.drawCircle(cx, cy, inner, body)
        val angle = Math.toRadians((135.0 + 270.0 * value))
        val dx = cos(angle).toFloat() * (inner - 6 * d)
        val dy = sin(angle).toFloat() * (inner - 6 * d)
        canvas.drawCircle(cx + dx, cy + dy, 3 * d, dot)

        canvas.drawText(ParamMath.display(param, value), cx, cy + 4 * d, valueText)
        canvas.drawText(param.label.uppercase(), cx, height - 4 * d, labelText)
    }

    private var startY = 0f
    private var startValue = 0f
    private var lastUp = 0L
    private var moved = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                startY = e.y
                startValue = value
                moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = (startY - e.y) / (160 * d)
                if (abs(startY - e.y) > 4 * d) moved = true
                var v = (startValue + delta).coerceIn(0f, 1f)
                if (param.steps > 1) v = (v * (param.steps - 1)).roundToInt() / (param.steps - 1).toFloat()
                if (v != value) {
                    value = v
                    onChange(param, v)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val now = e.eventTime
                if (!moved && now - lastUp < 300) {
                    value = param.def
                    onChange(param, value)
                    invalidate()
                    lastUp = 0
                } else if (!moved) {
                    lastUp = now
                }
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
