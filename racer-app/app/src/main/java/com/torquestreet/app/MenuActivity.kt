package com.torquestreet.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.torquestreet.app.core.CarShape
import com.torquestreet.app.core.Cars
import com.torquestreet.app.core.Mode
import com.torquestreet.app.core.Race
import com.torquestreet.app.core.TimeOfDay
import com.torquestreet.app.core.Track
import com.torquestreet.app.core.Weather
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The front end: pick a mode, a track, a car and its paint, the time of day and weather, then race. */
class MenuActivity : Activity() {
    private lateinit var view: MenuView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = MenuView(this, Prefs(this)) { startActivity(Intent(this, GameActivity::class.java)) }
        setContentView(view)
        LaunchFade.attach(this, "TORQUE STREET")
        immersive()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }
    override fun onResume() { super.onResume(); view.refresh() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (!view.back()) HubLink.let { if (it.installed(this)) it.open(this) else finish() } }
}

@SuppressLint("ViewConstructor")
class MenuView(c: Context, private val prefs: Prefs, private val onStart: () -> Unit) : View(c) {
    private enum class Screen { HOME, TRACK, CAR, CONDITIONS, SETTINGS }
    private var screen = Screen.HOME
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    private class Hit(val r: RectF, val action: () -> Unit)
    private val hits = ArrayList<Hit>()
    private var pressed: RectF? = null
    private var t = 0f
    private var lastNs = 0L
    private val tracks = (0..2).map { Track.build(it) }
    private val trackPaths = HashMap<Int, Path>()

    init { isClickable = true }

    fun refresh() = invalidate()

    /** Back: one screen up. False when already at the start. */
    fun back(): Boolean {
        screen = when (screen) {
            Screen.HOME -> return false
            Screen.TRACK, Screen.SETTINGS -> Screen.HOME
            Screen.CAR -> Screen.TRACK
            Screen.CONDITIONS -> Screen.CAR
        }
        invalidate(); return true
    }

    private fun go(s: Screen) { screen = s; invalidate() }

    override fun onDraw(cv: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else min(0.05f, (now - lastNs) / 1e9f); lastNs = now
        t += dt
        hits.clear()
        background(cv)
        when (screen) {
            Screen.HOME -> home(cv)
            Screen.TRACK -> trackScreen(cv)
            Screen.CAR -> carScreen(cv)
            Screen.CONDITIONS -> conditions(cv)
            Screen.SETTINGS -> settings(cv)
        }
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ look

    /** Night street rushing towards you: a glowing horizon, road lines and streaks of light. */
    private fun background(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF05070C.toInt(), 0xFF0C1022.toInt(), 0xFF1A0E14.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, p); p.shader = null
        val hy = h * 0.58f
        // City skyline
        p.color = 0xFF0A0D18.toInt()
        val rnd = java.util.Random(4)
        var x = 0f
        while (x < w) {
            val bw = (20 + rnd.nextInt(50)) * d; val bh = (20 + rnd.nextInt(130)) * d
            cv.drawRect(x, hy - bh, x + bw, hy, p)
            p.color = Color.argb(70, 255, 190, 110)
            var wy = hy - bh + 8 * d
            while (wy < hy - 6 * d) { var wx = x + 5 * d; while (wx < x + bw - 6 * d) { if (rnd.nextFloat() < 0.3f) cv.drawRect(wx, wy, wx + 3 * d, wy + 4 * d, p); wx += 8 * d }; wy += 10 * d }
            p.color = 0xFF0A0D18.toInt()
            x += bw + rnd.nextInt(6) * d
        }
        p.shader = LinearGradient(0f, hy - 60 * d, 0f, hy, Color.TRANSPARENT, Color.argb(90, 255, 110, 40), Shader.TileMode.CLAMP)
        cv.drawRect(0f, hy - 60 * d, w, hy, p); p.shader = null
        // The road, in perspective
        p.color = 0xFF07080D.toInt()
        path.reset(); path.moveTo(w * 0.47f, hy); path.lineTo(w * 0.53f, hy); path.lineTo(w * 1.1f, h); path.lineTo(-w * 0.1f, h); path.close()
        cv.drawPath(path, p)
        // Dashes rushing by
        for (k in 0 until 10) {
            val z = ((k / 10f + t * 0.9f) % 1f)
            val zz = z * z
            val y0 = hy + (h - hy) * zz; val y1 = hy + (h - hy) * ((z + 0.04f).let { it * it })
            p.color = Color.argb((200 * z).toInt(), 255, 200, 90)
            val half0 = 2 * d * (0.3f + zz * 3f)
            path.reset(); path.moveTo(w / 2 - half0, y0); path.lineTo(w / 2 + half0, y0); path.lineTo(w / 2 + half0 * 1.2f, min(h, y1)); path.lineTo(w / 2 - half0 * 1.2f, min(h, y1)); path.close()
            cv.drawPath(path, p)
        }
        // Light streaks
        for (k in 0 until 14) {
            val z = ((k * 0.137f + t * (0.25f + (k % 4) * 0.07f)) % 1f)
            val side = if (k % 2 == 0) -1f else 1f
            val xx = w / 2 + side * (w * 0.06f + z * z * w * 0.7f)
            val yy = hy + (h - hy) * z * z * 0.9f - (k % 3) * 8 * d * z
            p.color = if (k % 3 == 0) Color.argb((160 * z).toInt(), 255, 60, 50) else Color.argb((130 * z).toInt(), 120, 190, 255)
            p.strokeWidth = (1 + 3 * z) * d
            cv.drawLine(xx, yy, xx + side * 30 * d * z, yy + 6 * d * z, p)
        }
        p.color = Color.argb(110, 0, 0, 0); cv.drawRect(0f, 0f, w, h, p)
    }

