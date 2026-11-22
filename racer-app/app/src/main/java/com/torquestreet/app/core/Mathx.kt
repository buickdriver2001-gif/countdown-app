package com.torquestreet.app.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

// Small maths helpers for the game. Plain Kotlin (no Android), so the whole core can be tested on a computer.

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun smooth(e0: Float, e1: Float, x: Float): Float { val t = clamp((x - e0) / (e1 - e0), 0f, 1f); return t * t * (3f - 2f * t) }
/** Moves [v] towards [target] by at most [step]. */
fun approach(v: Float, target: Float, step: Float) = if (v < target) minOf(v + step, target) else maxOf(v - step, target)
/** Angle wrapped to -PI..PI. */
fun wrapAngle(a: Float): Float { var x = a; while (x > PI_F) x -= TWO_PI_F; while (x < -PI_F) x += TWO_PI_F; return x }

const val PI_F = 3.1415927f
const val TWO_PI_F = 6.2831855f

/** 4x4 matrices, column-major (as OpenGL expects). */
object M4 {
    fun identity(m: FloatArray) { for (i in 0 until 16) m[i] = 0f; m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f }

    fun perspective(m: FloatArray, fovyDeg: Float, aspect: Float, near: Float, far: Float) {
        val f = 1f / tan(fovyDeg * PI_F / 360f)
        for (i in 0 until 16) m[i] = 0f
        m[0] = f / aspect; m[5] = f
        m[10] = (far + near) / (near - far); m[11] = -1f
        m[14] = 2f * far * near / (near - far)
    }

    fun lookAt(m: FloatArray, ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, ux: Float, uy: Float, uz: Float) {
        var fx = cx - ex; var fy = cy - ey; var fz = cz - ez
        var l = sqrt(fx * fx + fy * fy + fz * fz); fx /= l; fy /= l; fz /= l
        var sx = fy * uz - fz * uy; var sy = fz * ux - fx * uz; var sz = fx * uy - fy * ux
        l = sqrt(sx * sx + sy * sy + sz * sz); sx /= l; sy /= l; sz /= l
        val vx = sy * fz - sz * fy; val vy = sz * fx - sx * fz; val vz = sx * fy - sy * fx
        m[0] = sx; m[4] = sy; m[8] = sz
        m[1] = vx; m[5] = vy; m[9] = vz
        m[2] = -fx; m[6] = -fy; m[10] = -fz
        m[3] = 0f; m[7] = 0f; m[11] = 0f
        m[12] = -(sx * ex + sy * ey + sz * ez)
        m[13] = -(vx * ex + vy * ey + vz * ez)
        m[14] = fx * ex + fy * ey + fz * ez
        m[15] = 1f
    }

    /** out = a * b (out must not be a or b). */
    fun mul(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
    }

    /**
     * A model matrix: rotate by [roll] (about z), then [pitch] (about x), then [yaw] (about y), then move to
     * (x, y, z). Model space: z forward, y up (and so, the world being right-handed, +x is the model's left; the
     * cars are symmetrical). Yaw 0 faces +z; a heading (tx, tz) is yaw = atan2(tx, tz).
     */
    fun model(m: FloatArray, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, roll: Float) {
        val cy = cos(yaw); val sy = sin(yaw); val cp = cos(pitch); val sp = sin(pitch); val cr = cos(roll); val sr = sin(roll)
        // R = Ry * Rx * Rz
        val r00 = cy * cr + sy * sp * sr; val r01 = -cy * sr + sy * sp * cr; val r02 = sy * cp
        val r10 = cp * sr; val r11 = cp * cr; val r12 = -sp
        val r20 = -sy * cr + cy * sp * sr; val r21 = sy * sr + cy * sp * cr; val r22 = cy * cp
        m[0] = r00; m[1] = r10; m[2] = r20; m[3] = 0f
        m[4] = r01; m[5] = r11; m[6] = r21; m[7] = 0f
        m[8] = r02; m[9] = r12; m[10] = r22; m[11] = 0f
        m[12] = x; m[13] = y; m[14] = z; m[15] = 1f
    }
}

