package com.gamehub.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The NEXUS world, after the feel of the PS2's start-up screen (an original take on it: no logo, no sound).
 * Seen from above at an angle, a field of grey glass towers stands on the dark floor: the more of the phone's
 * storage is used, the more towers there are and the taller they grow. Balls of red, green, blue and white light
 * sweep around them, trailing light, and the camera circles slowly, forever. Shown behind the home screen and
 * the lock screen.
 *
 * Drawing a frame makes no new objects: everything is reused.
 */
class Ps2World(context: Context) {
    private val d = context.resources.displayMetrics.density
    /** The world's own clock (seconds). */
    var t = 0f
    /** 0 for slow phones (fewer details) up to 2 (everything). */
    var quality = 2

    // ------------------------------------------------------------------ towers

    private class Tower(val x: Float, val z: Float, val cubes: Int, val delay: Float, val phase: Float)
    private var towers: Array<Tower> = emptyArray()
    private var builtFor = -1
    private var dist = FloatArray(0)
    private var order = IntArray(0)

    private val CUBE = 0.62f
    private val GAP = 0.07f

    /** Lays the towers out for storage [u] full (0..1): more of them, and taller, the fuller it is. Same layout every time. */
    private fun build(u: Float) {
        val rnd = java.util.Random(11)
        val n = (10 + u * 26).roundToInt().coerceIn(8, 36)
        val maxC = (2 + u * 9).roundToInt().coerceIn(2, 11)
        val spots = ArrayList<FloatArray>()
        val cell = 1.5f
        for (gx in -5..5) for (gz in -5..5) {
            val x = gx * cell + (rnd.nextFloat() - 0.5f) * 0.5f
            val z = gz * cell + (rnd.nextFloat() - 0.5f) * 0.5f
            if (hypot(x, z) <= 7.2f) spots.add(floatArrayOf(x, z, rnd.nextFloat()))
        }
        spots.sortBy { it[2] }
        towers = Array(min(n, spots.size)) { i ->
            val s = spots[i]
            // Every tower different, a little taller towards the middle
            val centre = 1f - hypot(s[0], s[1]) / 7.2f
            val c = (1 + (rnd.nextFloat().pow(1.3f) * 0.7f + centre * 0.3f) * (maxC - 1)).roundToInt().coerceIn(1, maxC)
            Tower(s[0], s[1], c, rnd.nextFloat(), rnd.nextFloat() * 6.283f)
        }
        dist = FloatArray(towers.size); order = IntArray(towers.size) { it }
    }

    // ------------------------------------------------------------------ the balls of light

