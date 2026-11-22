package com.torquestreet.app.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

enum class Scene { CITY, COAST, MOUNTAIN }

/** What the menu shows about a track. */
class TrackInfo(val id: Int, val name: String, val blurb: String, val scene: Scene, val laps: Int)

/**
 * A race track: a closed loop sampled every [STEP] metres. Each sample has its centre point, its direction
 * (forward, and right = forward x up), slope, curvature (positive = bending right), half-width and banking.
 *
 * Positions on the track are given as (s, d): s metres along the loop, d metres to the right of the centre line.
 */
class Track(val info: TrackInfo, pts: FloatArray /* x, z, y triples along the loop, about 1 m apart */, halfWidth: Float) {
    val n: Int
    val length: Float
    val cx: FloatArray; val cy: FloatArray; val cz: FloatArray
    /** Forward direction (horizontal, unit length). Right is (-tz, 0, tx). */
    val tx: FloatArray; val tz: FloatArray
    val slope: FloatArray
    /** Curvature in 1/m, smoothed a little (positive: bending right). */
    val kappa: FloatArray
    val hw: FloatArray
    val bank: FloatArray
    /** Distance from the centre to the hard edge on each side (curb, rail or wall). */
    val wall: FloatArray
    val tunnel: BooleanArray
    val bridge: BooleanArray
    val scene get() = info.scene

    // Finding the nearest part of the track: samples sorted into 40 m squares (set up in init, so declared first)
    private val cell = 40f
    private var gx0 = 0f; private var gz0 = 0f; private var gw = 0; private var gh = 0
    private lateinit var grid: Array<IntList?>

