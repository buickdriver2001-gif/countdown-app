package com.gamehub.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** One game in the hub. */
class GameEntry(val name: String, val pkg: String, val tagline: String, val accent: Int, val icon: Int) {
    var installed = false
}

/**
 * The hub screen: a dark void full of slowly turning towers of glowing glass cubes,
 * drifting motes of light, and a simple glowing menu of games.
 */
@SuppressLint("ViewConstructor")
class HubView(context: Context, val games: List<GameEntry>, private val onPick: (GameEntry) -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val rnd = Random(3)

    private class Tower(val x: Float, val z: Float, val cubes: Int, val size: Float, val phase: Float)
    private val towers = List(26) {
        val a = rnd.nextFloat() * 6.283f
        val r = 5.5f + rnd.nextFloat() * 11f
        Tower(sin(a) * r, cos(a) * r, 2 + rnd.nextInt(9), 0.7f + rnd.nextFloat() * 0.35f, rnd.nextFloat() * 6.283f)
    }
    private class Mote(var x: Float, var y: Float, var z: Float, val speed: Float)
    private val motes = List(70) { Mote((rnd.nextFloat() - 0.5f) * 30f, -4f + rnd.nextFloat() * 10f, (rnd.nextFloat() - 0.5f) * 30f, 0.15f + rnd.nextFloat() * 0.3f) }

    private var yaw = 0f
    private var t = 0f
    private var lastNs = 0L
    private var selected = -1
    private var launchFlash = 0f
    private var pending: GameEntry? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val path = Path()
    private val rect = RectF()
    private val clock = SimpleDateFormat("h:mm a", Locale.getDefault())

    private val rows = ArrayList<RectF>()

    // ------------------------------------------------------------------ 3D helpers

    private var px = 0f; private var py = 0f; private var pz = 0f
    private val camY = 0.6f

    /** World point to screen; returns false when behind the camera. */
    private fun project(x: Float, y: Float, z: Float): Boolean {
        val c = cos(yaw); val s = sin(yaw)
        val xr = x * c - z * s
        val zr = x * s + z * c
        if (zr < 0.6f) return false
        val f = width * 0.95f
        px = width / 2f + xr / zr * f
        py = height * 0.42f - (y - camY) / zr * f
        pz = zr
        return true
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        t += dt
        yaw += dt * 0.045f
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / 2.5f)

        // Deep blue-black void
        fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(2, 3, 10), Color.rgb(6, 12, 34), Color.rgb(1, 2, 6)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        // A soft glow on the horizon
        fill.shader = RadialGradient(w / 2, h * 0.45f, w * 0.9f, intArrayOf(Color.argb((70 * intro).toInt(), 40, 90, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        drawTowers(c, intro)
        drawMotes(c, dt, intro)
        drawMenu(c, intro)

        if (launchFlash > 0f) {
            launchFlash -= dt
            val k = (launchFlash / 0.45f).coerceIn(0f, 1f)
            fill.color = Color.argb(((1f - k) * 255).toInt().coerceIn(0, 255), 210, 230, 255)
            c.drawRect(0f, 0f, w, h, fill)
            if (launchFlash <= 0f) { pending?.let(onPick); pending = null }
        }
        // Fade in from black at start-up
        if (intro < 1f) { fill.color = Color.argb(((1f - intro) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, fill) }
        postInvalidateOnAnimation()
    }

    private val order = ArrayList<Int>()

    private fun drawTowers(c: Canvas, intro: Float) {
        // Draw far towers first
        order.clear(); order.addAll(towers.indices)
        val cy = cos(yaw); val sy = sin(yaw)
        order.sortByDescending { val tw = towers[it]; tw.x * sy + tw.z * cy }
        for (i in order) {
            val tw = towers[i]
            val bob = sin(t * 0.5f + tw.phase) * 0.15f
            val built = (intro * 1.4f * tw.cubes).toInt().coerceAtMost(tw.cubes)
            for (k in 0 until built) {
                val y0 = -3.2f + k * tw.size * 1.08f + bob
                drawCube(c, tw.x, y0, tw.z, tw.size, k, tw)
            }
            // Glow pooled at the tower's base
            if (project(tw.x, -3.3f, tw.z)) {
                val r = tw.size * width * 0.95f / pz * 1.6f
                val a = (60 * fogK(pz)).toInt()
                fill.shader = RadialGradient(px, py, max(1f, r), intArrayOf(Color.argb(a, 70, 130, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawCircle(px, py, max(1f, r), fill)
                fill.shader = null
            }
        }
    }

    private fun fogK(z: Float) = (1f - (z - 4f) / 16f).coerceIn(0.12f, 1f)

    private val cx = FloatArray(8); private val cyA = FloatArray(8); private val vis = BooleanArray(8)

    private fun drawCube(c: Canvas, x: Float, y: Float, z: Float, s: Float, k: Int, tw: Tower) {
        val h = s / 2
        var i = 0
        for (dy in 0..1) for (dz in 0..1) for (dx in 0..1) {
            vis[i] = project(x + (dx * 2 - 1) * h, y + dy * s, z + (dz * 2 - 1) * h)
            cx[i] = px; cyA[i] = py
            i++
        }
        if (!vis.all { it }) return
        project(x, y + h, z)
        val fog = fogK(pz)
        // Some cubes shimmer brighter now and then
        val twinkle = max(0f, sin(t * 1.3f + tw.phase * 3f + k * 1.7f)).let { it * it * it * it }
        val base = 26 + (40 * twinkle).toInt()
        // Faces: bottom(0,1,3,2) top(4,5,7,6) front(0,1,5,4) back(2,3,7,6) left(0,2,6,4) right(1,3,7,5)
        val faces = arrayOf(intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
        for ((fi, f) in faces.withIndex()) {
            path.reset()
            path.moveTo(cx[f[0]], cyA[f[0]])
            for (j in 1..3) path.lineTo(cx[f[j]], cyA[f[j]])
            path.close()
            val top = fi == 0
            fill.color = Color.argb(((if (top) base + 20 else base) * fog).toInt(), if (top) 120 else 60, if (top) 170 else 115, 255)
            c.drawPath(path, fill)
        }
        // Bright glassy edges
        line.strokeWidth = max(0.6f, 1.2f * d * fog)
        line.color = Color.argb(((70 + 120 * twinkle) * fog).toInt(), 150, 195, 255)
        val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        var e = 0
        while (e < edges.size) { c.drawLine(cx[edges[e]], cyA[edges[e]], cx[edges[e + 1]], cyA[edges[e + 1]], line); e += 2 }
    }

    private fun drawMotes(c: Canvas, dt: Float, intro: Float) {
        for (m in motes) {
            m.y += m.speed * dt
            if (m.y > 7f) { m.y = -4f }
            if (!project(m.x, m.y, m.z)) continue
            val a = (fogK(pz) * 200 * intro * (0.5f + 0.5f * sin(t * 2f + m.x))).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, 180, 215, 255)
            c.drawCircle(px, py, max(1f, 2.4f * d * 6f / pz), fill)
        }
    }

    private fun drawMenu(c: Canvas, intro: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = ((intro - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (a <= 0f) return
        // Title
        text.textAlign = Paint.Align.CENTER
        text.textSize = 34 * d
        text.letterSpacing = 0.35f
        text.setShadowLayer(14 * d, 0f, 0f, Color.argb((255 * a).toInt(), 70, 140, 255))
        text.color = Color.argb((255 * a).toInt(), 235, 242, 255)
        c.drawText("GAME HUB", w / 2, h * 0.14f, text)
        text.letterSpacing = 0.15f
        text.textSize = 12 * d
        text.color = Color.argb((170 * a).toInt(), 160, 190, 240)
        c.drawText("SELECT A GAME", w / 2, h * 0.14f + 26 * d, text)
        text.setShadowLayer(0f, 0f, 0f, 0)
        // Clock
        text.textAlign = Paint.Align.RIGHT
        text.textSize = 13 * d; text.letterSpacing = 0.05f
        text.color = Color.argb((190 * a).toInt(), 200, 215, 245)
        c.drawText(clock.format(Date()), w - 18 * d, 30 * d, text)

        // Game rows
        rows.clear()
        val rowH = 84 * d; val gap = 16 * d
        val left = 22 * d; val right = w - 22 * d
        var y = h * 0.52f
        for ((i, g) in games.withIndex()) {
            val r = RectF(left, y, right, y + rowH)
            rows.add(r)
            val sel = i == selected
            val pulse = 0.5f + 0.5f * sin(t * 3f)
            // Glassy panel
            fill.color = Color.argb(((if (sel) 70 else 34) * a).toInt(), 40, 80, 200)
            c.drawRoundRect(r, 10 * d, 10 * d, fill)
            line.strokeWidth = (if (sel) 2f else 1f) * d
            line.color = Color.argb(((if (sel) 160 + 80 * pulse else 70f).toInt() * a).toInt(), 140, 190, 255)
            c.drawRoundRect(r, 10 * d, 10 * d, line)
            if (sel) {
                fill.shader = LinearGradient(left, 0f, right, 0f, intArrayOf(Color.argb((80 * a).toInt(), 120, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(r, 10 * d, 10 * d, fill); fill.shader = null
            }
            // Icon
            val ic = RectF(left + 12 * d, y + 12 * d, left + 12 * d + rowH - 24 * d, y + rowH - 12 * d)
            drawIcon(c, g, ic, a)
            // Name and status
            text.textAlign = Paint.Align.LEFT
            text.textSize = 20 * d; text.letterSpacing = 0.08f
            text.color = Color.argb((255 * a).toInt(), 240, 245, 255)
            c.drawText(g.name, ic.right + 16 * d, y + rowH * 0.45f, text)
            text.textSize = 12 * d; text.letterSpacing = 0.04f
            text.color = if (g.installed) Color.argb((180 * a).toInt(), 170, 200, 240) else Color.argb((200 * a).toInt(), 255, 190, 110)
            c.drawText(if (g.installed) g.tagline else "Not installed · tap to download", ic.right + 16 * d, y + rowH * 0.72f, text)
            y += rowH + gap
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 11 * d
        text.color = Color.argb((110 * a).toInt(), 160, 185, 230)
        c.drawText("Tap a game to play", w / 2, h - 28 * d, text)
    }

    private fun drawIcon(c: Canvas, g: GameEntry, r: RectF, a: Float) {
        fill.color = Color.argb((255 * a).toInt(), 8, 12, 24)
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        line.strokeWidth = 1f * d
        line.color = Color.argb((120 * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        c.drawRoundRect(r, 8 * d, 8 * d, line)
        val cxm = r.centerX(); val cym = r.centerY(); val u = r.width() / 10f
        val al = (255 * a).toInt()
        when (g.icon) {
            0 -> { // Zombie Dots: a gold leader, blue squad, red horde
                fill.color = Color.argb(al, 255, 59, 59)
                for ((dx, dy) in listOf(-3.2f to -2.6f, 3f to -3f, -3.6f to 2.4f, 3.4f to 2.8f, 0.4f to 3.8f)) c.drawCircle(cxm + dx * u, cym + dy * u, 0.75f * u, fill)
                fill.color = Color.argb(al, 61, 165, 255)
                for ((dx, dy) in listOf(-1.4f to 0.2f, 1.4f to 0.2f, 0f to 1.5f)) c.drawCircle(cxm + dx * u, cym + dy * u, 0.75f * u, fill)
                fill.color = Color.argb(al, 255, 224, 102); c.drawCircle(cxm, cym - 0.4f * u, 0.95f * u, fill)
            }
            1 -> { // Ashen Vow: an upright sword with an ember glow
                fill.shader = RadialGradient(cxm, cym - 2f * u, 4f * u, intArrayOf(Color.argb((110 * a).toInt(), 255, 140, 50), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawCircle(cxm, cym - 2f * u, 4f * u, fill); fill.shader = null
                fill.color = Color.argb(al, 214, 220, 230)
                path.reset(); path.moveTo(cxm, cym - 4.2f * u); path.lineTo(cxm + 0.55f * u, cym - 3.3f * u); path.lineTo(cxm + 0.55f * u, cym + 1.6f * u)
                path.lineTo(cxm - 0.55f * u, cym + 1.6f * u); path.lineTo(cxm - 0.55f * u, cym - 3.3f * u); path.close(); c.drawPath(path, fill)
                fill.color = Color.argb(al, 184, 134, 46); c.drawRect(cxm - 2.2f * u, cym + 1.6f * u, cxm + 2.2f * u, cym + 2.3f * u, fill)
                fill.color = Color.argb(al, 90, 58, 30); c.drawRect(cxm - 0.4f * u, cym + 2.3f * u, cxm + 0.4f * u, cym + 4f * u, fill)
            }
            else -> { // SynthPad: keys under a glowing wave
                line.strokeWidth = 0.6f * u
                line.color = Color.argb(al, 120, 220, 255)
                path.reset()
                for (k in 0..30) {
                    val xx = r.left + u + k * (r.width() - 2 * u) / 30f
                    val yy = cym - 1.8f * u + sin(k / 30f * 12.56f) * 1.6f * u
                    if (k == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                }
                c.drawPath(path, line)
                fill.color = Color.argb(al, 230, 235, 245)
                val kw = (r.width() - 2 * u) / 6f
                for (k in 0 until 6) c.drawRect(r.left + u + k * kw + 0.08f * u, cym + 0.8f * u, r.left + u + (k + 1) * kw - 0.08f * u, cym + 4f * u, fill)
                fill.color = Color.argb(al, 20, 20, 28)
                for (k in intArrayOf(1, 2, 4, 5)) c.drawRect(r.left + u + k * kw - 0.32f * kw, cym + 0.8f * u, r.left + u + k * kw + 0.32f * kw, cym + 2.6f * u, fill)
            }
        }
    }

    // ------------------------------------------------------------------ touch

    private var downRow = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRow = rows.indexOfFirst { it.contains(e.x, e.y) }
                if (downRow >= 0) selected = downRow
            }
            MotionEvent.ACTION_UP -> {
                val r = rows.indexOfFirst { it.contains(e.x, e.y) }
                if (r >= 0 && r == downRow && launchFlash <= 0f && t > 1.5f) {
                    pending = games[r]
                    launchFlash = 0.45f
                    performClick()
                }
                downRow = -1
            }
            MotionEvent.ACTION_CANCEL -> downRow = -1
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    /** Called when the screen comes back (e.g. after closing a game). */
    fun resumed() { lastNs = 0L; launchFlash = 0f; pending = null }
}
