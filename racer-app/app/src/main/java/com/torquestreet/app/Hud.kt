package com.torquestreet.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.torquestreet.app.core.Mode
import com.torquestreet.app.core.Race
import com.torquestreet.app.core.RaceState
import com.torquestreet.app.core.Track
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One frame's worth of what the HUD shows (filled by the game on its thread, read by the HUD). */
class HudFrame {
    var speed = 0f; var gear = 1; var rpm = 0f; var nitro = 0f; var nitroOn = false
    var pos = 1; var racers = 1; var lap = 0; var laps = 1; var time = 0f; var best = 0f
    var heat = 0; var heatProg = 0f; var pursuit = false; var busted = 0f; var escape = 0f
    var state = RaceState.COUNTDOWN; var countdown = 3f; var mode = Mode.RACE
    var topSpeed = 0f; var evaded = 0
    var style = ""
    val msgText = arrayOfNulls<String>(2); val msgSub = arrayOfNulls<String>(2); val msgT = FloatArray(2); val msgColor = IntArray(2); var msgN = 0
    val dots = FloatArray(40 * 3); var nDots = 0; var playerYaw = 0f
    var paused = false

    fun copyFrom(o: HudFrame) {
        speed = o.speed; gear = o.gear; rpm = o.rpm; nitro = o.nitro; nitroOn = o.nitroOn
        pos = o.pos; racers = o.racers; lap = o.lap; laps = o.laps; time = o.time; best = o.best
        heat = o.heat; heatProg = o.heatProg; pursuit = o.pursuit; busted = o.busted; escape = o.escape
        state = o.state; countdown = o.countdown; mode = o.mode; topSpeed = o.topSpeed; evaded = o.evaded; style = o.style
        msgN = o.msgN
        for (i in 0 until 2) { msgText[i] = o.msgText[i]; msgSub[i] = o.msgSub[i]; msgT[i] = o.msgT[i]; msgColor[i] = o.msgColor[i] }
        System.arraycopy(o.dots, 0, dots, 0, o.nDots * 3); nDots = o.nDots; playerYaw = o.playerYaw; paused = o.paused
    }
}

/** Hands HUD frames from the game thread to the screen safely. */
class HudState {
    private val shared = HudFrame()
    @Volatile var bump = 0f
    fun publish(fill: (HudFrame) -> Unit) = synchronized(this) { fill(shared) }
    fun read(into: HudFrame) = synchronized(this) { into.copyFrom(shared) }
}

/**
 * The HUD drawn over the race, and the on-screen controls (with tilt steering or steering buttons).
 * Multi-touch: steer with one thumb while the other works the pedals and nitro.
 */