    init {
        // Resample the dense polyline every STEP metres
        val m = pts.size / 3
        val cum = FloatArray(m + 1)
        for (i in 0 until m) {
            val j = (i + 1) % m
            cum[i + 1] = cum[i] + hypot(pts[j * 3] - pts[i * 3], pts[j * 3 + 1] - pts[i * 3 + 1])
        }
        n = (cum[m] / STEP).toInt()
        length = n * STEP
        cx = FloatArray(n); cy = FloatArray(n); cz = FloatArray(n)
        var seg = 0
        for (k in 0 until n) {
            val s = k * cum[m] / n
            while (seg < m - 1 && cum[seg + 1] < s) seg++
            val j = (seg + 1) % m
            val f = ((s - cum[seg]) / (cum[seg + 1] - cum[seg]).coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
            cx[k] = lerp(pts[seg * 3], pts[j * 3], f); cz[k] = lerp(pts[seg * 3 + 1], pts[j * 3 + 1], f); cy[k] = lerp(pts[seg * 3 + 2], pts[j * 3 + 2], f)
        }
        // Gentle on the elevation: smooth it so the slopes are never sudden
        repeat(6) { smoothRing(cy, 4) }
        tx = FloatArray(n); tz = FloatArray(n); slope = FloatArray(n)
        for (k in 0 until n) {
            val a = (k - 1 + n) % n; val b = (k + 1) % n
            val dx = cx[b] - cx[a]; val dz = cz[b] - cz[a]; val l = hypot(dx, dz).coerceAtLeast(1e-4f)
            tx[k] = dx / l; tz[k] = dz / l
            slope[k] = (cy[b] - cy[a]) / (2 * STEP)
        }
        val kr = FloatArray(n)
        for (k in 0 until n) {
            val a = (k - 2 + n) % n; val b = (k + 2) % n
            kr[k] = wrapAngle(atan2(tz[b], tx[b]) - atan2(tz[a], tx[a])) / (4 * STEP)
        }
        kappa = kr.copyOf(); repeat(3) { smoothRing(kappa, 3) }
        hw = FloatArray(n) { halfWidth }
        bank = FloatArray(n) { if (info.scene == Scene.CITY) 0f else clamp(kappa[it] * 7f, -0.07f, 0.07f) }
        wall = FloatArray(n) { if (info.scene == Scene.CITY) halfWidth else halfWidth + 1.1f }
        tunnel = BooleanArray(n); bridge = BooleanArray(n)
        buildGrid()
    }
    private fun smoothRing(a: FloatArray, r: Int) {
        val c = a.copyOf()
        for (k in a.indices) {
            var s = 0f
            for (o in -r..r) s += c[(k + o + a.size) % a.size]
            a[k] = s / (2 * r + 1)
        }
    }

    fun wrap(s: Float): Float { var x = s % length; if (x < 0) x += length; return x }
    fun index(s: Float): Int = (wrap(s) / STEP).toInt().coerceIn(0, n - 1)
    fun idx(i: Int) = ((i % n) + n) % n

    /** World position of (s, d), [h] metres above the surface, into [out] (x, y, z). Includes banking. */
    fun pos(s: Float, d: Float, h: Float, out: FloatArray) {
        val w = wrap(s) / STEP
        val i = floor(w).toInt().coerceIn(0, n - 1); val j = (i + 1) % n; val f = w - i
        val x = lerp(cx[i], cx[j], f); val y = lerp(cy[i], cy[j], f); val z = lerp(cz[i], cz[j], f)
        var fx = lerp(tx[i], tx[j], f); var fz = lerp(tz[i], tz[j], f)
        val l = hypot(fx, fz).coerceAtLeast(1e-4f); fx /= l; fz /= l
        val b = lerp(bank[i], bank[j], f)
        out[0] = x - fz * d; out[1] = y - d * b + h; out[2] = z + fx * d
    }

    /** Heading of the track at [s] as a world yaw (see M4.model). */
    fun yaw(s: Float): Float {
        val w = wrap(s) / STEP
        val i = floor(w).toInt().coerceIn(0, n - 1); val j = (i + 1) % n; val f = w - i
        return atan2(lerp(tx[i], tx[j], f), lerp(tz[i], tz[j], f))
    }

    fun kappaAt(s: Float): Float { val w = wrap(s) / STEP; val i = floor(w).toInt().coerceIn(0, n - 1); return lerp(kappa[i], kappa[(i + 1) % n], w - i) }
    fun slopeAt(s: Float): Float { val w = wrap(s) / STEP; val i = floor(w).toInt().coerceIn(0, n - 1); return lerp(slope[i], slope[(i + 1) % n], w - i) }
    fun bankAt(s: Float): Float { val w = wrap(s) / STEP; val i = floor(w).toInt().coerceIn(0, n - 1); return lerp(bank[i], bank[(i + 1) % n], w - i) }
    fun wallAt(s: Float): Float = wall[index(s)]
    fun hwAt(s: Float): Float = hw[index(s)]
    /** Signed distance along the loop from [a] to [b], in -length/2..length/2. */
    fun delta(a: Float, b: Float): Float { var x = (b - a) % length; if (x > length / 2) x -= length; if (x < -length / 2) x += length; return x }

    // ------------------------------------------------------------------ finding the nearest part of the track

    val minX get() = gx0; val minZ get() = gz0
    val maxX get() = gx0 + gw * cell; val maxZ get() = gz0 + gh * cell

    private fun buildGrid() {
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (k in 0 until n) { x0 = minOf(x0, cx[k]); x1 = maxOf(x1, cx[k]); z0 = minOf(z0, cz[k]); z1 = maxOf(z1, cz[k]) }
        gx0 = x0 - 400f; gz0 = z0 - 400f
        gw = ((x1 - gx0 + 400f) / cell).toInt() + 1; gh = ((z1 - gz0 + 400f) / cell).toInt() + 1
        grid = arrayOfNulls(gw * gh)
        for (k in 0 until n) {
            val ci = ((cx[k] - gx0) / cell).toInt(); val cj = ((cz[k] - gz0) / cell).toInt()
            val id = cj * gw + ci
            (grid[id] ?: IntList(16).also { grid[id] = it }).add(k)
        }
    }

    /** Last result of [nearest]: the sample index, the signed lateral offset (right +) and distance. */
    var nearI = 0; var nearD = 0f; var nearDist = 0f

    /**
     * The nearest track sample to (x, z) within [maxR] metres (optionally skipping samples within [skipRange] of
     * sample [skipAround] along the loop). Returns false if there's none that close.
     */
    fun nearest(x: Float, z: Float, maxR: Float, skipAround: Int = -1, skipRange: Int = 0): Boolean {
        val ci = ((x - gx0) / cell).toInt(); val cj = ((z - gz0) / cell).toInt()
        val r = (maxR / cell).toInt() + 1
        var best = maxR * maxR; var bi = -1
        for (j in cj - r..cj + r) {
            if (j < 0 || j >= gh) continue
            for (i in ci - r..ci + r) {
                if (i < 0 || i >= gw) continue
                val l = grid[j * gw + i] ?: continue
                for (q in 0 until l.n) {
                    val k = l.a[q]
                    if (skipAround >= 0) { var dk = abs(k - skipAround); if (dk > n / 2) dk = n - dk; if (dk <= skipRange) continue }
                    val dx = x - cx[k]; val dz = z - cz[k]
                    val dd = dx * dx + dz * dz
                    if (dd < best) { best = dd; bi = k }
                }
            }
        }
        if (bi < 0) return false
        nearI = bi; nearDist = sqrt(best)
        nearD = (x - cx[bi]) * (-tz[bi]) + (z - cz[bi]) * tx[bi]
        return true
    }

    companion object {
        const val STEP = 2f

        val all = listOf(
            TrackInfo(0, "Downtown Loop", "Long straights and hard corners between the towers", Scene.CITY, 3),
            TrackInfo(1, "Harbor Coast", "Fast, sweeping road above the sea and over the bay", Scene.COAST, 2),
            TrackInfo(2, "Ridge Pass", "Climbs, hairpins and a tunnel through the ridge", Scene.MOUNTAIN, 2))

        fun build(id: Int): Track = when (id) {
            0 -> city()
            1 -> coast()
            else -> mountain()
        }

        private fun city(): Track {
            // City blocks: straight streets joined by rounded corners
            val corners = floatArrayOf(0f, 0f, 720f, 0f, 720f, 260f, 520f, 260f, 520f, 520f, 180f, 520f, 180f, 360f, -140f, 360f, -140f, 0f)
            val radii = floatArrayOf(0f, 55f, 50f, 45f, 60f, 50f, 45f, 55f, 60f)
            val flat = fillet(corners, radii)
            // Gentle rises and dips along the streets
            val m = flat.size / 2
            val out = FloatArray(m * 3)
            var s = 0f
            for (i in 0 until m) {
                if (i > 0) s += hypot(flat[i * 2] - flat[i * 2 - 2], flat[i * 2 + 1] - flat[i * 2 - 1])
                out[i * 3] = flat[i * 2]; out[i * 3 + 1] = flat[i * 2 + 1]
            }
            val total = s
            s = 0f
            for (i in 0 until m) {
                if (i > 0) s += hypot(flat[i * 2] - flat[i * 2 - 2], flat[i * 2 + 1] - flat[i * 2 - 1])
                val u = s / total * TWO_PI_F
                out[i * 3 + 2] = 2f + 2.2f * sin(u * 2f + 0.6f) + 1.1f * sin(u * 5f + 2.0f)
            }
            return Track(all[0], out, 8f)
        }

        private fun coast(): Track {
            val c = floatArrayOf(
                0f, -20f, 12f, 300f, -50f, 11f, 600f, -30f, 12f, 900f, -60f, 13f, 1150f, -40f, 13f, 1400f, -50f, 13f,
                1650f, -20f, 14f, 1850f, 100f, 20f, 1900f, 300f, 30f, 1750f, 480f, 40f, 1500f, 560f, 46f, 1200f, 520f, 44f,
                950f, 600f, 48f, 700f, 650f, 50f, 450f, 560f, 44f, 200f, 620f, 40f, -50f, 560f, 34f, -250f, 420f, 26f,
                -330f, 220f, 18f, -250f, 60f, 13f)
            return Track(all[1], catmull(c), 7.5f)
        }

        private fun mountain(): Track {
            val c = floatArrayOf(
                0f, 0f, 140f, 260f, 40f, 146f, 480f, -20f, 156f, 680f, 90f, 170f, 640f, 300f, 186f, 430f, 330f, 196f,
                380f, 500f, 206f, 560f, 620f, 216f, 800f, 560f, 228f, 980f, 660f, 236f, 1000f, 880f, 240f, 800f, 1000f, 234f,
                540f, 960f, 224f, 330f, 1040f, 214f, 120f, 960f, 200f, -60f, 800f, 186f, -120f, 560f, 170f, -60f, 330f, 158f,
                -120f, 140f, 148f)
            val t = Track(all[2], catmull(c), 6.5f)
            // A tunnel through the ridge
            val a = (t.n * 0.40f).toInt(); val b = (t.n * 0.47f).toInt()
            for (k in a..b) t.tunnel[k] = true
            return t
        }

        /** Straight lines between corner points, each corner rounded with its radius (a closed loop), about 1 m apart. */
        fun fillet(c: FloatArray, r: FloatArray): FloatArray {
            val m = c.size / 2
            val out = FloatList(8192)
            for (i in 0 until m) {
                val p = (i - 1 + m) % m; val q = (i + 1) % m
                val px = c[p * 2]; val pz = c[p * 2 + 1]; val x = c[i * 2]; val z = c[i * 2 + 1]; val nx = c[q * 2]; val nz = c[q * 2 + 1]
                var ax = x - px; var az = z - pz; var l = hypot(ax, az); ax /= l; az /= l
                var bx = nx - x; var bz = nz - z; l = hypot(bx, bz); bx /= l; bz /= l
                val cross = ax * bz - az * bx; val dot = (ax * bx + az * bz).coerceIn(-1f, 1f)
                val ang = kotlin.math.acos(dot)
                // The straight from the previous corner's end to this corner's start
                if (ang < 0.01f || r[i] <= 0f) { out.add(x); out.add(z); continue }
                val td = r[i] * tan(ang / 2)
                val sx = x - ax * td; val sz = z - az * td
                // Arc centre: to the side the road turns
                val side = if (cross > 0) 1f else -1f
                val ox = sx + (-az) * side * r[i]; val oz = sz + ax * side * r[i]
                val a0 = atan2(sz - oz, sx - ox)
                val steps = (r[i] * ang).toInt().coerceAtLeast(2)
                for (k in 0..steps) {
                    val a = a0 + side * ang * k / steps
                    out.add(ox + cos(a) * r[i]); out.add(oz + sin(a) * r[i])
                }
            }
            // Fill the straights so points are about a metre apart
            val raw = out.toArray()
            val res = FloatList(raw.size * 4)
            val rm = raw.size / 2
            for (i in 0 until rm) {
                val j = (i + 1) % rm
                val x0 = raw[i * 2]; val z0 = raw[i * 2 + 1]; val x1 = raw[j * 2]; val z1 = raw[j * 2 + 1]
                val len = hypot(x1 - x0, z1 - z0)
                val k = len.toInt().coerceAtLeast(1)
                for (q in 0 until k) { res.add(lerp(x0, x1, q.toFloat() / k)); res.add(lerp(z0, z1, q.toFloat() / k)) }
            }
            return res.toArray()
        }

        /** A smooth closed curve through control points (x, z, y), about 1 m apart (centripetal Catmull-Rom). */
        fun catmull(c: FloatArray): FloatArray {
            val m = c.size / 3
            val out = FloatList(20000)
            for (i in 0 until m) {
                val p0 = (i - 1 + m) % m; val p1 = i; val p2 = (i + 1) % m; val p3 = (i + 2) % m
                val len = hypot(c[p2 * 3] - c[p1 * 3], c[p2 * 3 + 1] - c[p1 * 3 + 1])
                val steps = len.toInt().coerceAtLeast(4)
                // Centripetal parameterisation (no loops or cusps)
                fun dt(a: Int, b: Int) = sqrt(hypot(c[b * 3] - c[a * 3], c[b * 3 + 1] - c[a * 3 + 1])).coerceAtLeast(1e-3f)
                val t0 = 0f; val t1 = t0 + dt(p0, p1); val t2 = t1 + dt(p1, p2); val t3 = t2 + dt(p2, p3)
                for (k in 0 until steps) {
                    val t = lerp(t1, t2, k.toFloat() / steps)
                    for (dim in 0 until 3) {
                        val P0 = c[p0 * 3 + dim]; val P1 = c[p1 * 3 + dim]; val P2 = c[p2 * 3 + dim]; val P3 = c[p3 * 3 + dim]
                        val a1 = (t1 - t) / (t1 - t0) * P0 + (t - t0) / (t1 - t0) * P1
                        val a2 = (t2 - t) / (t2 - t1) * P1 + (t - t1) / (t2 - t1) * P2
                        val a3 = (t3 - t) / (t3 - t2) * P2 + (t - t2) / (t3 - t2) * P3
                        val b1 = (t2 - t) / (t2 - t0) * a1 + (t - t0) / (t2 - t0) * a2
                        val b2 = (t3 - t) / (t3 - t1) * a2 + (t - t1) / (t3 - t1) * a3
                        out.add((t2 - t) / (t2 - t1) * b1 + (t - t1) / (t2 - t1) * b2)
                    }
                }
            }
            return out.toArray()
        }
    }
}

/** Pitch for a slope (rise over run), for M4.model (whose positive pitch dips the nose). */
fun pitchFor(slope: Float) = -atan(slope)