    private class Orb(val color: Int, val speed: Float, val phase: Float, val rx: Float, val rz: Float, val y0: Float, val ya: Float) {
        val tx = FloatArray(TRAIL); val ty = FloatArray(TRAIL); val tz = FloatArray(TRAIL)
        var n = 0; var head = 0; var acc = 0f
        var x = 0f; var y = 0f; var z = 0f
        val glow = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY) }
    }
    private val orbs = arrayOf(
        Orb(Color.rgb(255, 70, 85), 0.9f, 0f, 6.6f, 5.0f, 2.3f, 1.2f),
        Orb(Color.rgb(80, 255, 140), 0.78f, 1.6f, 5.0f, 6.8f, 3.1f, 1.5f),
        Orb(Color.rgb(90, 150, 255), 1.0f, 3.1f, 7.2f, 4.3f, 1.7f, 1.0f),
        Orb(Color.rgb(255, 255, 255), 0.84f, 4.6f, 4.6f, 6.1f, 3.7f, 1.3f))

    private fun place(o: Orb, tt: Float) {
        val a = tt * o.speed + o.phase
        o.x = sin(a) * o.rx + sin(a * 2.1f + o.phase) * 1.4f
        o.z = cos(a * 0.9f) * o.rz + cos(a * 1.7f) * 1.0f
        o.y = o.y0 + sin(a * 1.6f + o.phase * 2f) * o.ya
    }

    /** A soft white glow, tinted per ball. */
    private val glowBmp: Bitmap by lazy {
        val s = 64
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Color.WHITE, Color.argb(110, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        b
    }
    private val glowDst = RectF()

    // Light adds up (like real light) where the paint supports it
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; additive() }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { additive() }
    private val floorGlow = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.rgb(120, 160, 255), PorterDuff.Mode.MULTIPLY) }
    private fun Paint.additive() { if (android.os.Build.VERSION.SDK_INT >= 29) blendMode = android.graphics.BlendMode.PLUS }
    init { for (o in orbs) o.glow.additive() }

    // ------------------------------------------------------------------ specks of light drifting up

    private val motes = Array(40) { floatArrayOf(0f, 0f, 0f, 0f) }
    init {
        val r = java.util.Random(5)
        for (m in motes) { m[0] = (r.nextFloat() - 0.5f) * 20f; m[1] = r.nextFloat() * 8f; m[2] = (r.nextFloat() - 0.5f) * 20f; m[3] = 0.15f + r.nextFloat() * 0.3f }
    }

    // ------------------------------------------------------------------ camera

    private var cx0 = 0f; private var cy0 = 0f; private var focal = 1f
    private var ex = 0f; private var ey = 0f; private var ez = 0f
    private var fx = 0f; private var fy = 0f; private var fz = 0f
    private var rx = 0f; private var rz = 0f
    private var ux = 0f; private var uy = 0f; private var uz = 0f
    private var px = 0f; private var py = 0f; private var pz = 0f

    /** The camera circles the towers slowly, looking down at them. [centreY]: where the middle of the field sits on screen. */
    private fun look(w: Int, h: Int, centreY: Float) {
        val ang = t * 0.045f + 0.7f
        val r = 11f
        ex = sin(ang) * r; ez = cos(ang) * r; ey = 12.5f + sin(t * 0.06f) * 0.5f
        val dx = -ex; val dy = 0.5f - ey; val dz = -ez
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        fx = dx / l; fy = dy / l; fz = dz / l
        val rl = sqrt(fz * fz + fx * fx)
        rx = -fz / rl; rz = fx / rl
        ux = -rz * fy; uy = rz * fx - rx * fz; uz = rx * fy
        cx0 = w / 2f; cy0 = h * centreY; focal = min(w, h) * 1.05f
    }

    private fun project(x: Float, y: Float, z: Float): Boolean {
        val vx = x - ex; val vy = y - ey; val vz = z - ez
        val zz = vx * fx + vy * fy + vz * fz
        if (zz < 0.8f) return false
        px = cx0 + (vx * rx + vz * rz) / zz * focal
        py = cy0 - (vx * ux + vy * uy + vz * uz) / zz * focal
        pz = zz
        return true
    }

    private fun fog(z: Float) = ((26f - z) / 12f).coerceIn(0.3f, 1f)

    // ------------------------------------------------------------------ drawing

    private var vtx = FloatArray(2048)
    private var vcol = IntArray(1024)
    private var vn = 0
    private var lines = FloatArray(1024)
    private var ln = 0
    private val sx = FloatArray(8); private val sy = FloatArray(8)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /**
     * Draws the world over whatever is behind it. [dt] seconds have passed (0: time stands still), [intro] 0..1
     * (towers rise out of the floor and the lights come on as it goes from 0 to 1), [dim] 0..1 overall brightness.
     */
    fun draw(c: Canvas, w: Int, h: Int, dt: Float, intro: Float, centreY: Float, dim: Float) {
        if (w <= 0 || h <= 0) return
        t += dt
        val step = (storageUsed() * 20).toInt()
        if (step != builtFor || towers.isEmpty()) { builtFor = step; build(step / 20f) }
        look(w, h, centreY)

        // A soft pool of light on the floor under the towers
        if (project(0f, 0f, 0f)) {
            val r = focal * 9f / pz
            floorGlow.alpha = (90 * dim * intro).toInt().coerceIn(0, 255)
            glowDst.set(px - r * 1.3f, py - r * 0.55f, px + r * 1.3f, py + r * 0.55f)
            c.drawBitmap(glowBmp, null, glowDst, floorGlow)
        }

        // Towers, the far ones first
        for (i in towers.indices) { val tw = towers[i]; val dx = tw.x - ex; val dz = tw.z - ez; dist[i] = dx * dx + dz * dz }
        for (i in 1 until order.size) { // insertion sort, far to near (no new objects)
            val k = order[i]; var j = i - 1
            while (j >= 0 && dist[order[j]] < dist[k]) { order[j + 1] = order[j]; j-- }
            order[j + 1] = k
        }
        for (oi in order.indices) {
            val tw = towers[order[oi]]
            val grown = ((intro * 1.3f - tw.delay * 0.3f) / 0.7f).coerceIn(0f, 1f)
            val built = kotlin.math.ceil(grown * tw.cubes).toInt()
            if (built <= 0) continue
            vn = 0; ln = 0
            var f = 1f
            for (k in 0 until built) f = addCube(tw, k, dim)
            flush(c, f, dim)
        }

        // Specks of light drifting up
        if (quality > 0) {
            for (m in motes) {
                m[1] += m[3] * dt
                if (m[1] > 9f) m[1] = 0f
                if (!project(m[0], m[1], m[2])) continue
                val a = (fog(pz) * 150 * dim * intro * (0.5f + 0.5f * sin(t * 2f + m[0]))).toInt().coerceIn(0, 255)
                core.color = Color.argb(a, 200, 220, 255)
                c.drawCircle(px, py, max(1f, 1.6f * d * 12f / pz), core)
            }
        }

        // The balls of light and their trails
        val trailLen = if (quality == 0) TRAIL / 2 else TRAIL
        for (o in orbs) {
            if (o.n == 0) { // first time: a trail already behind it
                for (i in TRAIL - 1 downTo 0) { place(o, t - i / RATE); push(o) }
            }
            o.acc += dt
            while (o.acc >= 1f / RATE) { o.acc -= 1f / RATE; place(o, t - o.acc); push(o) }
            place(o, t)
            drawOrb(c, o, trailLen, dim * intro)
        }
    }

    private fun push(o: Orb) {
        o.tx[o.head] = o.x; o.ty[o.head] = o.y; o.tz[o.head] = o.z
        o.head = (o.head + 1) % TRAIL
        if (o.n < TRAIL) o.n++
    }

    // The trail as one smooth ribbon of light: soft at its edges, bright along its middle, tapering to nothing at
    // the tail (drawn in one go, so nothing overlaps and builds up into dots)
    private val rpx = FloatArray(TRAIL + 1); private val rpy = FloatArray(TRAIL + 1); private val rps = FloatArray(TRAIL + 1)
    private val rv = FloatArray((TRAIL + 1) * 6)
    private val rc = IntArray((TRAIL + 1) * 3)
    private val ridx = ShortArray(TRAIL * 12).also { ix ->
        for (i in 0 until TRAIL) {
            val l = i * 3; val m = l + 1; val r = l + 2; val l2 = l + 3; val m2 = l + 4; val r2 = l + 5
            val o = i * 12
            ix[o] = l.toShort(); ix[o + 1] = m.toShort(); ix[o + 2] = l2.toShort()
            ix[o + 3] = m.toShort(); ix[o + 4] = m2.toShort(); ix[o + 5] = l2.toShort()
            ix[o + 6] = m.toShort(); ix[o + 7] = r.toShort(); ix[o + 8] = m2.toShort()
            ix[o + 9] = r.toShort(); ix[o + 10] = r2.toShort(); ix[o + 11] = m2.toShort()
        }
    }
    private val ribbonPaint = Paint().apply { color = Color.WHITE; additive() }

    /** One ribbon along the [m] points gathered: [w0]..[w1] half-width (tail..head), brightest [a] at the head. */
    private fun ribbon(c: Canvas, m: Int, color: Int, w0: Float, w1: Float, a: Float, curve: Float) {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val edge = Color.argb(0, r, g, b)
        var nx = 0f; var ny = -1f
        for (i in 0 until m) {
            val i0 = if (i > 0) i - 1 else 0; val i1 = if (i < m - 1) i + 1 else m - 1
            val tx = rpx[i1] - rpx[i0]; val ty = rpy[i1] - rpy[i0]
            val l = sqrt(tx * tx + ty * ty)
            if (l > 0.01f) { nx = -ty / l; ny = tx / l }
            val k = i.toFloat() / (m - 1)
            val half = (w0 + (w1 - w0) * k) * d * rps[i]
            val v = i * 6
            rv[v] = rpx[i] + nx * half; rv[v + 1] = rpy[i] + ny * half
            rv[v + 2] = rpx[i]; rv[v + 3] = rpy[i]
            rv[v + 4] = rpx[i] - nx * half; rv[v + 5] = rpy[i] - ny * half
            val ci = i * 3
            rc[ci] = edge; rc[ci + 2] = edge
            rc[ci + 1] = Color.argb((255 * a * k.pow(curve)).toInt().coerceIn(0, 255), r, g, b)
        }
        c.drawVertices(Canvas.VertexMode.TRIANGLES, m * 6, rv, 0, null, 0, rc, 0, ridx, 0, (m - 1) * 12, ribbonPaint)
    }

    private fun drawOrb(c: Canvas, o: Orb, len: Int, a: Float) {
        if (a <= 0.01f) return
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            // Oldest to newest, then the ball itself (a gap - e.g. behind the camera - starts it afresh)
            val n0 = min(o.n, len)
            var m = 0
            for (i in 0 until n0) {
                val idx = (o.head - n0 + i + TRAIL * 2) % TRAIL
                if (!project(o.tx[idx], o.ty[idx], o.tz[idx])) { m = 0; continue }
                rpx[m] = px; rpy[m] = py; rps[m] = min(1.15f, 14f / pz); m++
            }
            if (project(o.x, o.y, o.z)) { rpx[m] = px; rpy[m] = py; rps[m] = min(1.15f, 14f / pz); m++ }
            if (m >= 2) {
                ribbon(c, m, o.color, 0.6f, 10f, 0.75f * a, 1.25f)
                val hot = Color.rgb((Color.red(o.color) + 2 * 255) / 3, (Color.green(o.color) + 2 * 255) / 3, (Color.blue(o.color) + 2 * 255) / 3)
                ribbon(c, m, hot, 0.3f, 3.2f, 0.9f * a, 1.8f)
            }
            drawHead(c, o, a)
            return
        }
        val n = min(o.n, len)
        var lx = 0f; var ly = 0f; var ok = false
        for (i in 0 until n) {
            // oldest to newest
            val idx = (o.head - n + i + TRAIL * 2) % TRAIL
            if (!project(o.tx[idx], o.ty[idx], o.tz[idx])) { ok = false; continue }
            if (ok) {
                val k = (i + 1).toFloat() / n
                val s = min(1.15f, 14f / pz)
                trail.color = o.color
                trail.alpha = (230 * k.pow(1.3f) * a).toInt().coerceIn(0, 255)
                trail.strokeWidth = (1.5f + 7f * k) * d * s
                c.drawLine(lx, ly, px, py, trail)
                if (k > 0.6f) { // a hot white middle near the ball
                    trail.color = Color.WHITE
                    trail.alpha = (150 * (k - 0.6f) / 0.4f * a).toInt().coerceIn(0, 255)
                    trail.strokeWidth = (0.8f + 2.5f * k) * d * s
                    c.drawLine(lx, ly, px, py, trail)
                }
            }
            lx = px; ly = py; ok = true
        }
        drawHead(c, o, a)
    }

    /** The ball itself: a soft glow in its colour with a white-hot middle. */
    private fun drawHead(c: Canvas, o: Orb, a: Float) {
        if (!project(o.x, o.y, o.z)) return
        val s = min(1.15f, 14f / pz)
        val pulse = 0.85f + 0.15f * sin(t * 3f + o.phase)
        val r = 30 * d * s * pulse
        o.glow.alpha = (255 * a).toInt().coerceIn(0, 255)
        glowDst.set(px - r, py - r, px + r, py + r)
        c.drawBitmap(glowBmp, null, glowDst, o.glow)
        core.color = Color.argb((255 * a).toInt().coerceIn(0, 255), 255, 255, 255)
        c.drawCircle(px, py, max(1.5f, 5.5f * d * s), core)
    }

    /** Adds one glass block of a tower (only the faces that face the camera). Returns its fog amount. */
    private fun addCube(tw: Tower, k: Int, dim: Float): Float {
        val h = CUBE / 2
        val yb = k * (CUBE + GAP)
        for (i in 0 until 8) {
            val dx = i and 1; val dz = (i shr 1) and 1; val dy = (i shr 2) and 1
            if (!project(tw.x + (dx * 2 - 1) * h, yb + dy * CUBE, tw.z + (dz * 2 - 1) * h)) return 1f
            sx[i] = px; sy[i] = py
        }
        project(tw.x, yb + h, tw.z)
        val f = fog(pz) * dim
        // Now and then a block glows brighter
        val tw0 = max(0f, sin(t * 1.1f + tw.phase * 3f + k * 1.7f)).let { it * it * it * it * it }
        val lift = 1f + tw0 * 0.9f
        if (vtx.size < (vn + 40) * 2) { vtx = vtx.copyOf(vtx.size * 2); vcol = vcol.copyOf(vcol.size * 2) }
        if (lines.size < ln + 80) lines = lines.copyOf(lines.size * 2)
        if (ey > yb + CUBE) face(4, 5, 7, 6, Color.argb((125 * f * lift).toInt().coerceIn(0, 255), 215, 221, 235))
        if (ex > tw.x + h) face(1, 3, 7, 5, side(0.92f, f, lift))
        if (ex < tw.x - h) face(0, 2, 6, 4, side(0.58f, f, lift))
        if (ez > tw.z + h) face(2, 3, 7, 6, side(0.78f, f, lift))
        if (ez < tw.z - h) face(0, 1, 5, 4, side(0.64f, f, lift))
        return f
    }

    private fun side(b: Float, f: Float, lift: Float) =
        Color.argb((95 * f * lift).toInt().coerceIn(0, 255), (175 * b).toInt(), (184 * b).toInt(), (200 * b).toInt())

    private fun face(a: Int, b: Int, c: Int, e: Int, col: Int) {
        val q = intArrayOfQuad; q[0] = a; q[1] = b; q[2] = c; q[3] = a; q[4] = c; q[5] = e
        for (j in 0 until 6) { vtx[vn * 2] = sx[q[j]]; vtx[vn * 2 + 1] = sy[q[j]]; vcol[vn] = col; vn++ }
        // Its edges
        edgeLine(a, b); edgeLine(b, c); edgeLine(c, e); edgeLine(e, a)
    }
    private val intArrayOfQuad = IntArray(6)
    private fun edgeLine(a: Int, b: Int) { lines[ln++] = sx[a]; lines[ln++] = sy[a]; lines[ln++] = sx[b]; lines[ln++] = sy[b] }

    private fun flush(c: Canvas, f: Float, dim: Float) {
        if (vn > 0) {
            fill.color = Color.WHITE
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                c.drawVertices(Canvas.VertexMode.TRIANGLES, vn * 2, vtx, 0, null, 0, vcol, 0, null, 0, 0, fill)
            } else {
                // Older Android: each triangle as a small path
                var v = 0
                while (v < vn) {
                    path.reset(); path.moveTo(vtx[v * 2], vtx[v * 2 + 1]); path.lineTo(vtx[v * 2 + 2], vtx[v * 2 + 3]); path.lineTo(vtx[v * 2 + 4], vtx[v * 2 + 5]); path.close()
                    fill.color = vcol[v]; c.drawPath(path, fill); v += 3
                }
            }
        }
        if (ln > 0 && (quality > 0 || f > 0.5f)) {
            edge.strokeWidth = max(0.6f, 1.1f * d * f)
            edge.color = Color.argb((185 * f).toInt().coerceIn(0, 255), 220, 227, 240)
            c.drawLines(lines, 0, ln, edge)
        }
    }
    private val path = android.graphics.Path()

    companion object {
        /** Trail points kept, and how many a second (a second's worth of trail). */
        private const val TRAIL = 60
        private const val RATE = 60f

        @Volatile private var used = -1f
        @Volatile private var usedAt = 0L

        /** How full the phone's storage is (0..1): looked up once, then again in the background every few minutes. */
        fun storageUsed(): Float {
            val now = android.os.SystemClock.uptimeMillis()
            if (used < 0f) { used = read(); usedAt = now }
            else if (now - usedAt > 10 * 60_000L) { usedAt = now; Bg.thread { used = read() }.start() }
            return used
        }

        private fun read(): Float = try {
            val s = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            ((s.totalBytes - s.availableBytes).toFloat() / s.totalBytes).coerceIn(0f, 1f)
        } catch (_: Throwable) { 0.5f }
    }
}