@SuppressLint("ViewConstructor")
class HudView(c: Context, private val prefs: Prefs, private val hud: HudState, private val controls: Controls,
              private val track: Track, private val onPause: () -> Unit, private val onCamera: () -> Unit) : View(c) {
    private val d = resources.displayMetrics.density
    private val f = HudFrame()
    private val bold = Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC)
    private val reg = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val r = RectF()
    private val path = Path()

    /** Tilt steering, smoothed (set by the activity from the sensor). */
    @Volatile var tiltSteer = 0f

    // Touch controls
    private val left = RectF(); private val right = RectF(); private val gas = RectF(); private val brake = RectF(); private val nitroBtn = RectF()
    private val pauseBtn = RectF(); private val camBtn = RectF()
    private val pointers = HashMap<Int, Int>() // pointer id -> control (1 left, 2 right, 3 gas, 4 brake, 5 nitro)
    private var steerTouch = 0f

    init { isClickable = true }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w, h).toFloat()
        val b = s * 0.2f
        val m = 18 * d
        if (prefs.tilt) {
            // Tilt: brake on the left, gas on the right
            brake.set(m, h - m - b * 1.15f, m + b * 1.1f, h - m.toFloat())
            gas.set(w - m - b * 1.1f, h - m - b * 1.15f, w - m.toFloat(), h - m.toFloat())
            left.setEmpty(); right.setEmpty()
        } else {
            left.set(m, h - m - b, m + b, h - m.toFloat())
            right.set(m + b + 12 * d, h - m - b, m + 2 * b + 12 * d, h - m.toFloat())
            gas.set(w - m - b, h - m - b * 1.15f, w - m.toFloat(), h - m.toFloat())
            brake.set(w - m - 2 * b - 12 * d, h - m - b * 0.85f, w - m - b - 12 * d, h - m.toFloat())
        }
        val nb = b * 0.72f
        nitroBtn.set(gas.centerX() - nb / 2, gas.top - nb - 14 * d, gas.centerX() + nb / 2, gas.top - 14 * d)
        pauseBtn.set(w / 2f - 22 * d, 8 * d, w / 2f + 22 * d, 52 * d)
        camBtn.set(w / 2f + 30 * d, 8 * d, w / 2f + 74 * d, 52 * d)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i); val y = e.getY(i)
                if (pauseBtn.contains(x, y) || hypot(x - pauseBtn.centerX(), y - pauseBtn.centerY()) < 30 * d) { onPause(); return true }
                if (camBtn.contains(x, y)) { onCamera(); return true }
                pointers[e.getPointerId(i)] = hit(x, y)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                val cur = pointers[id] ?: continue
                // Slide a thumb between the steering buttons (and between gas and brake)
                val nh = hit(e.getX(i), e.getY(i))
                if (nh != 0 && nh != cur && !(cur == 5 || nh == 5)) pointers[id] = nh
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointers.remove(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> pointers.clear()
        }
        applyControls()
        return true
    }

    private fun hit(x: Float, y: Float): Int {
        fun near(rr: RectF, pad: Float) = x >= rr.left - pad && x <= rr.right + pad && y >= rr.top - pad && y <= rr.bottom + pad
        val pad = 14 * d
        return when {
            near(nitroBtn, pad * 0.5f) -> 5
            !prefs.tilt && near(left, pad) -> 1
            !prefs.tilt && near(right, pad) -> 2
            near(gas, pad) -> 3
            near(brake, pad) -> 4
            // Steering buttons: anywhere on the lower-left quarter steers by which half you press
            !prefs.tilt && x < width * 0.33f && y > height * 0.45f -> if (x < left.right + 6 * d) 1 else 2
            else -> 0
        }
    }

    private fun applyControls() {
        val v = pointers.values
        val l = 1 in v; val rr = 2 in v
        steerTouch = when { l && !rr -> -1f; rr && !l -> 1f; else -> 0f }
        val braking = 4 in v
        val gasHeld = 3 in v
        controls.brake = if (braking) 1f else 0f
        controls.gas = if (braking) 0f else if (gasHeld || prefs.autoGas) 1f else 0f
        controls.nitro = 5 in v
        if (!prefs.tilt) controls.steer = steerTouch
    }

    // ------------------------------------------------------------------ drawing

    private var lastNs = 0L
    private var bumpFlash = 0f

    override fun onDraw(cv: Canvas) {
        hud.read(f)
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.05f, (now - lastNs) / 1e9f); lastNs = now
        if (prefs.tilt) controls.steer = tiltSteer
        if (hud.bump > 0f) {
            bumpFlash = min(1f, hud.bump / 18f)
            if (prefs.vibrate) Haptics.bump(context, hud.bump)
            hud.bump = 0f
        }
        bumpFlash = max(0f, bumpFlash - dt * 2.5f)
        val w = width.toFloat(); val h = height.toFloat()
        // A red flash at the edges after a hard hit
        if (bumpFlash > 0.02f) {
            fill.shader = LinearGradient(0f, 0f, w * 0.18f, 0f, Color.argb((120 * bumpFlash).toInt(), 255, 40, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawRect(0f, 0f, w * 0.18f, h, fill)
            fill.shader = LinearGradient(w, 0f, w * 0.82f, 0f, Color.argb((120 * bumpFlash).toInt(), 255, 40, 20), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            cv.drawRect(w * 0.82f, 0f, w, h, fill); fill.shader = null
        }
        drawTopLeft(cv)
        drawMinimap(cv, w)
        drawSpeedo(cv, w, h)
        drawPursuit(cv, w)
        drawMessages(cv, w, h)
        drawControls(cv)
        // Pause and camera buttons
        fill.color = Color.argb(110, 0, 0, 0); cv.drawCircle(pauseBtn.centerX(), pauseBtn.centerY(), 20 * d, fill)
        fill.color = Color.WHITE
        cv.drawRect(pauseBtn.centerX() - 7 * d, pauseBtn.centerY() - 8 * d, pauseBtn.centerX() - 3 * d, pauseBtn.centerY() + 8 * d, fill)
        cv.drawRect(pauseBtn.centerX() + 3 * d, pauseBtn.centerY() - 8 * d, pauseBtn.centerX() + 7 * d, pauseBtn.centerY() + 8 * d, fill)
        fill.color = Color.argb(110, 0, 0, 0); cv.drawCircle(camBtn.centerX(), camBtn.centerY(), 20 * d, fill)
        line.color = Color.WHITE; line.strokeWidth = 2 * d
        r.set(camBtn.centerX() - 9 * d, camBtn.centerY() - 6 * d, camBtn.centerX() + 5 * d, camBtn.centerY() + 6 * d); cv.drawRoundRect(r, 2 * d, 2 * d, line)
        path.reset(); path.moveTo(camBtn.centerX() + 5 * d, camBtn.centerY() - 2 * d); path.lineTo(camBtn.centerX() + 10 * d, camBtn.centerY() - 5 * d)
        path.lineTo(camBtn.centerX() + 10 * d, camBtn.centerY() + 5 * d); path.lineTo(camBtn.centerX() + 5 * d, camBtn.centerY() + 2 * d); cv.drawPath(path, line)
        postInvalidateOnAnimation()
    }

    private fun shadowText(cv: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, align: Paint.Align = Paint.Align.LEFT, tf: Typeface = bold) {
        text.typeface = tf; text.textSize = size; text.textAlign = align
        text.color = Color.argb(150, 0, 0, 0); cv.drawText(s, x + 1.5f * d, y + 1.5f * d, text)
        text.color = color; cv.drawText(s, x, y, text)
    }

    private fun drawTopLeft(cv: Canvas) {
        val x = 18 * d
        if (f.mode == Mode.RACE) {
            shadowText(cv, "${f.pos}", x, 50 * d, 44 * d, Color.WHITE)
            text.textSize = 44 * d
            val pw = text.measureText("${f.pos}")
            shadowText(cv, "/${f.racers}", x + pw + 3 * d, 50 * d, 20 * d, Color.argb(200, 255, 255, 255))
            val lap = (f.lap + 1).coerceIn(1, f.laps)
            shadowText(cv, "LAP $lap/${f.laps}", x, 74 * d, 15 * d, Color.argb(230, 255, 210, 120), tf = reg)
            shadowText(cv, Race.fmtTime(f.time), x, 94 * d, 15 * d, Color.WHITE, tf = reg)
            if (f.best > 0f) shadowText(cv, "BEST " + Race.fmtTime(f.best), x, 112 * d, 12 * d, Color.argb(200, 150, 210, 255), tf = reg)
        } else {
            shadowText(cv, "PURSUIT", x, 36 * d, 20 * d, Color.argb(240, 120, 190, 255))
            shadowText(cv, Race.fmtTime(f.time), x, 58 * d, 16 * d, Color.WHITE, tf = reg)
        }
    }

    private var mapScale = 0f; private var mapCx = 0f; private var mapCz = 0f
    private val mapPath = Path()
    private fun drawMinimap(cv: Canvas, w: Float) {
        val size = 118 * d
        val ox = w - size - 14 * d; val oy = 12 * d
        if (mapScale == 0f) {
            val span = max(track.maxX - track.minX - 800f, track.maxZ - track.minZ - 800f)
            mapScale = (size - 16 * d) / span
            mapCx = (track.maxX + track.minX) / 2; mapCz = (track.maxZ + track.minZ) / 2
            mapPath.reset()
            for (k in 0 until track.n step 4) {
                val px = size / 2 + (track.cx[k] - mapCx) * mapScale; val py = size / 2 - (track.cz[k] - mapCz) * mapScale
                if (k == 0) mapPath.moveTo(px, py) else mapPath.lineTo(px, py)
            }
            mapPath.close()
        }
        fill.color = Color.argb(120, 0, 4, 12)
        r.set(ox, oy, ox + size, oy + size); cv.drawRoundRect(r, 12 * d, 12 * d, fill)
        cv.save(); cv.translate(ox, oy)
        line.color = Color.argb(220, 230, 235, 245); line.strokeWidth = 3 * d; cv.drawPath(mapPath, line)
        val flash = (System.nanoTime() / 150_000_000L) % 2 == 0L
        for (i in 0 until f.nDots) {
            val px = size / 2 + (f.dots[i * 3] - mapCx) * mapScale; val py = size / 2 - (f.dots[i * 3 + 1] - mapCz) * mapScale
            val type = f.dots[i * 3 + 2].toInt()
            fill.color = when (type) { 0 -> Color.WHITE; 1 -> Color.rgb(255, 150, 40); 2 -> if (flash) Color.rgb(255, 60, 60) else Color.rgb(70, 130, 255); else -> Color.rgb(120, 140, 170) }
            if (type == 0) {
                // You: an arrow pointing the way you're going
                val a = f.playerYaw
                val fx = sin(a); val fz = cos(a)
                path.reset()
                path.moveTo(px + fx * 7 * d, py - fz * 7 * d)
                path.lineTo(px - fx * 4 * d + fz * 4.5f * d, py + fz * 4 * d + fx * 4.5f * d)
                path.lineTo(px - fx * 4 * d - fz * 4.5f * d, py + fz * 4 * d - fx * 4.5f * d)
                path.close()
                fill.color = Color.BLACK; cv.save(); cv.translate(1 * d, 1 * d); cv.drawPath(path, fill); cv.restore()
                fill.color = Color.WHITE; cv.drawPath(path, fill)
            } else cv.drawCircle(px, py, 3.6f * d, fill)
        }
        cv.restore()
    }

    private fun drawSpeedo(cv: Canvas, w: Float, h: Float) {
        // Above the gas pedal on the right
        val cx = w - 100 * d; val cy = gas.top - 96 * d
        val rad = 58 * d
        // Rev arc
        fill.color = Color.argb(110, 0, 4, 12); cv.drawCircle(cx, cy, rad + 10 * d, fill)
        line.strokeWidth = 6 * d; line.color = Color.argb(70, 255, 255, 255)
        r.set(cx - rad, cy - rad, cx + rad, cy + rad); cv.drawArc(r, 140f, 260f, false, line)
        val rpm = f.rpm.coerceIn(0f, 1f)
        line.color = if (rpm > 0.88f) Color.rgb(255, 70, 50) else Color.rgb(255, 160, 40)
        cv.drawArc(r, 140f, 260f * rpm, false, line)
        val kmh = f.speed * 3.6f
        val shown = if (prefs.mph) kmh * 0.6214f else kmh
        shadowText(cv, "%d".format(shown.toInt()), cx, cy + 12 * d, 38 * d, Color.WHITE, Paint.Align.CENTER)
        shadowText(cv, if (prefs.mph) "MPH" else "KM/H", cx, cy + 30 * d, 11 * d, Color.argb(200, 220, 230, 255), Paint.Align.CENTER, reg)
        shadowText(cv, "${f.gear}", cx, cy - 22 * d, 16 * d, Color.argb(230, 255, 210, 120), Paint.Align.CENTER)
        // Nitro bar
        val bw = 110 * d; val bx = cx - bw / 2; val by = cy + rad + 16 * d
        fill.color = Color.argb(120, 0, 0, 0); r.set(bx, by, bx + bw, by + 8 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill)
        fill.shader = LinearGradient(bx, 0f, bx + bw, 0f, Color.rgb(40, 120, 255), Color.rgb(120, 230, 255), Shader.TileMode.CLAMP)
        r.set(bx, by, bx + bw * f.nitro.coerceIn(0f, 1f), by + 8 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill); fill.shader = null
        shadowText(cv, "NITRO", bx, by - 4 * d, 10 * d, Color.argb(200, 150, 210, 255), tf = reg)
        if (f.style.isNotEmpty()) shadowText(cv, f.style, cx, by + 26 * d, 14 * d, Color.rgb(120, 230, 255), Paint.Align.CENTER)
    }

    private fun drawPursuit(cv: Canvas, w: Float) {
        if (f.heat <= 0 && !f.pursuit) return
        val cx = w / 2; val y = 74 * d
        // Heat: five bars
        val bw = 18 * d; val gap = 5 * d
        val total = 5 * bw + 4 * gap
        for (i in 0 until 5) {
            val x = cx - total / 2 + i * (bw + gap)
            fill.color = if (i < f.heat) Color.rgb(255, 70 + i * 20, 40) else Color.argb(90, 255, 255, 255)
            r.set(x, y, x + bw, y + 7 * d); cv.drawRoundRect(r, 2 * d, 2 * d, fill)
        }
        shadowText(cv, "HEAT", cx - total / 2 - 8 * d, y + 7 * d, 11 * d, Color.argb(220, 255, 180, 140), Paint.Align.RIGHT, reg)
        if (!f.pursuit) return
        val flash = (System.nanoTime() / 250_000_000L) % 2 == 0L
        shadowText(cv, "PURSUIT", cx, y + 30 * d, 18 * d, if (flash) Color.rgb(255, 80, 70) else Color.rgb(90, 150, 255), Paint.Align.CENTER)
        // Busted (red) or escaping (green)
        val mw = 160 * d
        if (f.busted > 0.01f) {
            fill.color = Color.argb(120, 0, 0, 0); r.set(cx - mw / 2, y + 38 * d, cx + mw / 2, y + 46 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill)
            fill.color = Color.rgb(255, 60, 50); r.set(cx - mw / 2, y + 38 * d, cx - mw / 2 + mw * f.busted, y + 46 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill)
            shadowText(cv, "BUSTED", cx, y + 62 * d, 12 * d, Color.rgb(255, 120, 110), Paint.Align.CENTER, reg)
        } else if (f.escape > 0.01f) {
            fill.color = Color.argb(120, 0, 0, 0); r.set(cx - mw / 2, y + 38 * d, cx + mw / 2, y + 46 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill)
            fill.color = Color.rgb(80, 230, 130); r.set(cx - mw / 2, y + 38 * d, cx - mw / 2 + mw * f.escape, y + 46 * d); cv.drawRoundRect(r, 4 * d, 4 * d, fill)
            shadowText(cv, "ESCAPING", cx, y + 62 * d, 12 * d, Color.rgb(140, 240, 170), Paint.Align.CENTER, reg)
        }
    }

    private fun drawMessages(cv: Canvas, w: Float, h: Float) {
        if (f.state == RaceState.COUNTDOWN && f.countdown > 0.6f) {
            val n = kotlin.math.ceil(f.countdown - 0.6f).toInt().coerceIn(1, 3)
            val frac = (f.countdown - 0.6f) % 1f
            val size = (70 + 40 * frac) * d
            shadowText(cv, "$n", w / 2, h * 0.42f, size, Color.argb((255 * min(1f, frac * 3f + 0.3f)).toInt(), 255, 220, 120), Paint.Align.CENTER)
            return
        }
        var y = h * 0.34f
        for (i in 0 until f.msgN) {
            val t = f.msgT[i]
            val a = (min(1f, t / 0.4f) * 255).toInt().coerceIn(0, 255)
            val col = (f.msgColor[i] and 0x00FFFFFF) or (a shl 24)
            shadowText(cv, f.msgText[i] ?: "", w / 2, y, 34 * d, col, Paint.Align.CENTER)
            val sub = f.msgSub[i] ?: ""
            if (sub.isNotEmpty()) shadowText(cv, sub, w / 2, y + 24 * d, 15 * d, Color.argb(a, 230, 235, 245), Paint.Align.CENTER, reg)
            y += 62 * d
        }
    }

    private fun drawControls(cv: Canvas) {
        fun pad(rr: RectF, label: String, active: Boolean, accent: Int) {
            fill.color = if (active) Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)) else Color.argb(70, 0, 0, 0)
            cv.drawRoundRect(rr, 18 * d, 18 * d, fill)
            line.color = Color.argb(if (active) 230 else 110, 255, 255, 255); line.strokeWidth = 2 * d
            cv.drawRoundRect(rr, 18 * d, 18 * d, line)
            shadowText(cv, label, rr.centerX(), rr.centerY() + 7 * d, 18 * d, Color.argb(220, 255, 255, 255), Paint.Align.CENTER)
        }
        val v = pointers.values
        if (!prefs.tilt) {
            arrowPad(cv, left, -1, 1 in v); arrowPad(cv, right, 1, 2 in v)
        }
        if (!prefs.autoGas || prefs.tilt) pad(gas, "GAS", 3 in v || (prefs.autoGas && 4 !in v), Color.rgb(60, 200, 110))
        else pad(gas, "GAS", 4 !in v, Color.rgb(60, 200, 110))
        pad(brake, "BRAKE", 4 in v, Color.rgb(230, 60, 60))
        // Nitro: glows when it's ready
        val ready = f.nitro > 0.05f
        fill.color = if (5 in v && ready) Color.argb(200, 60, 160, 255) else if (ready) Color.argb(120, 30, 90, 200) else Color.argb(60, 0, 0, 0)
        cv.drawOval(nitroBtn, fill)
        line.color = Color.argb(if (ready) 230 else 90, 140, 210, 255); line.strokeWidth = 2 * d; cv.drawOval(nitroBtn, line)
        shadowText(cv, "N2O", nitroBtn.centerX(), nitroBtn.centerY() + 6 * d, 15 * d, Color.argb(if (ready) 255 else 120, 220, 240, 255), Paint.Align.CENTER)
        if (prefs.tilt) {
            // A small steering indicator
            val cx = width / 2f; val y = height - 26 * d
            line.color = Color.argb(90, 255, 255, 255); line.strokeWidth = 3 * d
            cv.drawLine(cx - 60 * d, y, cx + 60 * d, y, line)
            fill.color = Color.WHITE; cv.drawCircle(cx + tiltSteer * 60 * d, y, 6 * d, fill)
        }
    }

    private fun arrowPad(cv: Canvas, rr: RectF, dir: Int, active: Boolean) {
        fill.color = if (active) Color.argb(150, 255, 160, 40) else Color.argb(70, 0, 0, 0)
        cv.drawRoundRect(rr, 18 * d, 18 * d, fill)
        line.color = Color.argb(if (active) 230 else 110, 255, 255, 255); line.strokeWidth = 2 * d
        cv.drawRoundRect(rr, 18 * d, 18 * d, line)
        val cx = rr.centerX(); val cy = rr.centerY(); val s = rr.width() * 0.2f
        path.reset()
        path.moveTo(cx + dir * s, cy); path.lineTo(cx - dir * s * 0.7f, cy - s); path.lineTo(cx - dir * s * 0.7f, cy + s); path.close()
        fill.color = Color.argb(230, 255, 255, 255)
        cv.drawPath(path, fill)
    }
}
