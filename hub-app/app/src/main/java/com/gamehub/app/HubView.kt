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
class GameEntry(val name: String, val pkg: String, val tagline: String, val accent: Int, val icon: Int,
                val custom: Boolean = false, val drawable: android.graphics.drawable.Drawable? = null) {
    var installed = false
}

/**
 * The hub screen: a dark void full of slowly turning towers of glowing glass cubes,
 * drifting motes of light, and a simple glowing menu of games.
 */
@SuppressLint("ViewConstructor")
class HubView(context: Context, var games: List<GameEntry>, private val onPick: (GameEntry) -> Unit,
              private val onAdd: () -> Unit, private val onRemove: (GameEntry) -> Unit, private val sfx: Sfx,
              private val onSettings: () -> Unit, private val onReorder: (Int, Int) -> Unit,
              private val onDrawer: () -> Unit, private val onRecent: () -> Unit) : View(context) {

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
    /** Start-up animation clock (the hub appears when it finishes). */
    private var bootT = 0f
    var booting = true
    private val BOOT_LEN = 5.2f
    private class Spark(val a: Float, val r0: Float, val speed: Float, val size: Float)
    private val bootSparks = List(140) { Spark(rnd.nextFloat() * 6.283f, 0.6f + rnd.nextFloat() * 0.8f, 0.6f + rnd.nextFloat() * 0.8f, 0.6f + rnd.nextFloat() * 1.6f) }
    private var lastNs = 0L
    private var selected = -1
    private var launchFlash = 0f
    private var pending: GameEntry? = null
    /** Fade up from black when coming back from an app. */
    private var enterFade = 0f

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

    /** True while something covers NEXUS (the app drawer): stop animating to save battery. */
    var covered = false
        set(v) { field = v; if (!v) { lastNs = 0L; invalidate() } }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        if (booting) {
            bootT += dt
            drawBoot(c)
            if (bootT >= BOOT_LEN) { booting = false; t = 0f }
            postInvalidateOnAnimation()
            return
        }
        t += dt
        yaw += dt * 0.045f
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / 2.5f)

        // Deep blue-black void with a soft horizon glow (drawn once, then reused)
        c.drawBitmap(backdrop(), 0f, 0f, null)

        drawGrid(c, intro)
        drawOrbit(c, intro)
        drawTowers(c, intro)
        drawMotes(c, dt, intro)
        drawStreaks(c, dt, intro)
        drawMenu(c, intro, dt)

        if (launchFlash > 0f) {
            launchFlash -= dt
            val k = 1f - (launchFlash / LAUNCH_LEN).coerceIn(0f, 1f)
            // A soft glow blooms out of the chosen row, then everything sinks into deep blue-black
            val row = rows.getOrNull(selected)
            if (row != null) {
                val rad = max(1f, w * (0.2f + 1.4f * k))
                fill.shader = RadialGradient(row.centerX(), row.centerY(), rad, intArrayOf(Color.argb((200 * (1f - k * 0.6f)).toInt(), 170, 210, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, fill); fill.shader = null
            }
            fill.color = Color.argb((255 * (k * k)).toInt().coerceIn(0, 255), 2, 4, 14)
            c.drawRect(0f, 0f, w, h, fill)
            if (launchFlash <= 0f) { pending?.let(onPick); pending = null }
        }
        if (enterFade > 0f) {
            enterFade -= dt
            fill.color = Color.argb((255 * (enterFade / 0.7f).coerceIn(0f, 1f)).toInt(), 2, 4, 14)
            c.drawRect(0f, 0f, w, h, fill)
        }
        // Fade in from black at start-up
        if (intro < 1f) { fill.color = Color.argb(((1f - intro) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, fill) }
        if (!covered) postInvalidateOnAnimation()
    }

    /**
     * Start-up: sparks of light spiral in out of the dark, gather into a turning glass cube,
     * a burst of light, then the name, then the hub fades up.
     */
    private fun drawBoot(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bt = bootT
        val cx0 = w / 2; val cy0 = h * 0.45f
        c.drawColor(Color.BLACK)
        val unit = min(w, h)
        // 1) Sparks spiral inward
        if (bt < 2.6f) {
            for (sp in bootSparks) {
                val k = ((bt * sp.speed) / 2.2f).coerceIn(0f, 1f)
                val r = sp.r0 * unit * (1f - k * k)
                val ang = sp.a + k * 3.5f
                val a = (min(1f, bt / 0.6f) * (1f - k * 0.3f) * 230).toInt()
                fill.color = Color.argb(a, 150, 195, 255)
                c.drawCircle(cx0 + cos(ang) * r, cy0 + sin(ang) * r * 0.75f, sp.size * d, fill)
            }
        }
        // 2) The glass cube forms and turns
        if (bt in 1.6f..4.2f) {
            val k = ((bt - 1.6f) / 0.9f).coerceIn(0f, 1f)
            val fade = 1f - ((bt - 3.6f) / 0.6f).coerceIn(0f, 1f)
            val size = unit * 0.12f * (0.4f + 0.6f * k)
            fill.shader = RadialGradient(cx0, cy0, size * 3f, intArrayOf(Color.argb((120 * k * fade).toInt(), 70, 130, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx0, cy0, size * 3f, fill); fill.shader = null
            bootCube(c, cx0, cy0, size, bt * 1.4f, (k * fade))
            // Light rays
            line.strokeWidth = 1.5f * d
            for (i in 0 until 12) {
                val a = i / 12f * 6.283f + bt * 0.4f
                line.color = Color.argb((60 * k * fade).toInt(), 160, 200, 255)
                c.drawLine(cx0 + cos(a) * size * 1.4f, cy0 + sin(a) * size * 1.4f, cx0 + cos(a) * size * (3f + 2f * k), cy0 + sin(a) * size * (3f + 2f * k), line)
            }
        }
        // 3) A burst of light
        if (bt in 3.3f..4.3f) {
            val k = (bt - 3.3f) / 1f
            val a = (sin(k * Math.PI).toFloat() * 255).toInt().coerceIn(0, 255)
            fill.shader = RadialGradient(cx0, cy0, unit * (0.2f + k * 1.2f), intArrayOf(Color.argb(a, 230, 240, 255), Color.argb(a / 3, 120, 170, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        }
        // 4) The name
        if (bt > 3.7f) {
            val k = ((bt - 3.7f) / 0.8f).coerceIn(0f, 1f)
            val out = ((bt - 4.6f) / 0.6f).coerceIn(0f, 1f)
            val a = (255 * k * (1f - out)).toInt()
            text.textAlign = Paint.Align.CENTER
            text.textSize = 36 * d
            text.letterSpacing = 0.9f - 0.55f * k
            text.setShadowLayer(18 * d, 0f, 0f, Color.argb(a, 80, 150, 255))
            text.color = Color.argb(a, 240, 246, 255)
            c.drawText("NEXUS", cx0, cy0 + 12 * d, text)
            text.setShadowLayer(0f, 0f, 0f, 0)
            text.letterSpacing = 0.15f
        }
    }

    private val bx = FloatArray(8); private val by = FloatArray(8)

    private fun bootCube(c: Canvas, cx0: Float, cy0: Float, s: Float, ang: Float, alpha: Float) {
        val ca = cos(ang); val sa = sin(ang); val cb = cos(0.5f); val sb = sin(0.5f)
        var i = 0
        for (yy in 0..1) for (zz in 0..1) for (xx in 0..1) {
            val x = (xx * 2 - 1).toFloat(); val y = (yy * 2 - 1).toFloat(); val z = (zz * 2 - 1).toFloat()
            val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
            val y2 = y * cb - z1 * sb; val z2 = y * sb + z1 * cb
            val persp = 3.2f / (3.2f + z2)
            bx[i] = cx0 + x1 * s * persp; by[i] = cy0 - y2 * s * persp
            i++
        }
        val faces = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
        for (f in faces) {
            path.reset(); path.moveTo(bx[f[0]], by[f[0]])
            for (j in 1..3) path.lineTo(bx[f[j]], by[f[j]])
            path.close()
            fill.color = Color.argb((40 * alpha).toInt(), 90, 150, 255)
            c.drawPath(path, fill)
        }
        line.strokeWidth = 1.8f * d
        line.color = Color.argb((230 * alpha).toInt(), 170, 210, 255)
        val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        var e = 0
        while (e < edges.size) { c.drawLine(bx[edges[e]], by[edges[e]], bx[edges[e + 1]], by[edges[e + 1]], line); e += 2 }
    }

    private var bgBmp: android.graphics.Bitmap? = null

    private fun backdrop(): android.graphics.Bitmap {
        val cur = bgBmp
        if (cur != null && cur.width == width && cur.height == height) return cur
        cur?.recycle()
        val w = width.toFloat(); val h = height.toFloat()
        val b = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(2, 3, 10), Color.rgb(6, 12, 34), Color.rgb(1, 2, 6)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w / 2, h * 0.45f, w * 0.9f, intArrayOf(Color.argb(70, 40, 90, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        bgBmp = b
        return b
    }

    private val order = ArrayList<Int>()

    // Batched drawing buffers (reused every frame, so nothing is created while animating)
    private var vtx = FloatArray(6 * 2 * 5 * 12)
    private var vcol = IntArray(6 * 5 * 12 * 2)
    private var vn = 0
    private val lineBuf = Array(3) { FloatArray(4 * 12 * 12) }
    private val lineN = IntArray(3)
    private val useVertices = android.os.Build.VERSION.SDK_INT >= 29

    /** A soft round glow, drawn once and reused (scaled) for every glow on screen. */
    private val glowBmp: android.graphics.Bitmap by lazy {
        val s = 128
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Color.argb(255, 120, 170, 255), Color.argb(90, 80, 140, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        b
    }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowDst = RectF()

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        if (alpha <= 0 || r < 1f) return
        glowPaint.alpha = alpha.coerceIn(0, 255)
        glowDst.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(glowBmp, null, glowDst, glowPaint)
    }

    private fun drawTowers(c: Canvas, intro: Float) {
        // Draw far towers first
        order.clear(); order.addAll(towers.indices)
        val cy = cos(yaw); val sy = sin(yaw)
        order.sortByDescending { val tw = towers[it]; tw.x * sy + tw.z * cy }
        for (i in order) {
            val tw = towers[i]
            // Glow pooled at the tower's base
            if (project(tw.x, -3.3f, tw.z)) glow(c, px, py, tw.size * width * 0.95f / pz * 1.6f, (90 * fogK(pz)).toInt())
            val bob = sin(t * 0.5f + tw.phase) * 0.15f
            val built = (intro * 1.4f * tw.cubes).toInt().coerceAtMost(tw.cubes)
            vn = 0; lineN.fill(0)
            var fogT = 1f
            for (k in 0 until built) {
                val y0 = -3.2f + k * tw.size * 1.08f + bob
                fogT = addCube(tw.x, y0, tw.z, tw.size, k, tw)
            }
            flushTower(c, fogT)
        }
    }

    private fun flushTower(c: Canvas, fog: Float) {
        if (vn > 0) {
            if (useVertices) {
                c.drawVertices(Canvas.VertexMode.TRIANGLES, vn * 2, vtx, 0, null, 0, vcol, 0, null, 0, 0, fill.apply { color = Color.WHITE; shader = null })
            } else {
                // Older Android: draw the triangles as small paths
                var v = 0
                while (v < vn) {
                    path.reset(); path.moveTo(vtx[v * 2], vtx[v * 2 + 1]); path.lineTo(vtx[v * 2 + 2], vtx[v * 2 + 3]); path.lineTo(vtx[v * 2 + 4], vtx[v * 2 + 5]); path.close()
                    fill.color = vcol[v]; c.drawPath(path, fill); v += 3
                }
            }
        }
        line.strokeWidth = max(0.6f, 1.2f * d * fog)
        for (b in 0 until 3) {
            if (lineN[b] == 0) continue
            line.color = Color.argb(((70 + 60 * b) * fog).toInt(), 150 + 30 * b, 195 + 20 * b, 255)
            c.drawLines(lineBuf[b], 0, lineN[b], line)
        }
    }

    private fun fogK(z: Float) = (1f - (z - 4f) / 16f).coerceIn(0.12f, 1f)

    private val cx = FloatArray(8); private val cyA = FloatArray(8)
    private val faces = arrayOf(intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
    private val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)

    /** Adds one glass cube's faces and edges to the batch. Returns its fog amount. */
    private fun addCube(x: Float, y: Float, z: Float, s: Float, k: Int, tw: Tower): Float {
        val h = s / 2
        var i = 0
        for (dy in 0..1) for (dz in 0..1) for (dx in 0..1) {
            if (!project(x + (dx * 2 - 1) * h, y + dy * s, z + (dz * 2 - 1) * h)) return 1f
            cx[i] = px; cyA[i] = py
            i++
        }
        project(x, y + h, z)
        val fog = fogK(pz)
        // Some cubes shimmer brighter now and then
        val twinkle0 = max(0f, sin(t * 1.3f + tw.phase * 3f + k * 1.7f)).let { it * it * it * it }
        // Every few seconds a band of white light passes across all the towers
        val pass = (t % PASS_EVERY) / PASS_LEN
        val bandX = -0.3f * width + pass * width * 1.6f
        val sweep = if (pass <= 1f) kotlin.math.exp(-((px - bandX) / (70 * d)).let { it * it }) else 0f
        val twinkle = min(1f, twinkle0 + sweep * 1.2f)
        val base = 26 + (40 * twinkle).toInt()
        if (vtx.size < (vn + 30) * 2) { vtx = vtx.copyOf(vtx.size * 2); vcol = vcol.copyOf(vcol.size * 2) }
        for ((fi, f) in faces.withIndex()) {
            val top = fi == 0
            val col = Color.argb(((if (top) base + 20 else base) * fog).toInt(), if (top) 120 else 60, if (top) 170 else 115, 255)
            for (j in intArrayOf(0, 1, 2, 0, 2, 3)) {
                vtx[vn * 2] = cx[f[j]]; vtx[vn * 2 + 1] = cyA[f[j]]; vcol[vn] = col; vn++
            }
        }
        // Edges go into one of three brightness groups
        val b = (twinkle * 2.99f).toInt().coerceIn(0, 2)
        var buf = lineBuf[b]
        if (buf.size < lineN[b] + 48) { buf = buf.copyOf(buf.size * 2); lineBuf[b] = buf }
        var e = 0
        while (e < edges.size) {
            buf[lineN[b]++] = cx[edges[e]]; buf[lineN[b]++] = cyA[edges[e]]
            buf[lineN[b]++] = cx[edges[e + 1]]; buf[lineN[b]++] = cyA[edges[e + 1]]
            e += 2
        }
        return fog
    }

    private val PASS_EVERY = 6.5f
    private val PASS_LEN = 2.2f

    /** A faint grid far below, with a scan ring pulsing out from the middle. */
    private val gridBuf = Array(4) { FloatArray(1024) }
    private val gridN = IntArray(4)

    private fun drawGrid(c: Canvas, intro: Float) {
        val gy = -3.6f
        val span = 22f
        gridN.fill(0)
        var gx = -span
        while (gx <= span) {
            gridLine(gx, gy, -span, gx, gy, span)
            gridLine(-span, gy, gx, span, gy, gx)
            gx += 2.75f
        }
        // Nearer lines are brighter: four brightness groups, one draw call each
        line.strokeWidth = 1f * d
        for (b in 0 until 4) {
            if (gridN[b] == 0) continue
            line.color = Color.argb(((8 + b * 8) * intro).toInt(), 110, 160, 255)
            c.drawLines(gridBuf[b], 0, gridN[b], line)
        }
        // Scan ring
        val k = (t % 5f) / 5f
        val rr = 1f + k * 21f
        val a = ((1f - k) * 120 * intro).toInt()
        line.color = Color.argb(a, 120, 180, 255); line.strokeWidth = 1.4f * d
        var prevOk = false; var lx = 0f; var ly = 0f
        for (i in 0..56) {
            val ang = i / 56f * 6.283f
            val ok = project(sin(ang) * rr, gy, cos(ang) * rr)
            if (ok && prevOk) c.drawLine(lx, ly, px, py, line)
            prevOk = ok; lx = px; ly = py
        }
    }

    private fun gridLine(x0: Float, y: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        // Short pieces so the far end can fade and pieces behind the camera are skipped
        val n = 6
        var prevOk = false; var lx = 0f; var ly = 0f; var lz = 0f
        for (i in 0..n) {
            val u = i.toFloat() / n
            val ok = project(x0 + (x1 - x0) * u, y + (y1 - y) * u, z0 + (z1 - z0) * u)
            if (ok && prevOk) {
                val b = (fogK((pz + lz) / 2) * 3.99f).toInt().coerceIn(0, 3)
                var buf = gridBuf[b]
                if (buf.size < gridN[b] + 4) { buf = buf.copyOf(buf.size * 2); gridBuf[b] = buf }
                buf[gridN[b]++] = lx; buf[gridN[b]++] = ly; buf[gridN[b]++] = px; buf[gridN[b]++] = py
            }
            prevOk = ok; lx = px; ly = py; lz = pz
        }
    }

    /** One thin tilted ring around the whole scene, with a light travelling along it. */
    private fun drawOrbit(c: Canvas, intro: Float) {
        val r = 9.5f
        line.strokeWidth = 1f * d
        var prevOk = false; var lx = 0f; var ly = 0f
        val tilt = 0.22f
        for (i in 0..96) {
            val ang = i / 96f * 6.283f
            val x = sin(ang) * r; val z = cos(ang) * r
            val ok = project(x, 1.2f + sin(ang + 1f) * r * tilt, z)
            if (ok && prevOk) { line.color = Color.argb((34 * fogK(pz) * intro).toInt(), 160, 200, 255); c.drawLine(lx, ly, px, py, line) }
            prevOk = ok; lx = px; ly = py
        }
        val ang = t * 0.35f
        if (project(sin(ang) * r, 1.2f + sin(ang + 1f) * r * tilt, cos(ang) * r)) {
            val rad = max(2f, 30 * d / pz)
            glow(c, px, py, rad * 3f, (230 * intro).toInt())
        }
    }

    private class Streak(var x: Float, val y: Float, val len: Float, val speed: Float, val alpha: Int)
    private val streaks = ArrayList<Streak>()

    /** Faint lines of light that zip across now and then. */
    private fun drawStreaks(c: Canvas, dt: Float, intro: Float) {
        if (rnd.nextFloat() < dt * 0.9f) {
            val dir = if (rnd.nextBoolean()) 1f else -1f
            streaks.add(Streak(if (dir > 0) -200 * d else width + 200 * d, height * (0.05f + rnd.nextFloat() * 0.85f),
                (60 + rnd.nextFloat() * 140) * d, dir * (500 + rnd.nextFloat() * 700) * d, 40 + rnd.nextInt(70)))
        }
        val it = streaks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.speed * dt
            if (s.x < -400 * d || s.x > width + 400 * d) { it.remove(); continue }
            val tail = s.x - kotlin.math.sign(s.speed) * s.len
            fill.shader = LinearGradient(tail, 0f, s.x, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((s.alpha * intro).toInt(), 190, 220, 255)), null, Shader.TileMode.CLAMP)
            c.drawRect(min(tail, s.x), s.y, max(tail, s.x), s.y + 1f * d, fill); fill.shader = null
        }
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

    // Scrolling list state
    private var scroll = 0f
    private var scrollVel = 0f
    private var maxScroll = 0f
    private var listTop = 0f
    private var listBottom = 0f
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private fun drawMenu(c: Canvas, intro: Float, dt: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = ((intro - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (a <= 0f) return
        // Title with a thin glowing rule beneath it
        text.textAlign = Paint.Align.CENTER
        text.textSize = 32 * d
        text.letterSpacing = 0.45f
        text.setShadowLayer(14 * d, 0f, 0f, Color.argb((255 * a).toInt(), 70, 140, 255))
        text.color = Color.argb((255 * a).toInt(), 235, 242, 255)
        c.drawText("NEXUS", w / 2 + 6 * d, h * 0.12f, text)
        text.setShadowLayer(0f, 0f, 0f, 0)
        val ruleY = h * 0.12f + 16 * d
        fill.shader = LinearGradient(w * 0.25f, 0f, w * 0.75f, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((200 * a).toInt(), 130, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(w * 0.25f, ruleY, w * 0.75f, ruleY + 1.2f * d, fill); fill.shader = null
        // A spark travelling along the rule
        val sx = w * 0.25f + (w * 0.5f) * ((t * 0.35f) % 1f)
        fill.color = Color.argb((220 * a).toInt(), 220, 235, 255); c.drawCircle(sx, ruleY + 0.6f * d, 1.8f * d, fill)
        // Status corners: date left, clock right, thin brackets
        text.letterSpacing = 0.12f; text.textSize = 11.5f * d
        text.color = Color.argb((170 * a).toInt(), 190, 210, 245)
        text.textAlign = Paint.Align.LEFT; c.drawText(dateFmt.format(Date()).uppercase(), 20 * d, 32 * d, text)
        text.textAlign = Paint.Align.RIGHT; c.drawText(clock.format(Date()), w - 20 * d, 32 * d, text)
        // Settings gear under the clock
        gearRect.set(w - 50 * d, 40 * d, w - 14 * d, 76 * d)
        drawGear(c, gearRect.centerX(), gearRect.centerY(), 8 * d, Color.argb((150 * a).toInt(), 180, 205, 245))
        line.strokeWidth = 1f * d; line.color = Color.argb((70 * a).toInt(), 140, 190, 255)
        bracket(c, 12 * d, 12 * d, 1, 1); bracket(c, w - 12 * d, 12 * d, -1, 1)
        bracket(c, 12 * d, h - 12 * d, 1, -1); bracket(c, w - 12 * d, h - 12 * d, -1, -1)

        // Rows (scrollable)
        rows.clear()
        listTop = h * 0.24f; listBottom = h - 92 * d
        val rowH = 76 * d; val gap = 12 * d; val addH = 58 * d
        val left = 22 * d; val right = w - 22 * d
        val total = games.size * (rowH + gap) + addH
        maxScroll = max(0f, total - (listBottom - listTop))
        // Fling with friction, springing back at the ends
        if (!dragging) {
            scroll += scrollVel * dt
            scrollVel *= kotlin.math.exp(-dt * 3.5f)
            if (kotlin.math.abs(scrollVel) < 5f) scrollVel = 0f
            if (scroll < 0f) { scroll += (0f - scroll) * min(1f, dt * 12f); scrollVel = 0f }
            if (scroll > maxScroll) { scroll += (maxScroll - scroll) * min(1f, dt * 12f); scrollVel = 0f }
        }
        val startY = if (maxScroll > 0f) listTop else listTop + (listBottom - listTop - total) * 0.55f
        var y = startY - scroll
        c.save()
        c.clipRect(0f, listTop - 6 * d, w, listBottom + 6 * d)
        if (animY.size != games.size) animY = FloatArray(games.size) { Float.NaN }
        // While an app is being moved, the others slide to make a gap where it will land
        val target = if (moveIndex >= 0) moveTarget(startY, rowH + gap) else -1
        val k = min(1f, dt * 16f)
        for ((i, g) in games.withIndex()) {
            var slot = i
            if (moveIndex >= 0 && i != moveIndex) {
                if (i > moveIndex && i <= target) slot = i - 1
                if (i < moveIndex && i >= target) slot = i + 1
            }
            val wantY = startY + slot * (rowH + gap) - scroll
            animY[i] = if (animY[i].isNaN() || moveIndex < 0 && !settling) wantY else animY[i] + (wantY - animY[i]) * k
            val r = RectF(left, animY[i], right, animY[i] + rowH)
            rows.add(RectF(left, y, right, y + rowH))
            if (i != moveIndex && r.bottom > listTop - 10 * d && r.top < listBottom + 10 * d) drawRow(c, g, r, i == selected && moveIndex < 0, a)
            y += rowH + gap
        }
        if (settling) { settleT -= dt; if (settleT <= 0f) settling = false }
        val ar = RectF(left, y, right, y + addH)
        rows.add(ar)
        val addSel = selected == games.size
        line.strokeWidth = (if (addSel) 1.6f else 1f) * d
        line.color = Color.argb(((if (addSel) 200 else 90) * a).toInt(), 140, 190, 255)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
        c.drawRoundRect(ar, 6 * d, 6 * d, line)
        line.pathEffect = null
        text.textAlign = Paint.Align.CENTER
        text.textSize = 14 * d; text.letterSpacing = 0.18f
        text.color = Color.argb(((if (addSel) 255 else 190) * a).toInt(), 170, 205, 255)
        c.drawText("+  ADD AN APP", ar.centerX(), ar.centerY() + 5 * d, text)
        c.restore()
        // The app being moved floats above everything, a little bigger and glowing
        if (moveIndex in games.indices) {
            // Scroll when dragging near the top or bottom of the list
            if (moveY < listTop + 50 * d) scroll = max(0f, scroll - dt * 600f * d / 2.6f)
            if (moveY > listBottom - 50 * d) scroll = min(maxScroll, scroll + dt * 600f * d / 2.6f)
            val top = moveY - moveGrab
            val r = RectF(left, top, right, top + rowH)
            c.save(); c.scale(1.04f, 1.04f, r.centerX(), r.centerY())
            fill.shader = RadialGradient(r.centerX(), r.centerY(), r.width() * 0.7f, intArrayOf(Color.argb(90, 90, 150, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(r.left - 30 * d, r.top - 30 * d, r.right + 30 * d, r.bottom + 30 * d, fill); fill.shader = null
            fill.color = Color.argb(200, 6, 12, 30); c.drawRoundRect(r, 6 * d, 6 * d, fill)
            drawRow(c, games[moveIndex], r, true, a)
            c.restore()
        }
        // Soft fades where the list scrolls under the edges
        if (maxScroll > 0f) {
            fill.shader = LinearGradient(0f, listTop - 6 * d, 0f, listTop + 24 * d, intArrayOf(Color.rgb(4, 8, 24), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, listTop - 6 * d, w, listTop + 24 * d, fill)
            fill.shader = LinearGradient(0f, listBottom - 24 * d, 0f, listBottom + 6 * d, intArrayOf(Color.TRANSPARENT, Color.rgb(2, 4, 12)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, listBottom - 24 * d, w, listBottom + 6 * d, fill); fill.shader = null
            // Thin scroll indicator
            val trackH = listBottom - listTop
            val thumbH = max(30 * d, trackH * trackH / (trackH + maxScroll))
            val ty = listTop + (trackH - thumbH) * (scroll / maxScroll).coerceIn(0f, 1f)
            fill.color = Color.argb((120 * a).toInt(), 140, 190, 255)
            c.drawRoundRect(RectF(w - 9 * d, ty, w - 7 * d, ty + thumbH), d, d, fill)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 10.5f * d; text.letterSpacing = 0.12f
        text.color = Color.argb((110 * a).toInt(), 160, 185, 230)
        // ALL APPS button (or swipe up from here)
        val bob = sin(t * 2f) * 2 * d
        // RECENT (open apps) on the left, ALL APPS on the right
        recentRect.set(w / 2 - 150 * d, h - 54 * d, w / 2 - 8 * d, h - 18 * d)
        fill.color = Color.argb((60 * a).toInt(), 40, 80, 200)
        c.drawRoundRect(recentRect, 18 * d, 18 * d, fill)
        line.strokeWidth = 1f * d; line.color = Color.argb((120 * a).toInt(), 140, 190, 255)
        c.drawRoundRect(recentRect, 18 * d, 18 * d, line)
        // stacked-cards glyph
        line.color = Color.argb((220 * a).toInt(), 200, 225, 255)
        c.drawRoundRect(RectF(recentRect.left + 18 * d, recentRect.centerY() - 6 * d, recentRect.left + 30 * d, recentRect.centerY() + 8 * d), 2 * d, 2 * d, line)
        c.drawLine(recentRect.left + 21 * d, recentRect.centerY() - 9 * d, recentRect.left + 33 * d, recentRect.centerY() - 9 * d, line)
        c.drawLine(recentRect.left + 33 * d, recentRect.centerY() - 9 * d, recentRect.left + 33 * d, recentRect.centerY() + 5 * d, line)
        text.textAlign = Paint.Align.LEFT; text.textSize = 12.5f * d; text.letterSpacing = 0.25f
        text.color = Color.argb((230 * a).toInt(), 220, 235, 255)
        c.drawText("RECENT", recentRect.left + 44 * d, recentRect.centerY() + 4.5f * d, text)
        allAppsRect.set(w / 2 + 8 * d, h - 54 * d, w / 2 + 150 * d, h - 18 * d)
        fill.color = Color.argb((60 * a).toInt(), 40, 80, 200)
        c.drawRoundRect(allAppsRect, 18 * d, 18 * d, fill)
        line.strokeWidth = 1f * d; line.color = Color.argb((120 * a).toInt(), 140, 190, 255)
        c.drawRoundRect(allAppsRect, 18 * d, 18 * d, line)
        // little 3x3 grid glyph
        fill.color = Color.argb((220 * a).toInt(), 200, 225, 255)
        for (gx in 0..2) for (gy in 0..2) c.drawRect(allAppsRect.left + 18 * d + gx * 5 * d, allAppsRect.centerY() - 7 * d + gy * 5 * d,
            allAppsRect.left + 21 * d + gx * 5 * d, allAppsRect.centerY() - 4 * d + gy * 5 * d, fill)
        text.textAlign = Paint.Align.LEFT; text.textSize = 12.5f * d; text.letterSpacing = 0.25f
        text.color = Color.argb((230 * a).toInt(), 220, 235, 255)
        c.drawText("ALL APPS", allAppsRect.left + 42 * d, allAppsRect.centerY() + 4.5f * d, text)
        line.color = Color.argb((90 * a).toInt(), 160, 200, 255)
        c.drawLine(w / 2 - 8 * d, h - 62 * d + bob, w / 2, h - 66 * d + bob, line); c.drawLine(w / 2, h - 66 * d + bob, w / 2 + 8 * d, h - 62 * d + bob, line)
        text.textAlign = Paint.Align.CENTER
    }

    private val gearRect = RectF()

    private fun drawGear(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        line.color = color; line.strokeWidth = 1.6f * d
        c.drawCircle(cx, cy, r * 0.62f, line)
        c.drawCircle(cx, cy, r * 0.25f, line)
        for (k in 0 until 8) {
            val a = k / 8f * 6.283f
            c.drawLine(cx + cos(a) * r * 0.62f, cy + sin(a) * r * 0.62f, cx + cos(a) * r, cy + sin(a) * r, line)
        }
    }

    private val allAppsRect = RectF()
    private val recentRect = RectF()
    private var swipedUp = false

    // Moving apps (hold, then drag)
    private var moveIndex = -1
    private var moveY = 0f
    private var moveGrab = 0f
    private var movedWhileHeld = false
    private var animY = FloatArray(0)
    private var settling = false
    private var settleT = 0f
    private var lastTarget = -1

    /** Which slot the dragged app would land in. */
    private fun moveTarget(startY: Float, step: Float): Int {
        val centre = moveY - moveGrab + (76 * d) / 2
        val slot = ((centre + scroll - startY) / step).toInt()
        val tgt = slot.coerceIn(0, games.size - 1)
        if (tgt != lastTarget) { if (lastTarget >= 0) sfx.tick(); lastTarget = tgt }
        return tgt
    }

    /** Thin L-shaped corner mark. */
    private fun bracket(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
        val l = 14 * d
        c.drawLine(x, y, x + sx * l, y, line); c.drawLine(x, y, x, y + sy * l, line)
    }

    private var selIndex = -2
    private var selSince = 0f

    private fun drawRow(c: Canvas, g: GameEntry, r: RectF, sel: Boolean, a0: Float) {
        // Fade the highlight in rather than snapping it on
        if (selected != selIndex) { selIndex = selected; selSince = t }
        val a = if (sel) a0 * (0.55f + 0.45f * min(1f, (t - selSince) / 0.18f)) else a0
        val pulse = 0.5f + 0.5f * sin(t * 3f)
        // Dark glass panel
        fill.color = Color.argb(((if (sel) 80 else 30) * a).toInt(), 40, 80, 200)
        c.drawRoundRect(r, 6 * d, 6 * d, fill)
        line.strokeWidth = 1f * d
        line.color = Color.argb(((if (sel) 150 + 90 * pulse else 55f) * a).toInt(), 140, 190, 255)
        c.drawRoundRect(r, 6 * d, 6 * d, line)
        if (sel) {
            fill.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Color.argb((90 * a).toInt(), 120, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 6 * d, 6 * d, fill); fill.shader = null
            // A white sheen sweeping across the highlighted row
            val k = (t * 0.7f) % 1.6f
            val sx = r.left + (r.width() + 120 * d) * k - 60 * d
            c.save(); c.clipRect(r)
            fill.shader = LinearGradient(sx - 50 * d, 0f, sx + 50 * d, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((70 * a).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(sx - 50 * d, r.top, sx + 50 * d, r.bottom, fill); fill.shader = null
            c.restore()
            // Corner ticks
            line.strokeWidth = 2f * d; line.color = Color.argb((230 * a).toInt(), 200, 225, 255)
            val l = 10 * d
            c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d + l, r.top - 4 * d, line); c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d, r.top - 4 * d + l, line)
            c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d - l, r.bottom + 4 * d, line); c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d, r.bottom + 4 * d - l, line)
        }
        // Left accent bar in the app's colour
        fill.color = Color.argb(((if (sel) 255 else 140) * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        c.drawRect(r.left, r.top + 14 * d, r.left + 2.5f * d, r.bottom - 14 * d, fill)
        // Icon
        val ic = RectF(r.left + 14 * d, r.top + 11 * d, r.left + 14 * d + r.height() - 22 * d, r.bottom - 11 * d)
        drawIcon(c, g, ic, a)
        // Name and description
        text.textAlign = Paint.Align.LEFT
        text.textSize = 19 * d; text.letterSpacing = 0.1f
        text.color = Color.argb((255 * a).toInt(), 240, 245, 255)
        c.drawText(g.name, ic.right + 16 * d, r.top + r.height() * 0.46f, text)
        text.textSize = 11.5f * d; text.letterSpacing = 0.06f
        text.color = if (g.installed) Color.argb((175 * a).toInt(), 170, 200, 240) else Color.argb((200 * a).toInt(), 255, 190, 110)
        val sub = if (!g.installed) "Not installed" else g.tagline
        c.drawText(sub, ic.right + 16 * d, r.top + r.height() * 0.72f, text)
        if (sel) {
            // Chevron on the right
            line.strokeWidth = 2f * d; line.color = Color.argb((220 * a).toInt(), 200, 225, 255)
            val cx = r.right - 22 * d; val cy = r.centerY(); val s = 6 * d
            c.drawLine(cx - s * 0.5f, cy - s, cx + s * 0.5f, cy, line); c.drawLine(cx + s * 0.5f, cy, cx - s * 0.5f, cy + s, line)
        }
    }

    private fun drawIcon(c: Canvas, g: GameEntry, r: RectF, a: Float) {
        fill.color = Color.argb((255 * a).toInt(), 8, 12, 24)
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        line.strokeWidth = 1f * d
        line.color = Color.argb((120 * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        c.drawRoundRect(r, 8 * d, 8 * d, line)
        val cxm = r.centerX(); val cym = r.centerY(); val u = r.width() / 10f
        val al = (255 * a).toInt()
        if (g.drawable != null) {
            val pad = 4 * d
            g.drawable.setBounds((r.left + pad).toInt(), (r.top + pad).toInt(), (r.right - pad).toInt(), (r.bottom - pad).toInt())
            g.drawable.alpha = al
            g.drawable.draw(c)
            return
        }
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
    // Tap = highlight; tap the highlighted one again = open. Dragging scrolls and never selects.

    private var downRow = -1
    private var longFired = false
    private var dragging = false
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var tracker: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** Hold: the app lifts up and can be dragged to a new spot (or, if you let go without moving, options). */
    private val longPress = Runnable {
        val r = downRow
        if (!dragging && r in games.indices) {
            longFired = true
            moveIndex = r
            moveY = lastY
            moveGrab = lastY - (rows.getOrNull(r)?.top ?: lastY)
            movedWhileHeld = false
            lastTarget = r
            selected = -1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            sfx.tick()
        }
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (y < listTop - 6 * d || y > listBottom + 6 * d) return -1
        return rows.indexOfFirst { it.contains(x, y) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = android.view.VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (booting) return true // the intro always plays in full
                downX = e.x; downY = e.y; lastY = e.y
                dragging = false; longFired = false
                scrollVel = 0f
                downRow = rowAt(e.x, e.y)
                swipedUp = false
                if (downRow in games.indices) postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                if (moveIndex >= 0) {
                    moveY = e.y
                    if (kotlin.math.abs(e.y - downY) > slop) movedWhileHeld = true
                    lastY = e.y
                    return true
                }
                if (!dragging && (kotlin.math.abs(e.y - downY) > slop || kotlin.math.abs(e.x - downX) > slop)) {
                    dragging = true
                    removeCallbacks(longPress)
                }
                // Swipe up from the bottom opens all apps
                if (!swipedUp && downY > listBottom && downY - e.y > 50 * d) { swipedUp = true; sfx.tick(); onDrawer(); dragging = true }
                if (dragging && !swipedUp) {
                    var dy = lastY - e.y
                    // Resist past the ends
                    if (scroll < 0f || scroll > maxScroll) dy *= 0.4f
                    scroll += dy
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (moveIndex >= 0) {
                    val from = moveIndex
                    val to = lastTarget.coerceIn(0, games.size - 1)
                    moveIndex = -1
                    if (!movedWhileHeld) { if (games[from].custom) onRemove(games[from]) }
                    else if (to != from) {
                        // Keep the slide smooth: carry the animated positions over to the new order
                        val list = animY.toMutableList(); val v = list.removeAt(from); list.add(to, moveY - moveGrab)
                        animY = list.toFloatArray()
                        settling = true; settleT = 0.4f
                        onReorder(from, to)
                        sfx.confirm()
                    } else { settling = true; settleT = 0.4f; animY[from] = moveY - moveGrab }
                    downRow = -1; dragging = false
                    tracker?.recycle(); tracker = null
                    return true
                }
                if (dragging && !swipedUp) {
                    tracker?.computeCurrentVelocity(1000)
                    scrollVel = -(tracker?.yVelocity ?: 0f)
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && recentRect.contains(e.x, e.y)) {
                    sfx.tick(); onRecent()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && allAppsRect.contains(e.x, e.y)) {
                    sfx.tick(); onDrawer()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && gearRect.contains(e.x, e.y)) {
                    sfx.tick(); onSettings()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f) {
                    val r = rowAt(e.x, e.y)
                    if (r >= 0 && r == downRow) {
                        if (r != selected) { selected = r; sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                        else {
                            sfx.confirm()
                            performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            if (r == games.size) onAdd()
                            else { pending = games[r]; launchFlash = LAUNCH_LEN }
                        }
                        performClick()
                    } else if (r < 0) selected = -1
                }
                dragging = false
                downRow = -1
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); moveIndex = -1; dragging = false; downRow = -1; tracker?.recycle(); tracker = null }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun clearSelection() { selected = -1 }

    /** Called when the screen comes back (e.g. after closing a game). */
    fun resumed(fromApp: Boolean) {
        lastNs = 0L; launchFlash = 0f; pending = null
        if (fromApp) enterFade = 0.7f
    }

    private val LAUNCH_LEN = 0.6f
}
