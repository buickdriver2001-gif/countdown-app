package com.torquestreet.app.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The world's textures, painted by code (every one repeats without seams): asphalt with lane lines, worn
 * wheel tracks, cracks and puddle spots; sidewalk slabs; grass, rock and sand; four building faces whose
 * windows light up at night; trees; steel; tunnel tiles; and the far skyline.
 *
 * Each layer is [size] x [size] RGBA bytes (not premultiplied).
 */
object TexGen {
    fun layer(id: Int, scene: Scene, size: Int): ByteArray {
        val out = ByteArray(size * size * 4)
        val p = Painter(out, size)
        when (id) {
            0 -> asphalt(p)
            1 -> concrete(p)
            2 -> grass(p)
            3 -> rock(p)
            4 -> sand(p)
            5, 6, 7, 8 -> facade(p, id - 5)
            9 -> foliage(p)
            10 -> metal(p)
            11 -> tiles(p)
            12 -> white(p)
            13 -> skyline(p, scene)
        }
        return out
    }

    class Painter(val b: ByteArray, val n: Int) {
        fun set(x: Int, y: Int, r: Float, g: Float, bl: Float, a: Float) {
            val o = (y * n + x) * 4
            b[o] = (clamp(r, 0f, 1f) * 255f + 0.5f).toInt().toByte()
            b[o + 1] = (clamp(g, 0f, 1f) * 255f + 0.5f).toInt().toByte()
            b[o + 2] = (clamp(bl, 0f, 1f) * 255f + 0.5f).toInt().toByte()
            b[o + 3] = (clamp(a, 0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
        fun r(x: Int, y: Int) = (b[(y * n + x) * 4].toInt() and 255) / 255f
        fun g(x: Int, y: Int) = (b[(y * n + x) * 4 + 1].toInt() and 255) / 255f
        fun bl(x: Int, y: Int) = (b[(y * n + x) * 4 + 2].toInt() and 255) / 255f
        fun a(x: Int, y: Int) = (b[(y * n + x) * 4 + 3].toInt() and 255) / 255f
    }

    /** A repeatable random number for a pixel (0..1). */
    private fun hash(x: Int, y: Int, s: Int): Float {
        var h = x * 374761393 + y * 668265263 + s * 982451653
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffffff) / 16777215f
    }

    private fun asphalt(p: Painter) {
        val n = p.n
        val ns = Noise(3)
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val u = x.toFloat() / n; val v = y.toFloat() / n
            var base = 0.165f + 0.055f * ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 4) +
                0.035f * ns.tile(x / (4f * sc), y / (4f * sc), (n / (4f * sc)).toInt())
            val hr = hash(x, y, 1)
            if (hr < 0.05f) base += 0.08f * (hr / 0.05f) else if (hr > 0.975f) base -= 0.05f
            // Wheel tracks: darker, smoother bands in each lane
            var wear = 0f
            for (c in floatArrayOf(0.125f, 0.375f, 0.625f, 0.875f)) for (o in floatArrayOf(-0.042f, 0.042f)) {
                val q = (u - c - o) / 0.02f; wear += exp(-q * q)
            }
            base -= 0.028f * wear
            // Fine cracks, here and there
            val cr = 1f - abs(ns.tile(x / (16f * sc), y / (16f * sc), (n / (16f * sc)).toInt()) * 2f - 1f)
            val patch = smooth(0.55f, 0.7f, ns.tile(x / (64f * sc) + 40f, y / (64f * sc), (n / (64f * sc)).toInt()))
            if (cr > 0.975f) base -= 0.045f * (cr - 0.975f) / 0.025f * patch
            var r = base * 0.97f; var g = base; var b = base * 1.05f
            // Lane lines (paint, worn in places)
            val worn = 0.7f + 0.3f * ns.tile(x / (8f * sc), y / (8f * sc), (n / (8f * sc)).toInt())
            var paint = 0f; var pr = 0.92f; var pg = 0.92f; var pb = 0.9f
            fun band(c: Float, w: Float) = smooth(w + 0.0025f, w, abs(u - c))
            paint = max(paint, band(0.032f, 0.0075f)); paint = max(paint, band(0.968f, 0.0075f))
            if (v < 0.375f) { paint = max(paint, band(0.25f, 0.005f)); paint = max(paint, band(0.75f, 0.005f)) }
            val yel = max(band(0.4915f, 0.0035f), band(0.5085f, 0.0035f))
            if (yel > paint) { paint = yel; pr = 0.86f; pg = 0.66f; pb = 0.16f }
            paint *= worn
            r = lerp(r, pr, paint); g = lerp(g, pg, paint); b = lerp(b, pb, paint)
            // Alpha: where water gathers when it rains (low spots and the wheel tracks)
            val puddle = smooth(0.56f, 0.7f, ns.tileFbm(x / (128f * sc), y / (128f * sc), (n / (128f * sc)).toInt(), 3)) * 0.85f + wear * 0.25f
            p.set(x, y, r, g, b, clamp(puddle, 0f, 1f))
        }
    }

