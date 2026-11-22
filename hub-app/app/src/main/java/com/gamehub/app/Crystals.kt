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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glass crystals that come out while a NEXUS panel (Settings and the like) is open, after the feel of the
 * crystals in an old console's system menu (an original design): a slow ring of six-sided crystal points, each
 * turning on its own axis, catching the light on their faces and edges, with a soft glow behind them.
 *
 * Drawing a frame makes no new objects: everything is reused.
 */
class Crystals(context: Context) {
    private val d = context.resources.displayMetrics.density
    private var t = 0f

    private class Cr(val a0: Float, val y: Float, val size: Float, val spin: Float, val tilt: Float, val bob: Float)
    private val crystals = Array(COUNT) { i ->
        val r = java.util.Random(31L + i)
        Cr(i * 6.2832f / COUNT, (r.nextFloat() - 0.5f) * 1.4f, 0.85f + r.nextFloat() * 0.4f,
            (0.35f + r.nextFloat() * 0.35f) * (if (i % 2 == 0) 1f else -1f), (r.nextFloat() - 0.5f) * 0.5f, r.nextFloat() * 6.28f)
    }

    // One crystal: a six-sided body with a long point on top and a short one underneath
    private val bx = FloatArray(14); private val by = FloatArray(14); private val bz = FloatArray(14)
    init {
        for (i in 0 until 6) {
            val a = i * 1.0472f
            bx[i] = cos(a) * 0.55f; bz[i] = sin(a) * 0.55f; by[i] = 0.75f        // top ring
            bx[6 + i] = bx[i]; bz[6 + i] = bz[i]; by[6 + i] = -0.55f             // bottom ring
        }
        bx[12] = 0f; by[12] = 2.1f; bz[12] = 0f   // top point
        bx[13] = 0f; by[13] = -1.35f; bz[13] = 0f // bottom point
    }
    /** The faces, as triangles (corner numbers above), and the crystal's edges each one draws (not the body's diagonals). */
    private val tris = IntArray(24 * 3)
    private val triEdges = IntArray(24 * 4) { -1 }
    init {
        var k = 0; var e = 0
        fun tri(a: Int, b: Int, c: Int, vararg ed: Int) {
            tris[k++] = a; tris[k++] = b; tris[k++] = c
            for (q in 0 until 4) triEdges[e + q] = if (q < ed.size) ed[q] else -1
            e += 4
        }
        for (i in 0 until 6) {
            val j = (i + 1) % 6
            tri(12, j, i, 12, j, j, i)               // top point
            tri(i, j, 6 + j, j, 6 + j)               // body
            tri(i, 6 + j, 6 + i, 6 + j, 6 + i)
            tri(13, 6 + i, 6 + j, 13, 6 + i)         // bottom point
        }
    }