    private fun title(cv: Canvas, s: String, x: Float, y: Float, size: Float) {
        p.typeface = Ui.bold; p.textSize = size; p.textAlign = Paint.Align.CENTER
        p.color = Color.argb(160, 0, 0, 0); cv.drawText(s, x + 3 * d, y + 3 * d, p)
        p.shader = LinearGradient(0f, y - size, 0f, y, intArrayOf(0xFFFFE08A.toInt(), Ui.ORANGE, 0xFFE0301A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawText(s, x, y, p); p.shader = null
    }

    private fun heading(cv: Canvas, s: String) {
        val w = width.toFloat()
        p.typeface = Ui.bold; p.textSize = 26 * d; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
        cv.drawText(s, 28 * d, 46 * d, p)
        p.color = Ui.ORANGE; cv.drawRect(28 * d, 54 * d, 28 * d + 60 * d, 57 * d, p)
        p.typeface = Ui.reg; p.textSize = 13 * d; p.textAlign = Paint.Align.RIGHT; p.color = Color.argb(170, 220, 228, 245)
        cv.drawText(prefs.mode.label.uppercase() + "  ·  " + Track.all[prefs.track].name + "  ·  " + Cars.player[prefs.car].name, w - 28 * d, 44 * d, p)
    }

    private fun button(cv: Canvas, rr: RectF, label: String, primary: Boolean, action: () -> Unit) {
        Ui.button(cv, rr, label, d, primary, pressed === rr || (pressed != null && pressed == rr), p)
        hits.add(Hit(RectF(rr), action))
    }

    private fun chip(cv: Canvas, rr: RectF, label: String, on: Boolean, action: () -> Unit) {
        p.style = Paint.Style.FILL
        p.color = if (on) Color.argb(220, 255, 122, 26) else Color.argb(150, 20, 26, 40)
        cv.drawRoundRect(rr, rr.height() / 2, rr.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = if (on) Color.argb(230, 255, 230, 190) else Color.argb(110, 160, 190, 255)
        cv.drawRoundRect(rr, rr.height() / 2, rr.height() / 2, p); p.style = Paint.Style.FILL
        p.typeface = Ui.bold; p.textSize = 15 * d; p.textAlign = Paint.Align.CENTER; p.color = Color.WHITE
        cv.drawText(label, rr.centerX(), rr.centerY() + 5 * d, p)
        hits.add(Hit(RectF(rr), action))
    }

    private fun card(cv: Canvas, rr: RectF, selected: Boolean) {
        p.style = Paint.Style.FILL
        p.color = if (selected) Color.argb(200, 30, 24, 30) else Color.argb(160, 14, 18, 28)
        cv.drawRoundRect(rr, 14 * d, 14 * d, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = (if (selected) 2.5f else 1.2f) * d
        p.color = if (selected) Ui.ORANGE else Color.argb(90, 160, 190, 255)
        cv.drawRoundRect(rr, 14 * d, 14 * d, p); p.style = Paint.Style.FILL
    }

    private fun wrap(cv: Canvas, s: String, x: Float, y: Float, maxW: Float, lh: Float, maxLines: Int = 3) {
        val words = s.split(" ")
        var line = ""; var yy = y; var n = 0
        for (wd in words) {
            val tryL = if (line.isEmpty()) wd else "$line $wd"
            if (p.measureText(tryL) > maxW && line.isNotEmpty()) { cv.drawText(line, x, yy, p); yy += lh; line = wd; if (++n >= maxLines - 1) break } else line = tryL
        }
        if (line.isNotEmpty()) cv.drawText(line, x, yy, p)
    }

    // ------------------------------------------------------------------ screens

    private fun home(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pulse = 1f + 0.015f * sin(t * 2.2f)
        title(cv, "TORQUE STREET", w / 2, h * 0.3f, 58 * d * pulse)
        p.typeface = Ui.reg; p.textSize = 15 * d; p.textAlign = Paint.Align.CENTER; p.color = Color.argb(210, 230, 235, 245)
        cv.drawText("Street racing  ·  Police chases  ·  Three tracks, any weather", w / 2, h * 0.3f + 30 * d, p)
        val bw = min(260 * d, w * 0.3f); val bh = 48 * d
        val x0 = w / 2 - bw - 8 * d; val x1 = w / 2 + 8 * d
        val y0 = h * 0.5f
        button(cv, RectF(x0, y0, x0 + bw, y0 + bh), "RACE", true) { prefs.mode = Mode.RACE; go(Screen.TRACK) }
        button(cv, RectF(x1, y0, x1 + bw, y0 + bh), "PURSUIT", true) { prefs.mode = Mode.PURSUIT; go(Screen.TRACK) }
        button(cv, RectF(x0, y0 + bh + 14 * d, x0 + bw, y0 + 2 * bh + 14 * d), "SETTINGS", false) { go(Screen.SETTINGS) }
        if (HubLink.installed(context as Activity)) button(cv, RectF(x1, y0 + bh + 14 * d, x1 + bw, y0 + 2 * bh + 14 * d), "RETURN TO NEXUS", false) { HubLink.open(context as Activity) }
        p.typeface = Ui.reg; p.textSize = 12 * d; p.color = Color.argb(140, 200, 210, 230)
        cv.drawText("Race: beat three rivals (and stay ahead of the police).   Pursuit: escape the police.", w / 2, h - 22 * d, p)
    }

    private fun trackScreen(cv: Canvas) {
        heading(cv, "CHOOSE A TRACK")
        val w = width.toFloat(); val h = height.toFloat()
        val gap = 16 * d
        val cw = (w - 56 * d - gap * 2) / 3; val ch = h - 170 * d
        for (i in 0..2) {
            val x = 28 * d + i * (cw + gap); val y = 74 * d
            val rr = RectF(x, y, x + cw, y + ch)
            val sel = prefs.track == i
            card(cv, rr, sel)
            hits.add(Hit(RectF(rr)) { prefs.track = i; invalidate() })
            // The track's shape
            val tp = trackPaths.getOrPut(i) { shapePath(tracks[i], cw - 40 * d, ch * 0.45f) }
            cv.save(); cv.translate(x + 20 * d, y + 16 * d)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; p.strokeJoin = Paint.Join.ROUND
            p.color = if (sel) Ui.ORANGE else Color.argb(200, 220, 228, 245)
            cv.drawPath(tp, p); p.style = Paint.Style.FILL
            cv.restore()
            val info = Track.all[i]
            p.typeface = Ui.bold; p.textSize = 20 * d; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
            cv.drawText(info.name.uppercase(), x + 16 * d, y + ch * 0.62f, p)
            p.typeface = Ui.reg; p.textSize = 13 * d; p.color = Color.argb(200, 215, 222, 240)
            wrap(cv, info.blurb, x + 16 * d, y + ch * 0.62f + 22 * d, cw - 32 * d, 17 * d)
            p.color = Color.argb(220, 255, 200, 120)
            val len = "%.1f km".format(tracks[i].length / 1000f)
            val best = prefs.best(i, prefs.mode)
            cv.drawText(if (prefs.mode == Mode.RACE) "$len  ·  ${info.laps} laps" else "$len loop", x + 16 * d, y + ch - 30 * d, p)
            if (best > 0f) { p.color = Color.argb(200, 150, 210, 255); cv.drawText("Record " + Race.fmtTime(best), x + 16 * d, y + ch - 12 * d, p) }
        }
        navButtons(cv, "NEXT") { go(Screen.CAR) }
    }

    private fun shapePath(tr: Track, w: Float, h: Float): Path {
        val pa = Path()
        val sx = w / (tr.maxX - tr.minX - 800f); val sz = h / (tr.maxZ - tr.minZ - 800f)
        val s = min(sx, sz)
        val cx = (tr.maxX + tr.minX) / 2; val cz = (tr.maxZ + tr.minZ) / 2
        for (k in 0 until tr.n step 3) {
            val x = w / 2 + (tr.cx[k] - cx) * s; val y = h / 2 - (tr.cz[k] - cz) * s
            if (k == 0) pa.moveTo(x, y) else pa.lineTo(x, y)
        }
        pa.close()
        return pa
    }

    private fun carScreen(cv: Canvas) {
        heading(cv, "CHOOSE YOUR CAR")
        val w = width.toFloat(); val h = height.toFloat()
        val gap = 16 * d
        val cw = (w - 56 * d - gap * 2) / 3; val ch = h - 170 * d
        for (i in 0..2) {
            val spec = Cars.player[i]
            val x = 28 * d + i * (cw + gap); val y = 74 * d
            val rr = RectF(x, y, x + cw, y + ch)
            val sel = prefs.car == i
            card(cv, rr, sel)
            hits.add(Hit(RectF(rr)) { if (prefs.car != i) { prefs.car = i; prefs.paint = 0 }; invalidate() })
            val paint = spec.paints[if (sel) prefs.paint.coerceIn(0, spec.paints.size - 1) else 0]
            drawCar(cv, spec.shape, x + cw / 2, y + ch * 0.3f, cw * 0.82f, paint)
            p.typeface = Ui.bold; p.textSize = 19 * d; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
            cv.drawText(spec.name.uppercase(), x + 16 * d, y + ch * 0.47f, p)
            p.typeface = Ui.reg; p.textSize = 12 * d; p.color = Color.argb(200, 215, 222, 240)
            wrap(cv, spec.blurb, x + 16 * d, y + ch * 0.47f + 18 * d, cw - 32 * d, 15 * d, 2)
            val stats = listOf("SPEED" to spec.speedRating, "ACCEL" to spec.accelRating, "HANDLING" to spec.handlingRating)
            var sy = y + ch * 0.47f + 56 * d
            for ((name, v) in stats) {
                p.textSize = 11 * d; p.color = Color.argb(190, 200, 210, 230); cv.drawText(name, x + 16 * d, sy, p)
                val bx = x + 86 * d; val bw = cw - 104 * d
                p.color = Color.argb(60, 255, 255, 255); r.set(bx, sy - 8 * d, bx + bw, sy - 2 * d); cv.drawRoundRect(r, 3 * d, 3 * d, p)
                p.color = if (sel) Ui.ORANGE else Color.argb(220, 200, 210, 230); r.set(bx, sy - 8 * d, bx + bw * v / 10f, sy - 2 * d); cv.drawRoundRect(r, 3 * d, 3 * d, p)
                sy += 18 * d
            }
            if (sel) {
                // Paint choices
                val n = spec.paints.size
                val sw = 22 * d
                var px = x + cw / 2 - (n * sw + (n - 1) * 8 * d) / 2
                val py = y + ch - 30 * d
                for ((k, col) in spec.paints.withIndex()) {
                    p.color = Color.rgb((col[0] * 255).toInt(), (col[1] * 255).toInt(), (col[2] * 255).toInt())
                    cv.drawCircle(px + sw / 2, py, sw / 2, p)
                    if (k == prefs.paint) { p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d; p.color = Color.WHITE; cv.drawCircle(px + sw / 2, py, sw / 2 + 4 * d, p); p.style = Paint.Style.FILL }
                    val kk = k
                    hits.add(Hit(RectF(px - 3 * d, py - sw, px + sw + 3 * d, py + sw)) { prefs.paint = kk; invalidate() })
                    px += sw + 8 * d
                }
            }
        }
        navButtons(cv, "NEXT") { go(Screen.CONDITIONS) }
    }

    /** A side view of a car from its proportions, in its paint. */
    private fun drawCar(cv: Canvas, s: CarShape, cx: Float, cy: Float, width: Float, col: FloatArray) {
        val k = width / s.length
        fun X(z: Float) = cx + z * k
        fun Y(y: Float) = cy + (0.7f - y) * k
        val hl = s.length / 2
        path.reset()
        path.moveTo(X(-hl), Y(s.clearance + 0.12f))
        path.lineTo(X(-hl), Y(s.belt - s.tailDrop - 0.05f))
        path.lineTo(X(-hl + 0.6f), Y(s.belt - s.tailDrop * 0.3f))
        path.lineTo(X(s.cab0), Y(s.belt))
        path.lineTo(X(s.cab1), Y(s.roof))
        path.lineTo(X(s.cab2), Y(s.roof))
        path.lineTo(X(s.cab3), Y(s.belt - 0.01f))
        path.lineTo(X(hl - 0.55f), Y(s.belt - s.noseDrop * 0.7f))
        path.lineTo(X(hl), Y(s.belt - s.noseDrop - 0.08f))
        path.lineTo(X(hl), Y(s.clearance + 0.1f))
        path.close()
        p.shader = LinearGradient(0f, Y(s.roof), 0f, Y(s.clearance), Color.rgb(min(255, (col[0] * 300).toInt()), min(255, (col[1] * 300).toInt()), min(255, (col[2] * 300).toInt())),
            Color.rgb((col[0] * 150).toInt(), (col[1] * 150).toInt(), (col[2] * 150).toInt()), Shader.TileMode.CLAMP)
        cv.drawPath(path, p); p.shader = null
        // Windows
        path.reset()
        path.moveTo(X(s.cab0 + 0.12f), Y(s.belt + 0.04f)); path.lineTo(X(s.cab1 + 0.05f), Y(s.roof - 0.05f))
        path.lineTo(X(s.cab2 - 0.03f), Y(s.roof - 0.05f)); path.lineTo(X(s.cab3 - 0.1f), Y(s.belt + 0.04f)); path.close()
        p.color = 0xFF0C1018.toInt(); cv.drawPath(path, p)
        // Wheels
        for (z in floatArrayOf(-s.wheelbase / 2, s.wheelbase / 2)) {
            p.color = 0xFF050608.toInt(); cv.drawCircle(X(z), Y(s.wheelR), s.wheelR * k, p)
            p.color = 0xFF8A909C.toInt(); cv.drawCircle(X(z), Y(s.wheelR), s.wheelR * k * 0.6f, p)
        }
        // Lights
        p.color = Color.rgb(255, 240, 200); cv.drawRect(X(hl - 0.12f), Y(s.belt - s.noseDrop - 0.02f), X(hl), Y(s.belt - s.noseDrop - 0.1f), p)
        p.color = Color.rgb(255, 40, 30); cv.drawRect(X(-hl), Y(s.belt - s.tailDrop - 0.06f), X(-hl + 0.1f), Y(s.belt - s.tailDrop - 0.16f), p)
    }

    private fun conditions(cv: Canvas) {
        heading(cv, "TIME AND WEATHER")
        val w = width.toFloat(); val h = height.toFloat()
        val cw = 130 * d; val chh = 42 * d
        p.typeface = Ui.bold; p.textSize = 16 * d; p.textAlign = Paint.Align.CENTER; p.color = Color.argb(220, 255, 200, 120)
        val y1 = h * 0.3f
        cv.drawText("TIME OF DAY", w / 2, y1, p)
        for ((i, tod) in TimeOfDay.values().withIndex()) {
            val x = w / 2 - (3 * cw + 2 * 14 * d) / 2 + i * (cw + 14 * d)
            chip(cv, RectF(x, y1 + 14 * d, x + cw, y1 + 14 * d + chh), tod.label.uppercase(), prefs.time == tod) { prefs.time = tod; invalidate() }
        }
        val y2 = y1 + 100 * d
        p.typeface = Ui.bold; p.textSize = 16 * d; p.textAlign = Paint.Align.CENTER; p.color = Color.argb(220, 255, 200, 120)
        cv.drawText("WEATHER", w / 2, y2, p)
        for ((i, wt) in Weather.values().withIndex()) {
            val x = w / 2 - (3 * cw + 2 * 14 * d) / 2 + i * (cw + 14 * d)
            chip(cv, RectF(x, y2 + 14 * d, x + cw, y2 + 14 * d + chh), wt.label.uppercase(), prefs.weather == wt) { prefs.weather = wt; invalidate() }
        }
        p.typeface = Ui.reg; p.textSize = 13 * d; p.color = Color.argb(190, 215, 222, 240)
        val note = when {
            prefs.weather == Weather.RAIN -> "Wet roads: less grip, longer braking, spray from every car."
            prefs.weather == Weather.FOG -> "Thick fog: you'll see the corners late."
            prefs.time == TimeOfDay.NIGHT -> "Street lights, headlights and lit windows."
            prefs.time == TimeOfDay.SUNSET -> "Low sun and long shadows, lights coming on."
            else -> "Clear and bright."
        }
        cv.drawText(note, w / 2, y2 + 86 * d, p)
        navButtons(cv, "START") { onStart() }
    }

    private fun settings(cv: Canvas) {
        heading(cv, "SETTINGS")
        val w = width.toFloat()
        val rows = listOf(
            "Steering" to (if (prefs.tilt) "Tilt the phone" else "Buttons") to { prefs.tilt = !prefs.tilt },
            "Tilt sensitivity" to (when { prefs.tiltSens < 0.9f -> "Low"; prefs.tiltSens > 1.1f -> "High"; else -> "Medium" }) to {
                prefs.tiltSens = when { prefs.tiltSens < 0.9f -> 1f; prefs.tiltSens > 1.1f -> 0.75f; else -> 1.3f } },
            "Reverse tilt" to (if (prefs.tiltInvert) "On" else "Off") to { prefs.tiltInvert = !prefs.tiltInvert },
            "Accelerate" to (if (prefs.autoGas) "Automatically" else "Hold the gas") to { prefs.autoGas = !prefs.autoGas },
            "Speed in" to (if (prefs.mph) "mph" else "km/h") to { prefs.mph = !prefs.mph },
            "Sound" to (when { prefs.sound < 0.05f -> "Off"; prefs.sound < 0.5f -> "Low"; prefs.sound < 0.85f -> "Medium"; else -> "High" }) to {
                prefs.sound = when { prefs.sound < 0.05f -> 0.4f; prefs.sound < 0.5f -> 0.75f; prefs.sound < 0.85f -> 1f; else -> 0f } },
            "Vibration" to (if (prefs.vibrate) "On" else "Off") to { prefs.vibrate = !prefs.vibrate },
            "Graphics" to (when (prefs.quality) { 0 -> "Smooth (fastest)"; 2 -> "Sharp"; else -> "Balanced" }) to { prefs.quality = (prefs.quality + 1) % 3 })
        val colW = (w - 56 * d - 16 * d) / 2
        val rh = 46 * d
        for ((i, row) in rows.withIndex()) {
            val col = i / 4; val ri = i % 4
            val x = 28 * d + col * (colW + 16 * d); val y = 78 * d + ri * (rh + 10 * d)
            val rr = RectF(x, y, x + colW, y + rh)
            card(cv, rr, false)
            p.typeface = Ui.reg; p.textSize = 15 * d; p.textAlign = Paint.Align.LEFT; p.color = Color.argb(220, 220, 228, 245)
            cv.drawText(row.first.first, x + 16 * d, y + rh / 2 + 5 * d, p)
            p.typeface = Ui.bold; p.textAlign = Paint.Align.RIGHT; p.color = Ui.ORANGE
            cv.drawText(row.first.second, x + colW - 16 * d, y + rh / 2 + 5 * d, p)
            hits.add(Hit(RectF(rr)) { row.second(); invalidate() })
        }
        p.typeface = Ui.reg; p.textSize = 12 * d; p.textAlign = Paint.Align.CENTER; p.color = Color.argb(150, 200, 210, 230)
        cv.drawText("Tilt: turn the phone like a steering wheel. If it steers the wrong way, turn on Reverse tilt.", w / 2, 78 * d + 4 * (rh + 10 * d) + 16 * d, p)
        navButtons(cv, null) {}
    }

    private fun navButtons(cv: Canvas, next: String?, onNext: () -> Unit) {
        val w = width.toFloat(); val h = height.toFloat()
        val bw = 170 * d; val bh = 46 * d
        button(cv, RectF(28 * d, h - bh - 20 * d, 28 * d + bw, h - 20 * d), "BACK", false) { back() }
        if (next != null) button(cv, RectF(w - 28 * d - bw, h - bh - 20 * d, w - 28 * d, h - 20 * d), next, true, onNext)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Last drawn on top: check from the end
        val hit = hits.lastOrNull { it.r.contains(e.x, e.y) }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = hit?.r; downHit = hit }
            MotionEvent.ACTION_MOVE -> if (hit?.r != downHit?.r) pressed = null
            MotionEvent.ACTION_UP -> { val h = downHit; pressed = null; downHit = null; if (h != null && hit != null && h.r == hit.r) hit.action() }
            MotionEvent.ACTION_CANCEL -> { pressed = null; downHit = null }
        }
        invalidate()
        return true
    }
    private var downHit: Hit? = null
}
