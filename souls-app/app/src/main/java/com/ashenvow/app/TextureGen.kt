package com.ashenvow.app

import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Makes the game's textures in code (no image files). One 512×512 image holds three
 * greyscale patterns, one per colour channel:
 *   red   = weathered stone (cracks, pits, blotches)
 *   green = worn metal (brushed streaks, scratches, dents)
 *   blue  = cloth / leather (woven threads, grain)
 * Everything tiles seamlessly.
 */
object TextureGen {
    const val SIZE = 512

    private val rnd = Random(42)
    private val lattice = FloatArray(64 * 64) { rnd.nextFloat() }

    /** Smooth tileable value noise; period = 64 lattice cells / freq. */
    private fun noise(x: Float, y: Float, period: Int, periodY: Int = period): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        fun l(i: Int, j: Int) = lattice[Math.floorMod(j, periodY) * 64 + Math.floorMod(i, period)]
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = l(xi, yi) + (l(xi + 1, yi) - l(xi, yi)) * sx
        val b = l(xi, yi + 1) + (l(xi + 1, yi + 1) - l(xi, yi + 1)) * sx
        return a + (b - a) * sy
    }

    private fun fbm(u: Float, v: Float, base: Int, octaves: Int): Float {
        var sum = 0f; var amp = 0.5f; var f = base
        for (o in 0 until octaves) {
            sum += noise(u * f, v * f, f) * amp
            amp *= 0.5f; f *= 2
            if (f > 64) break
        }
        return sum
    }

    /** Distance to the nearest edge between random cells (tileable): small = on a crack. */
    private val cells = Array(28) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat()) }
    private fun cellEdge(u: Float, v: Float): Float {
        var d1 = 9f; var d2 = 9f
        for (c in cells) for (ox in -1..1) for (oy in -1..1) {
            val dx = u - (c[0] + ox); val dy = v - (c[1] + oy)
            val dd = dx * dx + dy * dy
            if (dd < d1) { d2 = d1; d1 = dd } else if (dd < d2) d2 = dd
        }
        return sqrt(d2) - sqrt(d1)
    }

    fun build(): IntArray {
        val px = IntArray(SIZE * SIZE)
        // Random scratches for the metal
        val scratches = Array(90) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), (rnd.nextFloat() - 0.5f) * 0.6f, 0.03f + rnd.nextFloat() * 0.12f) }
        val metal = FloatArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val u = x.toFloat() / SIZE; val v = y.toFloat() / SIZE
            // Brushed: noise stretched along x, plus broad blotches of wear
            val brushed = 0.2f + noise(u * 4f, v * 64f, 4, 64) * 0.25f + fbm(u, v, 4, 4) * 0.45f
            metal[y * SIZE + x] = brushed
        }
        for (s in scratches) {
            val len = (s[3] * SIZE).toInt()
            for (k in 0 until len) {
                val xx = Math.floorMod((s[0] * SIZE + k).toInt(), SIZE)
                val yy = Math.floorMod((s[1] * SIZE + k * s[2]).toInt(), SIZE)
                metal[yy * SIZE + xx] += 0.35f
                metal[Math.floorMod(yy + 1, SIZE) * SIZE + xx] -= 0.15f
            }
        }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val u = x.toFloat() / SIZE; val v = y.toFloat() / SIZE
            // Stone: blotchy fbm, pitted, with dark cracks along cell edges
            val e = cellEdge(u, v)
            val crack = if (e < 0.007f) 0.55f + e / 0.007f * 0.45f else 1f
            val grain = noise(u * 64f, v * 64f, 64)
            val pits = if (grain > 0.9f) 0.8f else 1f
            val stone = (0.18f + fbm(u, v, 4, 5) * 0.72f + grain * 0.12f) * crack * pits
            // Cloth: woven threads with a little grain
            val wx = sin(u * SIZE * 0.55f); val wy = sin(v * SIZE * 0.55f)
            val weave = 0.6f + 0.25f * (if ((x / 3 + y / 3) % 2 == 0) wx * wx else wy * wy) + fbm(u, v, 8, 3) * 0.3f - 0.15f
            val r = (stone.coerceIn(0f, 1f) * 255).toInt()
            val g = (metal[y * SIZE + x].coerceIn(0f, 1f) * 255).toInt()
            val b = (weave.coerceIn(0f, 1f) * 255).toInt()
            px[y * SIZE + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return px
    }
}