/** Smooth value noise (repeatable), for textures and terrain. */
class Noise(seed: Int) {
    private val perm = IntArray(512)
    private val vals = FloatArray(256)
    init {
        val r = java.util.Random(seed.toLong())
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) { val j = r.nextInt(i + 1); val t = p[i]; p[i] = p[j]; p[j] = t }
        for (i in 0 until 512) perm[i] = p[i and 255]
        for (i in 0 until 256) vals[i] = r.nextFloat()
    }
    private fun h(x: Int, y: Int) = vals[perm[(perm[x and 255] + y) and 511] and 255]

    /** 0..1, smooth, period 256 in both directions. */
    fun at(x: Float, y: Float): Float {
        val xf = floor(x); val yf = floor(y)
        val xi = xf.toInt(); val yi = yf.toInt()
        val fx = x - xf; val fy = y - yf
        val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
        val a = h(xi, yi); val b = h(xi + 1, yi); val c = h(xi, yi + 1); val d = h(xi + 1, yi + 1)
        return lerp(lerp(a, b, u), lerp(c, d, u), v)
    }

    /** Several octaves added together (0..1 roughly). */
    fun fbm(x: Float, y: Float, oct: Int = 4, gain: Float = 0.5f): Float {
        var sum = 0f; var amp = 1f; var f = 1f; var norm = 0f
        for (i in 0 until oct) { sum += at(x * f, y * f) * amp; norm += amp; amp *= gain; f *= 2.03f }
        return sum / norm
    }

    /** Tileable noise over a [period]-sized square (for textures that repeat without seams). */
    fun tile(x: Float, y: Float, period: Int): Float {
        val xf = floor(x); val yf = floor(y)
        val xi = xf.toInt(); val yi = yf.toInt()
        val fx = x - xf; val fy = y - yf
        val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
        fun w(a: Int) = ((a % period) + period) % period
        val a = h(w(xi), w(yi)); val b = h(w(xi + 1), w(yi)); val c = h(w(xi), w(yi + 1)); val d = h(w(xi + 1), w(yi + 1))
        return lerp(lerp(a, b, u), lerp(c, d, u), v)
    }

    /** Tileable noise with its own period across and down. */
    fun tileXY(x: Float, y: Float, px: Int, py: Int): Float {
        val xf = floor(x); val yf = floor(y)
        val xi = xf.toInt(); val yi = yf.toInt()
        val fx = x - xf; val fy = y - yf
        val u = fx * fx * (3 - 2 * fx); val v = fy * fy * (3 - 2 * fy)
        fun wx(a: Int) = ((a % px) + px) % px
        fun wy(a: Int) = ((a % py) + py) % py
        val a = h(wx(xi), wy(yi)); val b = h(wx(xi + 1), wy(yi)); val c = h(wx(xi), wy(yi + 1)); val d = h(wx(xi + 1), wy(yi + 1))
        return lerp(lerp(a, b, u), lerp(c, d, u), v)
    }

    fun tileFbm(x: Float, y: Float, period: Int, oct: Int = 4): Float {
        var sum = 0f; var amp = 1f; var f = 1; var norm = 0f
        for (i in 0 until oct) { sum += tile(x * f, y * f, period * f) * amp; norm += amp; amp *= 0.5f; f *= 2 }
        return sum / norm
    }
}

/** A growable float list (no boxing). */
class FloatList(cap: Int = 1024) {
    var a = FloatArray(cap); var n = 0
    fun add(v: Float) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun toArray() = a.copyOf(n)
}

/** A growable int list (no boxing). */
class IntList(cap: Int = 1024) {
    var a = IntArray(cap); var n = 0
    fun add(v: Int) { if (n == a.size) a = a.copyOf(a.size * 2); a[n++] = v }
    fun toArray() = a.copyOf(n)
}

fun absf(v: Float) = abs(v)
