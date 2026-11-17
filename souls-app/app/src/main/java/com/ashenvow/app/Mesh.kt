package com.ashenvow.app

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Collects triangles: position, normal and color for every vertex. */
class MeshBuilder {
    var data = FloatArray(9 * 1024)
    var size = 0

    fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, c: FloatArray) {
        if (size + 9 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x; data[size++] = y; data[size++] = z
        data[size++] = nx; data[size++] = ny; data[size++] = nz
        data[size++] = c[0]; data[size++] = c[1]; data[size++] = c[2]
    }

    /** Two triangles from four corners (in order around the edge), all with one normal. */
    fun quad(p: Array<FloatArray>, n: FloatArray, c: FloatArray) {
        for (i in intArrayOf(0, 1, 2, 0, 2, 3)) vert(p[i][0], p[i][1], p[i][2], n[0], n[1], n[2], c)
    }

    fun tri(a: FloatArray, b: FloatArray, cc: FloatArray, n: FloatArray, c: FloatArray) {
        vert(a[0], a[1], a[2], n[0], n[1], n[2], c)
        vert(b[0], b[1], b[2], n[0], n[1], n[2], c)
        vert(cc[0], cc[1], cc[2], n[0], n[1], n[2], c)
    }

    /** A box centred at (cx, cy, cz) with full sizes sx, sy, sz, turned by yaw (and tipped by tilt) in degrees. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, c: FloatArray, yawDeg: Float = 0f, tiltDeg: Float = 0f) {
        val ya = (yawDeg * PI / 180).toFloat(); val ta = (tiltDeg * PI / 180).toFloat()
        val cy0 = cos(ya); val sy0 = sin(ya); val ct = cos(ta); val st = sin(ta)
        fun tr(x: Float, y: Float, z: Float): FloatArray {
            // tilt around X, then yaw around Y
            val y1 = y * ct - z * st; val z1 = y * st + z * ct
            return floatArrayOf(x * cy0 + z1 * sy0, y1, -x * sy0 + z1 * cy0)
        }
        fun pt(x: Float, y: Float, z: Float): FloatArray { val v = tr(x, y, z); return floatArrayOf(cx + v[0], cy + v[1], cz + v[2]) }
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        val faces = arrayOf(
            floatArrayOf(1f, 0f, 0f), floatArrayOf(-1f, 0f, 0f),
            floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, -1f, 0f),
            floatArrayOf(0f, 0f, 1f), floatArrayOf(0f, 0f, -1f)
        )
        for (n in faces) {
            // Two axes across the face
            val u = if (n[0] != 0f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
            val v = floatArrayOf(n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0])
            val ox = n[0] * hx; val oy = n[1] * hy; val oz = n[2] * hz
            val ux = u[0] * hx; val uy = u[1] * hy; val uz = u[2] * hz
            val vx = v[0] * hx; val vy = v[1] * hy; val vz = v[2] * hz
            val corners = arrayOf(
                pt(ox - ux - vx, oy - uy - vy, oz - uz - vz),
                pt(ox + ux - vx, oy + uy - vy, oz + uz - vz),
                pt(ox + ux + vx, oy + uy + vy, oz + uz + vz),
                pt(ox - ux + vx, oy - uy + vy, oz - uz + vz)
            )
            quad(corners, tr(n[0], n[1], n[2]), c)
        }
    }

    /** An upright cylinder (low-poly), standing on y = cy. */
    fun cylinder(cx: Float, cy: Float, cz: Float, r: Float, h: Float, sides: Int, c: FloatArray, top: Boolean = true, rTop: Float = r) {
        for (i in 0 until sides) {
            val a0 = (i.toFloat() / sides * 2 * PI).toFloat(); val a1 = ((i + 1).toFloat() / sides * 2 * PI).toFloat()
            val am = (a0 + a1) / 2
            val n = floatArrayOf(sin(am), 0f, cos(am))
            quad(arrayOf(
                floatArrayOf(cx + sin(a0) * r, cy, cz + cos(a0) * r),
                floatArrayOf(cx + sin(a1) * r, cy, cz + cos(a1) * r),
                floatArrayOf(cx + sin(a1) * rTop, cy + h, cz + cos(a1) * rTop),
                floatArrayOf(cx + sin(a0) * rTop, cy + h, cz + cos(a0) * rTop)
            ), n, c)
            if (top) tri(
                floatArrayOf(cx, cy + h, cz),
                floatArrayOf(cx + sin(a0) * rTop, cy + h, cz + cos(a0) * rTop),
                floatArrayOf(cx + sin(a1) * rTop, cy + h, cz + cos(a1) * rTop),
                floatArrayOf(0f, 1f, 0f), c
            )
        }
    }

    /** A flat ring on the ground (inner radius r0, outer r1). */
    fun annulus(r0: Float, r1: Float, y: Float, sides: Int, c: FloatArray) {
        val n = floatArrayOf(0f, 1f, 0f)
        for (i in 0 until sides) {
            val a0 = (i.toFloat() / sides * 2 * PI).toFloat(); val a1 = ((i + 1).toFloat() / sides * 2 * PI).toFloat()
            quad(arrayOf(
                floatArrayOf(sin(a0) * r0, y, cos(a0) * r0), floatArrayOf(sin(a1) * r0, y, cos(a1) * r0),
                floatArrayOf(sin(a1) * r1, y, cos(a1) * r1), floatArrayOf(sin(a0) * r1, y, cos(a0) * r1)
            ), n, c)
        }
    }

    /** A low-poly ball. */
    fun sphere(cx: Float, cy: Float, cz: Float, r: Float, rings: Int, segs: Int, c: FloatArray) {
        fun p(i: Int, j: Int): FloatArray {
            val th = (i.toFloat() / rings * PI).toFloat(); val ph = (j.toFloat() / segs * 2 * PI).toFloat()
            return floatArrayOf(sin(th) * sin(ph), cos(th), sin(th) * cos(ph))
        }
        for (i in 0 until rings) for (j in 0 until segs) {
            val a = p(i, j); val b = p(i + 1, j); val d = p(i + 1, j + 1); val e = p(i, j + 1)
            for (q in arrayOf(a, b, d, a, d, e)) {
                vert(cx + q[0] * r, cy + q[1] * r, cz + q[2] * r, q[0], q[1], q[2], c)
            }
        }
    }

    fun build(): Mesh = Mesh(data, size)
}

/** Triangles uploaded to the graphics card. */
class Mesh(data: FloatArray, floats: Int) {
    val count = floats / 9
    private val vbo: Int

    init {
        val buf = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data, 0, floats).position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, floats * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    fun draw(aPos: Int, aNor: Int, aCol: Int) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 36, 0)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 36, 12)
        GLES20.glEnableVertexAttribArray(aCol)
        GLES20.glVertexAttribPointer(aCol, 3, GLES20.GL_FLOAT, false, 36, 24)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, count)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }
}

fun rgb(hex: Int) = floatArrayOf(((hex shr 16) and 255) / 255f, ((hex shr 8) and 255) / 255f, (hex and 255) / 255f)

fun normalize3(x: Float, y: Float, z: Float): FloatArray {
    val l = sqrt(x * x + y * y + z * z)
    return floatArrayOf(x / l, y / l, z / l)
}