    private fun concrete(p: Painter) {
        val n = p.n
        val ns = Noise(5)
        val sc = n / 512f
        val slab = n / 2
        for (y in 0 until n) for (x in 0 until n) {
            var c = 0.6f + 0.08f * ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 4) + 0.04f * (hash(x, y, 2) - 0.5f)
            val stain = ns.tileFbm(x / (128f * sc), y / (128f * sc), (n / (128f * sc)).toInt(), 3)
            c -= 0.08f * smooth(0.55f, 0.75f, stain)
            val gx = min(x % slab, slab - x % slab); val gy = min(y % slab, slab - y % slab)
            if (min(gx, gy) < 2 * sc) c *= 0.62f
            p.set(x, y, c, c * 0.99f, c * 0.96f, 1f)
        }
    }

    private fun grass(p: Painter) {
        val n = p.n
        val ns = Noise(7)
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val big = ns.tileFbm(x / (128f * sc), y / (128f * sc), (n / (128f * sc)).toInt(), 4)
            val blade = ns.tileXY(x / (2f * sc), y / (8f * sc), (n / (2f * sc)).toInt(), (n / (8f * sc)).toInt())
            val dry = smooth(0.5f, 0.75f, big)
            val k = 0.75f + 0.35f * blade
            p.set(x, y, lerp(0.2f, 0.42f, dry) * k, lerp(0.33f, 0.38f, dry) * k, lerp(0.12f, 0.18f, dry) * k, 1f)
        }
    }

    private fun rock(p: Painter) {
        val n = p.n
        val ns = Noise(11)
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val ridged = 1f - abs(ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 5) * 2f - 1f)
            val strata = 0.5f + 0.5f * sin(y * TWO_PI_F * 7f / n + ns.tile(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt()) * 6f)
            var c = 0.34f + 0.2f * ridged + 0.06f * strata + 0.05f * (hash(x, y, 3) - 0.5f)
            if (ridged > 0.93f) c *= 0.7f
            p.set(x, y, c * 1.04f, c * 0.98f, c * 0.9f, 1f)
        }
    }

    private fun sand(p: Painter) {
        val n = p.n
        val ns = Noise(13)
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val rip = 0.5f + 0.5f * sin(TWO_PI_F * (3f * x + 10f * y) / n + ns.tile(x / (32f * sc), y / (32f * sc), (n / (32f * sc)).toInt()) * 5f)
            val c = 0.68f + 0.06f * rip + 0.06f * (hash(x, y, 4) - 0.5f) + 0.05f * ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 3)
            p.set(x, y, c, c * 0.9f, c * 0.7f, 1f)
        }
    }

    /**
     * A building face, 16 m square: 4 floors of 4 window bays. Alpha = how brightly each window is lit at night
     * (some dark, some bright, a few dim).
     */
    private fun facade(p: Painter, style: Int) {
        val n = p.n
        val ns = Noise(17 + style)
        val bay = n / 8
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val bx = x / bay; val by = y / bay
            val lx = (x % bay).toFloat() / bay; val ly = (y % bay).toFloat() / bay
            val lit = hash(bx, by, 40 + style)
            val litA = if (lit < 0.3f) 0.6f + lit else if (lit < 0.38f) 0.3f else 0f
            val grain = 0.04f * (hash(x, y, 5) - 0.5f)
            var r: Float; var g: Float; var b: Float; var a = 0f
            when (style) {
                0 -> { // glass tower: glass all over, thin frames, dark band at each floor
                    val refl = 0.75f + 0.5f * ns.tileXY(x / (128f * sc), y / (256f * sc), (n / (128f * sc)).toInt(), (n / (256f * sc)).toInt())
                    r = 0.2f * refl; g = 0.32f * refl; b = 0.4f * refl
                    val mull = (x % (bay / 2)) < 2 * sc
                    val slabBand = ly > 0.9f
                    if (slabBand) { r = 0.1f; g = 0.12f; b = 0.14f }
                    else if (mull) { r = 0.55f; g = 0.58f; b = 0.6f }
                    else a = litA
                }
                1 -> { // brick with framed windows
                    val bw = 8 * sc; val bh = 4 * sc
                    val row = floor(y / bh).toInt()
                    val off = if (row % 2 == 0) 0f else bw / 2
                    val mortar = (y % bh) < 1.2f * sc || ((x + off) % bw) < 1.2f * sc
                    val tone = 0.85f + 0.3f * hash(floor((x + off) / bw).toInt(), row, 6)
                    r = 0.5f * tone; g = 0.22f * tone; b = 0.16f * tone
                    if (mortar) { r = 0.6f; g = 0.57f; b = 0.52f }
                    val inX = lx in 0.24f..0.76f; val inY = ly in 0.18f..0.78f
                    val frX = lx in 0.21f..0.79f; val frY = ly in 0.15f..0.81f
                    if (frX && frY) { r = 0.86f; g = 0.85f; b = 0.82f }
                    if (inX && inY) {
                        r = 0.1f; g = 0.12f; b = 0.16f
                        if (abs(lx - 0.5f) < 0.012f || abs(ly - 0.48f) < 0.012f) { r = 0.8f; g = 0.8f; b = 0.78f } else a = litA
                    }
                }
                2 -> { // concrete offices: light bands, a ribbon of windows on each floor
                    val c = 0.66f + 0.06f * ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 3)
                    r = c; g = c * 0.98f; b = c * 0.94f
                    if (ly in 0.3f..0.75f) {
                        if ((x % (bay / 4)) < 2.5f * sc) { r = 0.3f; g = 0.31f; b = 0.33f }
                        else { r = 0.14f; g = 0.18f; b = 0.22f; a = litA }
                    }
                }
                else -> { // dark modern: dark panels, tall narrow windows
                    val c = 0.24f + 0.05f * ns.tileFbm(x / (64f * sc), y / (64f * sc), (n / (64f * sc)).toInt(), 3)
                    r = c; g = c * 1.03f; b = c * 1.1f
                    val inW = (lx in 0.12f..0.36f || lx in 0.62f..0.86f) && ly in 0.1f..0.88f
                    if ((x % (bay / 2)) < 1.5f * sc) { r = 0.32f; g = 0.34f; b = 0.38f }
                    if (inW) { r = 0.08f; g = 0.12f; b = 0.17f; a = litA }
                }
            }
            p.set(x, y, r + grain, g + grain, b + grain, a)
        }
    }

    /** Four tree pictures, one in each quarter: pine, palm, broad-leafed tree, bush. Alpha cuts out the shape. */
    private fun foliage(p: Painter) {
        val n = p.n
        val q = n / 2
        val ns = Noise(23)
        val sc = q / 256f
        for (y in 0 until n) for (x in 0 until n) {
            val kind = (x / q) + 2 * (y / q)
            val u = (x % q).toFloat() / q; val v = (y % q).toFloat() / q // v = 0 at the top
            val hgt = 1f - v
            var a = 0f; var r = 0.12f; var g = 0.22f; var b = 0.1f
            val leaf = ns.at(x / (3f * sc), y / (3f * sc))
            when (kind) {
                0 -> { // pine: tiers narrowing to a point, trunk at the foot
                    val trunk = abs(u - 0.5f) < 0.03f && hgt < 0.2f
                    val tier = (hgt * 6f) % 1f
                    val wdt = (1f - hgt) * 0.46f * (0.75f + 0.25f * (1f - tier)) + 0.02f
                    if (hgt > 0.08f && abs(u - 0.5f) < wdt * (0.85f + 0.3f * leaf)) {
                        a = 1f; val sh = 0.55f + 0.45f * (1f - tier) + 0.2f * leaf
                        r = 0.07f * sh; g = 0.2f * sh; b = 0.1f * sh
                    } else if (trunk) { a = 1f; r = 0.25f; g = 0.16f; b = 0.1f }
                }
                1 -> { // palm: a slim curved trunk, fronds fanning from the top
                    val tx = 0.5f + 0.06f * sin(hgt * 3f)
                    if (hgt < 0.72f && abs(u - tx) < 0.022f + 0.01f * (1f - hgt)) {
                        a = 1f; val ring = 0.8f + 0.2f * sin(hgt * 160f); r = 0.42f * ring; g = 0.32f * ring; b = 0.2f * ring
                    }
                    val cx = 0.5f + 0.06f * sin(0.72f * 3f); val cy = 0.72f
                    val dx = u - cx; val dy = hgt - cy
                    val ang = kotlin.math.atan2(dy, dx)
                    val dist = hypot(dx, dy)
                    for (f in 0 until 8) {
                        val fa = f * 0.785f + 0.2f
                        var da = wrapAngle(ang - fa)
                        // Fronds droop: their direction bends down with distance
                        da = wrapAngle(ang - (fa - dist * 1.6f * kotlin.math.cos(fa)))
                        if (abs(da) < 0.16f * (1f - dist / 0.42f) && dist < 0.42f && leaf > 0.2f) {
                            a = 1f; val sh = 0.6f + 0.4f * leaf; r = 0.16f * sh; g = 0.38f * sh; b = 0.14f * sh
                        }
                    }
                }
                2 -> { // broad-leafed: a round, lumpy crown on a trunk
                    val cx = u - 0.5f; val cy = hgt - 0.62f
                    val rad = 0.36f * (0.85f + 0.3f * ns.at(x / (18f * sc), y / (18f * sc)))
                    if (cx * cx + cy * cy * 1.3f < rad * rad && leaf > 0.15f) {
                        a = 1f; val sh = 0.55f + 0.35f * (cy + 0.4f) + 0.25f * leaf; r = 0.14f * sh; g = 0.3f * sh; b = 0.1f * sh
                    } else if (abs(u - 0.5f) < 0.035f && hgt < 0.4f) { a = 1f; r = 0.28f; g = 0.2f; b = 0.12f }
                }
                else -> { // bush
                    val cx = u - 0.5f; val cy = hgt - 0.28f
                    val rad = 0.42f * (0.8f + 0.35f * ns.at(x / (14f * sc), y / (14f * sc)))
                    if (cx * cx * 0.8f + cy * cy * 2.2f < rad * rad && hgt > 0.01f && leaf > 0.12f) {
                        a = 1f; val sh = 0.6f + 0.6f * cy + 0.2f * leaf; r = 0.16f * sh; g = 0.3f * sh; b = 0.12f * sh
                    }
                }
            }
            // Cut-out pixels keep a leafy colour, so the edges don't go pale when the picture is shrunk
            if (a < 0.5f) { r = 0.1f; g = 0.18f; b = 0.09f; a = 0f }
            p.set(x, y, r, g, b, a)
        }
    }

    private fun metal(p: Painter) {
        val n = p.n
        val ns = Noise(29)
        val sc = n / 512f
        for (y in 0 until n) for (x in 0 until n) {
            val c = 0.62f + 0.08f * ns.tileXY(x / (64f * sc), y / (2f * sc), (n / (64f * sc)).toInt(), (n / (2f * sc)).toInt()) + 0.03f * (hash(x, y, 7) - 0.5f)
            p.set(x, y, c, c, c * 1.02f, 1f)
        }
    }

    private fun tiles(p: Painter) {
        val n = p.n
        val ns = Noise(31)
        val t = n / 16
        for (y in 0 until n) for (x in 0 until n) {
            var c = 0.8f + 0.05f * hash(x / t, y / t, 8) + 0.02f * (hash(x, y, 9) - 0.5f)
            if (x % t < 2 || y % t < 2) c = 0.45f
            c -= 0.25f * smooth(0.5f, 0.95f, ns.tileFbm(x / 64f, y / 64f, n / 64, 3)) * 0.6f
            p.set(x, y, c, c * 0.98f, c * 0.93f, 1f)
        }
    }

    private fun white(p: Painter) {
        val n = p.n
        for (y in 0 until n) for (x in 0 until n) { val c = 0.93f + 0.04f * (hash(x, y, 10) - 0.5f); p.set(x, y, c, c, c, 1f) }
    }

    /**
     * The far horizon (wraps around): alpha = what's solid. City: towers, G = lit windows. Coast: hills with a
     * few lights. Mountains: ridges, R = snow on the peaks.
     */
    private fun skyline(p: Painter, scene: Scene) {
        val n = p.n
        val ns = Noise(37)
        val top = FloatArray(n)
        val win = BooleanArray(n)
        when (scene) {
            Scene.CITY -> {
                var x = 0
                val r = java.util.Random(5)
                while (x < n) {
                    val w = 6 + r.nextInt(26)
                    val h = 0.15f + r.nextFloat().let { it * it } * 0.75f
                    for (i in x until min(n, x + w)) { top[i] = h; win[i] = true }
                    x += w + r.nextInt(3)
                }
            }
            Scene.COAST -> for (i in 0 until n) top[i] = 0.12f + 0.3f * ns.tileFbm(i / 64f, 0.5f, n / 64, 4)
            Scene.MOUNTAIN -> for (i in 0 until n) {
                val rr = 1f - abs(ns.tileFbm(i / 64f, 3.3f, n / 64, 5) * 2f - 1f)
                top[i] = 0.18f + 0.7f * rr * rr
            }
        }
        for (y in 0 until n) for (x in 0 until n) {
            val hgt = 1f - y.toFloat() / n
            val solid = hgt < top[x]
            var g = 0f; var r = 0f
            if (solid) {
                if (scene == Scene.CITY && win[x] && (x % 3 == 1) && (y % 4 == 1) && hash(x / 3, y / 4, 11) < 0.35f) g = 0.6f + 0.4f * hash(x, y, 12)
                if (scene == Scene.COAST && hgt < 0.06f && hash(x, y, 13) < 0.02f) g = 1f
                if (scene == Scene.MOUNTAIN && hgt > top[x] - 0.12f && top[x] > 0.55f) r = 0.9f
            }
            p.set(x, y, r, g, 0f, if (solid) 1f else 0f)
        }
    }
}
