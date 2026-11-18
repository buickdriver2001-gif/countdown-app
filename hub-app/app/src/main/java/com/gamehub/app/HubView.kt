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
    /** Tile size in grid cells (width × height). */
    var tileW = 2
    var tileH = 1

    // Text layout, worked out once per tile size rather than every frame
    internal var rowsW = -1f; internal var rowsStep = -1f; internal var rowsSub: String? = null; internal var rowsBase = -1; internal var rows = 1
    internal var planW = -1f; internal var planH = -1f; internal var planBase = -1f; internal var planSub: String? = null
    internal var plan: TileText.Plan? = null
}

/**
 * The hub screen: a dark void full of slowly turning towers of glowing glass cubes,
 * drifting motes of light, and a simple glowing menu of games.
 */
@SuppressLint("ViewConstructor")
class HubView(context: Context, var games: List<GameEntry>, private val onPick: (GameEntry) -> Unit,
              private val onAdd: () -> Unit, private val onRemove: (GameEntry) -> Unit, private val sfx: Sfx,
              private val onSettings: () -> Unit, private val onReorder: (Int, Int) -> Unit,
              private val onDrawer: () -> Unit, private val onRecent: () -> Unit,
              private val onSystem: () -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val rnd = Random(3)

    /** A column of small glass blocks. Heights slowly rise and fall. */
    private class Tower(val x: Float, val z: Float, val cubes: Int, val size: Float, val phase: Float, val swing: Float)
    /** Towers stand in clusters around you, like a city of light. */
    private class Cluster(val x: Float, val z: Float)
    private val clusters = List(8) {
        val a = it / 8f * 6.283f + rnd.nextFloat() * 0.5f
        val r = 7f + rnd.nextFloat() * 8f
        Cluster(sin(a) * r, cos(a) * r)
    }
    private val towers = clusters.flatMap { cl ->
        List(4 + rnd.nextInt(4)) {
            val ox = (rnd.nextFloat() - 0.5f) * 2.6f; val oz = (rnd.nextFloat() - 0.5f) * 2.6f
            Tower(cl.x + ox, cl.z + oz, 3 + rnd.nextInt(13), 0.42f + rnd.nextFloat() * 0.12f, rnd.nextFloat() * 6.283f, 1f + rnd.nextFloat() * 3f)
        }
    }
    /** Soft clouds of blue haze drifting through the scene (screen space). */
    private class Haze(var x: Float, val y: Float, val r: Float, val speed: Float, val alpha: Int)
    private val haze = List(7) { Haze(rnd.nextFloat(), 0.15f + rnd.nextFloat() * 0.7f, 0.35f + rnd.nextFloat() * 0.45f, (rnd.nextFloat() - 0.5f) * 0.02f, 18 + rnd.nextInt(22)) }
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
        adaptQuality(dt)
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / 2.5f)

        // Deep blue-black void with a soft horizon glow (drawn once, then reused)
        c.drawBitmap(backdrop(), 0f, 0f, null)

        drawHaze(c, dt, intro)
        drawTowers(c, intro)
        drawOrb(c, intro)
        drawMotes(c, dt, intro)
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

    /**
     * Detail level for the background: 2 = everything, 1 = no grid floor, 0 = also no far towers / extras.
     * If the phone starts dropping frames, detail is lowered so taps and transitions stay smooth;
     * when there's room again it comes back.
     */
    private var quality = 2
    private var frameAvg = 1f / 60f
    private var slowFor = 0f
    private var fastFor = 0f

    private fun adaptQuality(dt: Float) {
        if (dt <= 0f || dt > 0.25f) return // ignore pauses (screen off, app switch)
        frameAvg += (dt - frameAvg) * 0.08f
        if (frameAvg > 1f / 45f) { slowFor += dt; fastFor = 0f } else if (frameAvg < 1f / 57f) { fastFor += dt; slowFor = 0f } else { slowFor = 0f; fastFor = 0f }
        if (slowFor > 1.2f && quality > 0) { quality--; slowFor = 0f }
        if (fastFor > 6f && quality < 2) { quality++; fastFor = 0f }
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
        p.shader = RadialGradient(w / 2, h * 0.45f, w * 0.9f, intArrayOf(Color.argb(80, 40, 90, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // Dark vignette around the edges
        p.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(170, 0, 0, 4)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
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
            if (quality == 0 && (tw.x * sy + tw.z * cy) > 11f) continue
            val bob = sin(t * 0.4f + tw.phase) * 0.06f
            // Heights breathe slowly, like the towers are alive
            val live = (tw.cubes + sin(t * 0.28f + tw.phase * 2f) * tw.swing).coerceAtLeast(1f)
            val built = (intro * 1.4f * live).toInt().coerceAtMost(live.toInt())
            vn = 0; lineN.fill(0)
            var fogT = 1f
            for (k in 0 until built) {
                val y0 = -3.4f + k * tw.size * 1.12f + bob
                fogT = addCube(tw.x, y0, tw.z, tw.size, k, tw)
            }
            flushTower(c, fogT)
        }
    }

    /** Glow pooled under each cluster of towers. */
    private fun clusterGlows(c: Canvas) {
        for (cl in clusters) if (project(cl.x, -3.5f, cl.z)) glow(c, px, py, width * 2.2f / pz, (110 * fogK(pz)).toInt())
    }

    private fun drawHaze(c: Canvas, dt: Float, intro: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        for (hz in haze) {
            hz.x += hz.speed * dt
            if (hz.x < -0.6f) hz.x += 2.2f
            if (hz.x > 1.6f) hz.x -= 2.2f
            glow(c, hz.x * w, hz.y * h, hz.r * w, (hz.alpha * intro).toInt())
        }
        clusterGlows(c)
    }

    /** A soft orb of light wandering slowly through the towers. */
    private fun drawOrb(c: Canvas, intro: Float) {
        val ox = sin(t * 0.07f) * 6f; val oz = cos(t * 0.05f) * 6f + 2f
        val oy = 0.8f + sin(t * 0.23f) * 1.2f
        if (!project(ox, oy, oz)) return
        val r = width * 0.9f / pz
        val pulse = 0.8f + 0.2f * sin(t * 1.7f)
        glow(c, px, py, r * 2.6f, (70 * intro * pulse).toInt())
        glow(c, px, py, r, (190 * intro * pulse).toInt())
        fill.color = Color.argb((235 * intro).toInt(), 235, 245, 255)
        c.drawCircle(px, py, max(1.5f, r * 0.14f), fill)
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
    private val quadOrder = intArrayOf(0, 1, 2, 0, 2, 3)
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
        for (fi in faces.indices) {
            val f = faces[fi]
            val top = fi == 0
            val col = Color.argb(((if (top) base + 20 else base) * fog).toInt(), if (top) 120 else 60, if (top) 170 else 115, 255)
            for (j in quadOrder) {
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
        for (mi in motes.indices) {
            val m = motes[mi]
            if (quality == 0 && mi % 2 == 1) continue
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


        // App tiles on a home-screen grid (scrollable)
        rows.clear()
        listTop = h * 0.2f; listBottom = h - 76 * d
        val gap = 10 * d
        val left = 18 * d; val right = w - 18 * d
        val cols = gridCols.coerceIn(2, 6)
        layoutCols = cols
        cellW = (right - left - gap * (cols - 1)) / cols
        cellH = ((listBottom - listTop - gap * (gridRows - 1)) / gridRows).coerceIn(cellW * 0.7f, cellW * 1.3f)
        val stepX = cellW + gap; val stepY = cellH + gap
        // Pack tiles in order (first free spot that fits, like a phone home screen); the add tile goes last
        val order = ArrayList<Int>(games.size + 1)
        var target = -1
        if (moveIndex in games.indices) {
            // Where would the dragged tile land? Look at the layout without it and find the tile under the finger
            val rest = games.indices.filter { it != moveIndex }
            val restPos = pack(rest, cols)
            val fx = lastX; val fy = moveY + scroll - listTop
            var hit = rest.size
            for ((k, idx) in rest.withIndex()) {
                val p = restPos[k]
                val x0 = left + p[0] * stepX; val y0 = p[1] * stepY
                if (fx >= x0 && fx <= x0 + p[2] * stepX && fy >= y0 && fy <= y0 + p[3] * stepY) { hit = k; break }
                if (idx >= 0 && fy < y0) { hit = k; break }
            }
            target = hit.coerceIn(0, rest.size)
            if (target != lastTarget) { if (lastTarget >= 0) sfx.tick(); lastTarget = target }
            order.addAll(rest); order.add(target, moveIndex)
        } else order.addAll(games.indices)
        order.add(-1) // the + ADD tile
        val pos = pack(order, cols)
        var usedRows = 0
        for (p in pos) usedRows = max(usedRows, p[1] + p[3])
        val total = usedRows * stepY - gap
        maxScroll = max(0f, total - (listBottom - listTop))
        // Fling with friction, springing back at the ends
        if (!dragging) {
            scroll += scrollVel * dt
            scrollVel *= kotlin.math.exp(-dt * 3.5f)
            if (kotlin.math.abs(scrollVel) < 5f) scrollVel = 0f
            if (scroll < 0f) { scroll += (0f - scroll) * min(1f, dt * 12f); scrollVel = 0f }
            if (scroll > maxScroll) { scroll += (maxScroll - scroll) * min(1f, dt * 12f); scrollVel = 0f }
        }
        val startY = listTop - scroll
        if (anim.size != games.size) anim = Array(games.size) { RectF(Float.NaN, 0f, 0f, 0f) }
        val k = min(1f, dt * 14f)
        val hitRects = arrayOfNulls<RectF>(games.size + 1)
        c.save()
        c.clipRect(0f, listTop - 8 * d, w, listBottom + 8 * d)
        for ((oi, idx) in order.withIndex()) {
            val p = pos[oi]
            val x0 = left + p[0] * stepX; val y0 = startY + p[1] * stepY
            val tr = RectF(x0, y0, x0 + p[2] * stepX - gap, y0 + p[3] * stepY - gap)
            if (idx < 0) {
                hitRects[games.size] = tr
                if (moveIndex < 0) drawAddTile(c, tr, selected == games.size, a)
                continue
            }
            hitRects[idx] = RectF(tr)
            val ar = anim[idx]
            // Tiles glide to their new spots (while moving apps, and just after dropping)
            if (ar.left.isNaN() || (moveIndex < 0 && !settling)) ar.set(tr)
            else ar.set(ar.left + (tr.left - ar.left) * k, ar.top + (tr.top - ar.top) * k, ar.right + (tr.right - ar.right) * k, ar.bottom + (tr.bottom - ar.bottom) * k)
            if (idx != moveIndex && ar.bottom > listTop - 10 * d && ar.top < listBottom + 10 * d) drawTile(c, games[idx], ar, idx == selected && moveIndex < 0, a)
        }
        for (hr in hitRects) rows.add(hr ?: RectF())
        if (settling) { settleT -= dt; if (settleT <= 0f) settling = false }
        c.restore()
        // The tile being moved floats above everything, a little bigger and glowing
        if (moveIndex in games.indices) {
            if (moveY < listTop + 50 * d) scroll = max(0f, scroll - dt * 230f * d)
            if (moveY > listBottom - 50 * d) scroll = min(maxScroll, scroll + dt * 230f * d)
            val g = games[moveIndex]
            val tw = min(g.tileW, cols) * stepX - gap; val th = rowsFor(g, cols) * stepY - gap
            val r = RectF(lastX - grabFx * tw, moveY - grabFy * th, lastX - grabFx * tw + tw, moveY - grabFy * th + th)
            c.save(); c.scale(1.05f, 1.05f, r.centerX(), r.centerY())
            glow(c, r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.9f, 150)
            fill.color = Color.argb(210, 6, 12, 30); c.drawRoundRect(r, 8 * d, 8 * d, fill)
            drawTile(c, g, r, true, a)
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
        // Bottom bar: RECENT · ALL APPS
        val pw = min(142 * d, (w - 48 * d) / 2f); val gapB = 12 * d
        val bx0 = w / 2 - (pw * 2 + gapB) / 2
        recentRect.set(bx0, h - 54 * d, bx0 + pw, h - 18 * d)
        allAppsRect.set(bx0 + pw + gapB, h - 54 * d, bx0 + pw * 2 + gapB, h - 18 * d)
        systemRect.setEmpty()
        bottomPill(c, recentRect, "RECENT", 0, a)
        bottomPill(c, allAppsRect, "ALL APPS", 2, a)
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
    private val systemRect = RectF()

    /** A glass pill with a small glyph and a label (glyph 0 = stacked cards, 1 = gauge, 2 = grid). */
    private fun bottomPill(c: Canvas, r: RectF, label: String, glyph: Int, a: Float) {
        fill.color = Color.argb((60 * a).toInt(), 40, 80, 200)
        c.drawRoundRect(r, 18 * d, 18 * d, fill)
        line.strokeWidth = 1f * d; line.color = Color.argb((120 * a).toInt(), 140, 190, 255)
        c.drawRoundRect(r, 18 * d, 18 * d, line)
        text.textAlign = Paint.Align.LEFT; text.textSize = 11 * d; text.letterSpacing = 0.18f
        val tw = text.measureText(label)
        val gw = 14 * d
        val x0 = r.centerX() - (gw + 8 * d + tw) / 2
        val cy = r.centerY()
        line.color = Color.argb((220 * a).toInt(), 200, 225, 255); fill.color = Color.argb((220 * a).toInt(), 200, 225, 255)
        when (glyph) {
            0 -> { // stacked cards
                c.drawRoundRect(RectF(x0, cy - 6 * d, x0 + 10 * d, cy + 7 * d), 2 * d, 2 * d, line)
                c.drawLine(x0 + 3 * d, cy - 9 * d, x0 + 13 * d, cy - 9 * d, line); c.drawLine(x0 + 13 * d, cy - 9 * d, x0 + 13 * d, cy + 4 * d, line)
            }
            1 -> { // gauge
                c.drawArc(RectF(x0, cy - 7 * d, x0 + 14 * d, cy + 7 * d), 150f, 240f, false, line)
                c.drawLine(x0 + 7 * d, cy, x0 + 11 * d, cy - 4 * d, line)
            }
            else -> for (gx in 0..2) for (gy in 0..2) c.drawRect(x0 + gx * 5 * d, cy - 7 * d + gy * 5 * d, x0 + 3 * d + gx * 5 * d, cy - 4 * d + gy * 5 * d, fill)
        }
        text.color = Color.argb((230 * a).toInt(), 220, 235, 255)
        c.drawText(label, x0 + gw + 8 * d, cy + 4 * d, text)
        text.textAlign = Paint.Align.CENTER
    }
    private val recentRect = RectF()
    private var swipedUp = false

    // Moving apps (hold, then drag)
    private var moveIndex = -1
    private var moveY = 0f
    /** Where the dragged tile is drawn right now (so dropping it glides smoothly into place). */
    private fun floatingRect(): RectF {
        val g = games.getOrNull(moveIndexForDrop) ?: return RectF()
        val stepX = cellW + 10 * d; val stepY = cellH + 10 * d
        val tw = min(g.tileW, layoutCols) * stepX - 10 * d; val th = rowsFor(g, layoutCols) * stepY - 10 * d
        return RectF(lastX - grabFx * tw, moveY - grabFy * th, lastX - grabFx * tw + tw, moveY - grabFy * th + th)
    }
    private var moveIndexForDrop = -1
    private var movedWhileHeld = false
    private var settling = false
    private var settleT = 0f
    private var lastTarget = -1

    /** Thin L-shaped corner mark. */
    private fun bracket(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
        val l = 14 * d
        c.drawLine(x, y, x + sx * l, y, line); c.drawLine(x, y, x, y + sy * l, line)
    }

    // ------------------------------------------------------------------ tiles

    /** Grid shape (columns × rows that fit on one screen), set from NEXUS settings. */
    var gridCols = 4
    var gridRows = 6
    private var cellW = 1f
    private var cellH = 1f
    private var anim = arrayOf<RectF>()
    private var lastX = 0f
    private var grabFx = 0.5f
    private var grabFy = 0.5f

    /** Pictures of each app's last screen, shown behind large tiles. */
    private val liveShots = HashMap<String, android.graphics.Bitmap>()

    fun loadLiveShots() {
        val pkgs = games.filter { it.tileH >= 2 }.map { it.pkg }
        Thread {
            for (p in pkgs) {
                val f = NexusAccessibility.snapFile(context, p)
                if (!f.exists()) continue
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                val b = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts) } catch (_: Exception) { null } ?: continue
                post { liveShots[p] = b }
            }
        }.start()
    }

    /** Tile positions for an order of apps (-1 = the ADD tile, always 1×1). */
    private fun pack(order: List<Int>, cols: Int): Array<IntArray> =
        TileLayout.pack(order.map { idx -> if (idx < 0) intArrayOf(1, 1) else intArrayOf(games[idx].tileW, rowsFor(games[idx], cols)) }, cols)

    // ------------------------------------------------------------------ tile text: always shown in full

    private var layoutCols = 4
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val measure = TileText.Measure { str, size, spacing ->
        measurePaint.textSize = size; measurePaint.letterSpacing = spacing; measurePaint.measureText(str)
    }

    private fun subOf(g: GameEntry) = if (!g.installed) "Not installed" else g.tagline

    /**
     * How many grid rows a tile takes: the size the user picked, plus extra rows if its full name
     * (and description) need more room than that, so nothing is ever cut off.
     */
    private fun rowsFor(g: GameEntry, cols: Int): Int {
        val gap = 10 * d
        val stepX = cellW + gap; val stepY = cellH + gap
        val w = min(g.tileW, cols) * stepX - gap
        val sub = subOf(g)
        if (g.rowsW == w && g.rowsStep == stepY && g.rowsSub == sub && g.rowsBase == g.tileH) return g.rows
        val baseH = g.tileH * stepY - gap
        var rows = g.tileH
        while (rows < g.tileH + 5 && !TileText.plan(g.name, sub, w, rows * stepY - gap, baseH, cellW, d, measure).fits) rows++
        g.rowsW = w; g.rowsStep = stepY; g.rowsSub = sub; g.rowsBase = g.tileH; g.rows = rows
        return rows
    }

    private fun planFor(g: GameEntry, w: Float, h: Float, baseH: Float): TileText.Plan {
        val sub = subOf(g)
        val p = g.plan
        if (p != null && g.planW == w && g.planH == h && g.planBase == baseH && g.planSub == sub) return p
        val np = TileText.plan(g.name, sub, w, h, baseH, cellW, d, measure)
        g.plan = np; g.planW = w; g.planH = h; g.planBase = baseH; g.planSub = sub
        return np
    }

    /** Draws lines of text from [top] down; returns where the block ends. */
    private fun drawLines(c: Canvas, lines: List<String>, x: Float, top: Float, size: Float, spacing: Float, color: Int, lineH: Float): Float {
        text.textSize = size; text.letterSpacing = spacing; text.color = color
        var y = top
        for (ln in lines) { c.drawText(ln, x, y + lineH * 0.8f, text); y += lineH }
        return y
    }

    private var selIndex = -2
    private var selSince = 0f

    /** One app tile; the layout adapts to its size (small, wide, tall, large). */
    private fun drawTile(c: Canvas, g: GameEntry, r: RectF, sel: Boolean, a0: Float) {
        if (selected != selIndex) { selIndex = selected; selSince = t }
        val a = if (sel) a0 * (0.55f + 0.45f * min(1f, (t - selSince) / 0.18f)) else a0
        val pulse = 0.5f + 0.5f * sin(t * 3f)
        val rad = 8 * d
        // Shape comes from the size the user picked; the tile may be taller to fit its full name
        val baseH = min(g.tileH * (cellH + 10 * d) - 10 * d, r.height())
        val plan = planFor(g, r.width(), r.height(), baseH)
        val wide = plan.kind == TileText.Kind.WIDE
        val tall = plan.kind == TileText.Kind.TALL
        val large = plan.kind == TileText.Kind.LARGE
        // Glass panel (large tiles show the app's last screen behind it)
        fill.color = Color.argb(((if (sel) 80 else 34) * a).toInt(), 40, 80, 200)
        c.drawRoundRect(r, rad, rad, fill)
        val shot = if (large || tall) liveShots[g.pkg] else null
        if (shot != null) {
            c.save(); clipPath.reset(); clipPath.addRoundRect(r, rad, rad, Path.Direction.CW); c.clipPath(clipPath)
            val sc = max(r.width() / shot.width, r.height() / shot.height)
            val dw = shot.width * sc
            shotDst.set(r.centerX() - dw / 2, r.top, r.centerX() + dw / 2, r.top + shot.height * sc)
            shotPaint.alpha = (150 * a).toInt()
            c.drawBitmap(shot, null, shotDst, shotPaint)
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(Color.argb((40 * a).toInt(), 2, 4, 14), Color.argb((235 * a).toInt(), 2, 4, 14)), null, Shader.TileMode.CLAMP)
            c.drawRect(r, fill); fill.shader = null
            c.restore()
        }
        line.strokeWidth = 1f * d
        line.color = Color.argb(((if (sel) 150 + 90 * pulse else 60f) * a).toInt(), 140, 190, 255)
        c.drawRoundRect(r, rad, rad, line)
        if (sel) {
            // White sheen sweeping across the highlighted tile
            val k = (t * 0.7f) % 1.6f
            val sx = r.left + (r.width() + 120 * d) * k - 60 * d
            c.save(); c.clipRect(r)
            fill.shader = LinearGradient(sx - 50 * d, 0f, sx + 50 * d, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((70 * a).toInt(), 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(sx - 50 * d, r.top, sx + 50 * d, r.bottom, fill); fill.shader = null
            c.restore()
            line.strokeWidth = 2f * d; line.color = Color.argb((230 * a).toInt(), 200, 225, 255)
            val l = 10 * d
            c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d + l, r.top - 4 * d, line); c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d, r.top - 4 * d + l, line)
            c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d - l, r.bottom + 4 * d, line); c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d, r.bottom + 4 * d - l, line)
        }
        val accent = Color.argb(((if (sel) 255 else 140) * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        val subCol = if (g.installed) Color.argb((175 * a).toInt(), 170, 200, 240) else Color.argb((200 * a).toInt(), 255, 190, 110)
        val nameCol = Color.argb((255 * a).toInt(), 240, 245, 255)
        text.textAlign = Paint.Align.LEFT
        c.save(); c.clipRect(r)
        when {
            wide -> {
                // Wide: icon on the left, the full name and description beside it
                fill.color = accent; c.drawRect(r.left, r.top + r.height() * 0.2f, r.left + 2.5f * d, r.bottom - r.height() * 0.2f, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 14 * d, r.centerY() - isz / 2, r.left + 14 * d + isz, r.centerY() + isz / 2)
                drawIcon(c, g, tmpRect, a)
                val tx = tmpRect.right + 14 * d
                var y = r.centerY() - TileText.blockHeight(plan, d) / 2
                y = drawLines(c, plan.name, tx, y, plan.nameSize, TileText.NAME_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                if (plan.sub.isNotEmpty()) drawLines(c, plan.sub, tx, y + 5 * d, plan.subSize, TileText.SUB_SPACING, subCol, TileText.subLineH(plan.subSize))
            }
            large || tall -> {
                // Large / tall: icon top-left, the full name and description along the bottom
                fill.color = accent; c.drawRect(r.left + 12 * d, r.top, r.left + 12 * d + r.width() * 0.25f, r.top + 2.5f * d, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 12 * d, r.top + 14 * d, r.left + 12 * d + isz, r.top + 14 * d + isz)
                drawIcon(c, g, tmpRect, a)
                var y = r.bottom - 14 * d - TileText.blockHeight(plan, d)
                y = drawLines(c, plan.name, r.left + 12 * d, y, plan.nameSize, TileText.NAME_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                if (plan.sub.isNotEmpty()) drawLines(c, plan.sub, r.left + 12 * d, y + 5 * d, plan.subSize, TileText.SUB_SPACING, subCol, TileText.subLineH(plan.subSize))
            }
            else -> {
                // Small: icon in the middle, the full name underneath (over more lines if it's long)
                val isz = plan.icon
                val block = TileText.blockHeight(plan, d)
                val iconTop = if (r.height() > baseH + 1f) r.top + (r.height() - (isz + 4 * d + block)) / 2 else r.top + plan.iconTop
                tmpRect.set(r.centerX() - isz / 2, iconTop, r.centerX() + isz / 2, iconTop + isz)
                drawIcon(c, g, tmpRect, a)
                text.textAlign = Paint.Align.CENTER
                val regionTop = tmpRect.bottom + 4 * d
                val regionH = r.bottom - 6 * d - regionTop
                val y = if (r.height() > baseH + 1f) regionTop else regionTop + max(0f, (regionH - block) / 2)
                drawLines(c, plan.name, r.centerX(), y, plan.nameSize, TileText.SMALL_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                text.textAlign = Paint.Align.LEFT
            }
        }
        c.restore()
    }

    private fun drawAddTile(c: Canvas, r: RectF, sel: Boolean, a: Float) {
        line.strokeWidth = (if (sel) 1.6f else 1f) * d
        line.color = Color.argb(((if (sel) 200 else 90) * a).toInt(), 140, 190, 255)
        line.pathEffect = android.graphics.DashPathEffect(floatArrayOf(7 * d, 5 * d), 0f)
        c.drawRoundRect(r, 8 * d, 8 * d, line)
        line.pathEffect = null
        val s = min(r.width(), r.height()) * 0.16f
        line.strokeWidth = 2f * d; line.color = Color.argb(((if (sel) 255 else 190) * a).toInt(), 170, 205, 255)
        val cy = r.centerY() - r.height() * 0.08f
        c.drawLine(r.centerX() - s, cy, r.centerX() + s, cy, line); c.drawLine(r.centerX(), cy - s, r.centerX(), cy + s, line)
        text.textAlign = Paint.Align.CENTER
        text.textSize = min(11 * d, r.width() * 0.11f); text.letterSpacing = 0.2f
        text.color = Color.argb(((if (sel) 255 else 180) * a).toInt(), 170, 205, 255)
        c.drawText("ADD", r.centerX(), r.bottom - r.height() * 0.14f, text)
    }

    private val tmpRect = RectF()
    private val shotDst = RectF()
    private val shotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()

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
    // Tap = highlight; tap the highlighted one again = open. Swiping scrolls and never highlights:
    //  - any finger movement past a few dp, or a quick flick (even a short one), counts as a swipe
    //  - the finger's position when it lifts counts too (a fast flick can jump a long way in that last step)
    //  - touching the list while it's still gliding just stops it, it doesn't pick what's under the thumb

    private var downRow = -1
    /** This touch landed on a list that was still moving: it only stops the list. */
    private var caught = false
    /** Furthest the finger has been from where it touched down. */
    private var maxTravel = 0f
    private fun travel(x: Float, y: Float) { maxTravel = max(maxTravel, kotlin.math.hypot(x - downX, y - downY)) }
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
            val rr = rows.getOrNull(r)
            if (rr != null && rr.width() > 0f) { grabFx = ((lastX - rr.left) / rr.width()).coerceIn(0f, 1f); grabFy = ((lastY - rr.top) / rr.height()).coerceIn(0f, 1f) }
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
                downX = e.x; downY = e.y; lastY = e.y; lastX = e.x
                dragging = false; longFired = false
                maxTravel = 0f
                caught = kotlin.math.abs(scrollVel) > 40 * d || scroll < -2 * d || scroll > maxScroll + 2 * d
                scrollVel = 0f
                downRow = rowAt(e.x, e.y)
                swipedUp = false
                if (downRow in games.indices && !caught) postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                for (k in 0 until e.historySize) travel(e.getHistoricalX(k), e.getHistoricalY(k))
                travel(e.x, e.y)
                if (moveIndex >= 0) {
                    moveY = e.y
                    if (kotlin.math.abs(e.y - downY) > slop) movedWhileHeld = true
                    lastY = e.y
                    return true
                }
                if (!dragging && maxTravel > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                }
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
                travel(e.x, e.y)
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                val vx = tracker?.xVelocity ?: 0f
                // A flick, however short, is a swipe, not a tap
                val flick = kotlin.math.hypot(vx, vy) > 350 * d
                val tap = !dragging && !flick && maxTravel <= slop * 0.75f
                if (moveIndex >= 0) {
                    val from = moveIndex
                    moveIndexForDrop = from
                    val to = lastTarget.coerceIn(0, games.size - 1)
                    moveIndex = -1
                    if (!movedWhileHeld) onRemove(games[from])
                    else if (to != from) {
                        // Keep the slide smooth: carry the animated positions over to the new order
                        val list = anim.toMutableList(); val v = list.removeAt(from)
                        v.set(floatingRect()); list.add(to, v)
                        anim = list.toTypedArray()
                        settling = true; settleT = 0.4f
                        onReorder(from, to)
                        sfx.confirm()
                    } else { settling = true; settleT = 0.4f; anim.getOrNull(from)?.set(floatingRect()) }
                    downRow = -1; dragging = false
                    tracker?.recycle(); tracker = null
                    return true
                }
                if (!tap) {
                    // Swipe: let the list glide on (a short flick that never got going still moves it)
                    val inList = downY >= listTop - 6 * d && downY <= listBottom + 6 * d
                    if (!swipedUp && (dragging || (flick && inList))) scrollVel = -vy
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && systemRect.contains(e.x, e.y)) {
                    sfx.tick(); onSystem()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && recentRect.contains(e.x, e.y)) {
                    sfx.tick(); onRecent()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && allAppsRect.contains(e.x, e.y)) {
                    sfx.tick(); onDrawer()
                } else if (!longFired && launchFlash <= 0f && t > 1.2f && gearRect.contains(e.x, e.y)) {
                    sfx.tick(); onSettings()
                } else if (!longFired && !caught && launchFlash <= 0f && t > 1.2f) {
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

    /** Tile tap → the screen settles into the dark (then the app rises out of it). */
    private val LAUNCH_LEN = 0.34f
}