    // Per frame, reused
    private val wx = FloatArray(14); private val wy = FloatArray(14); private val wz = FloatArray(14)
    private val sx = FloatArray(14); private val sy = FloatArray(14)
    private val verts = FloatArray(24 * 3 * 2)
    private val cols = IntArray(24 * 3)
    private val lines = FloatArray(24 * 3 * 4)
    private val order = IntArray(COUNT) { it }
    private val fill = Paint().apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.rgb(110, 170, 255), PorterDuff.Mode.MULTIPLY); additive() }
    private val glowDst = RectF()
    private fun Paint.additive() { if (android.os.Build.VERSION.SDK_INT >= 29) blendMode = android.graphics.BlendMode.PLUS }

    private val glowBmp: Bitmap by lazy {
        val s = 64
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, intArrayOf(Color.WHITE, Color.argb(90, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        b
    }

    private val ox = FloatArray(COUNT); private val oy = FloatArray(COUNT)
    private val near = FloatArray(COUNT)

    /** Light from the upper left, a little in front (the viewer looks along +z). */
    private val lx = -0.45f; private val ly = 0.72f; private val lz = -0.53f

    /**
     * Draws the crystals, fading in with [k] (0..1). They orbit an oval (centre [cx], [cy], radii [rx], [ry]; by
     * default round the middle of the screen, above and below an open panel), the near ones larger and brighter,
     * [scale] times their usual size. [dt] seconds have passed.
     */
    fun draw(c: Canvas, w: Int, h: Int, dt: Float, k: Float,
             cx: Float = w / 2f, cy: Float = h * 0.5f, rx: Float = w * 0.41f, ry: Float = h * 0.42f, scale: Float = 1f) {
        if (k <= 0.01f || w <= 0 || h <= 0) return
        t += dt
        val turn = t * 0.11f
        val unit0 = min(w, h) * 0.085f * scale
        for (i in 0 until COUNT) {
            val a = crystals[i].a0 + turn
            ox[i] = cx + cos(a) * rx; oy[i] = cy + sin(a) * ry
            near[i] = 0.5f + 0.5f * sin(a) // the bottom of the oval is the near side
        }
        // Far ones first
        for (i in 1 until COUNT) { val v = order[i]; var j = i - 1; while (j >= 0 && near[order[j]] > near[v]) { order[j + 1] = order[j]; j-- }; order[j + 1] = v }
        for (oi in 0 until COUNT) {
            val n = order[oi]
            val cr = crystals[n]
            val nk = near[n]
            val unit = unit0 * cr.size * (0.7f + 0.45f * nk) * (0.92f + 0.08f * k)
            val bob = sin(t * 0.8f + cr.bob) * unit * 0.25f
            val px0 = ox[n]; val py0 = oy[n] + bob
            val rs = t * cr.spin + cr.bob
            val cs = cos(rs); val sn = sin(rs); val ct = cos(cr.tilt); val st = sin(cr.tilt)
            for (v in 0 until 14) {
                // Turn on its own axis and lean a little
                val x1 = bx[v] * cs - bz[v] * sn; val z1 = bx[v] * sn + bz[v] * cs
                val x2 = x1 * ct - by[v] * st; val y2 = x1 * st + by[v] * ct
                wx[v] = x2; wy[v] = y2; wz[v] = z1
                // A touch of perspective: the nearer corners a little bigger
                val p = 1f / (1f + z1 * 0.08f)
                sx[v] = px0 + x2 * unit * p; sy[v] = py0 - y2 * unit * p
            }
            val bright = (0.55f + 0.45f * nk) * k
            // Its glow
            val gr = unit * 2.4f
            glowPaint.alpha = (175 * bright).toInt().coerceIn(0, 255)
            glowDst.set(px0 - gr, py0 - gr * 1.35f, px0 + gr, py0 + gr * 1.35f)
            c.drawBitmap(glowBmp, null, glowDst, glowPaint)
            // Faces: the far side faint (seen through the glass), the near side shaded by the light
            var vn = 0; var ln = 0
            for (pass in 0..1) {
                for (f in 0 until 24) {
                    val i0 = tris[f * 3]; val i1 = tris[f * 3 + 1]; val i2 = tris[f * 3 + 2]
                    val ax = wx[i1] - wx[i0]; val ay = wy[i1] - wy[i0]; val az = wz[i1] - wz[i0]
                    val bxv = wx[i2] - wx[i0]; val byv = wy[i2] - wy[i0]; val bzv = wz[i2] - wz[i0]
                    var nx = ay * bzv - az * byv; var ny = az * bxv - ax * bzv; var nz = ax * byv - ay * bxv
                    val nl = sqrt(nx * nx + ny * ny + nz * nz); if (nl < 1e-6f) continue
                    nx /= nl; ny /= nl; nz /= nl
                    val front = nz < 0f // facing the viewer
                    if ((pass == 0) == front) continue
                    val col = if (!front) Color.argb((34 * bright).toInt().coerceIn(0, 255), 120, 170, 255) else {
                        val lit = max(0f, nx * lx + ny * ly + nz * lz)
                        val spec = lit * lit * lit * lit
                        Color.argb(((70 + 110 * lit) * bright).toInt().coerceIn(0, 255),
                            (110 + 120 * lit + 25 * spec).toInt().coerceIn(0, 255), (160 + 80 * lit + 15 * spec).toInt().coerceIn(0, 255), 255)
                    }
                    for (q in 0..2) { val vi = tris[f * 3 + q]; verts[vn * 2] = sx[vi]; verts[vn * 2 + 1] = sy[vi]; cols[vn] = col; vn++ }
                    if (front) {
                        var q = 0
                        while (q < 4 && triEdges[f * 4 + q] >= 0) {
                            val e0 = triEdges[f * 4 + q]; val e1 = triEdges[f * 4 + q + 1]
                            lines[ln++] = sx[e0]; lines[ln++] = sy[e0]; lines[ln++] = sx[e1]; lines[ln++] = sy[e1]
                            q += 2
                        }
                    }
                }
            }
            if (vn > 0 && android.os.Build.VERSION.SDK_INT >= 29) c.drawVertices(Canvas.VertexMode.TRIANGLES, vn * 2, verts, 0, null, 0, cols, 0, null, 0, 0, fill)
            if (ln > 0) {
                edge.strokeWidth = max(0.7f, unit * 0.022f)
                edge.color = Color.argb((150 * bright).toInt().coerceIn(0, 255), 190, 220, 255)
                c.drawLines(lines, 0, ln, edge)
            }
        }
    }

    companion object { private const val COUNT = 7 }
}
