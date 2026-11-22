package com.torquestreet.app.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The cars you can drive (all made up). Speeds in m/s, accelerations in m/s². */
class CarSpec(
    val id: Int, val name: String, val blurb: String,
    val topSpeed: Float, val accel: Float, val grip: Float, val brake: Float, val mass: Float,
    val shape: CarShape, val paints: List<FloatArray>
) {
    /** Ratings out of 10 for the menu. */
    val speedRating get() = ((topSpeed - 60f) / 3f).coerceIn(1f, 10f)
    val accelRating get() = ((accel - 6f) * 1.8f).coerceIn(1f, 10f)
    val handlingRating get() = ((grip - 9f) * 1.1f).coerceIn(1f, 10f)
}

/** The body's proportions (metres). */
class CarShape(
    val length: Float, val width: Float, val belt: Float, val roof: Float, val clearance: Float,
    val cab0: Float, val cab1: Float, val cab2: Float, val cab3: Float, // rear window base, roof start, roof end, windscreen base (z)
    val noseDrop: Float, val tailDrop: Float, val wheelR: Float, val wheelbase: Float,
    val spoiler: Boolean = false, val boxTruck: Boolean = false
)

object Cars {
    private fun c(r: Int, g: Int, b: Int) = floatArrayOf(r / 255f, g / 255f, b / 255f)

    val VANTA = CarShape(4.5f, 1.92f, 0.86f, 1.26f, 0.16f, -1.0f, -0.35f, 0.38f, 1.0f, 0.28f, 0.12f, 0.34f, 2.7f)
    val KESTREL = CarShape(4.2f, 1.86f, 0.78f, 1.13f, 0.13f, -0.85f, -0.3f, 0.25f, 0.9f, 0.32f, 0.08f, 0.33f, 2.55f, spoiler = true)
    val BRUISER = CarShape(4.85f, 1.96f, 0.96f, 1.33f, 0.18f, -1.45f, -0.75f, 0.05f, 0.55f, 0.16f, 0.1f, 0.36f, 2.9f)
    val INTERCEPTOR = CarShape(4.9f, 1.92f, 0.92f, 1.42f, 0.17f, -1.45f, -0.8f, 0.45f, 1.05f, 0.2f, 0.16f, 0.35f, 2.85f)
    val SEDAN = CarShape(4.6f, 1.82f, 0.9f, 1.45f, 0.16f, -1.5f, -0.85f, 0.4f, 1.0f, 0.18f, 0.18f, 0.32f, 2.7f)
    val HATCH = CarShape(3.9f, 1.75f, 0.88f, 1.48f, 0.15f, -1.8f, -1.55f, 0.15f, 0.8f, 0.2f, 0.05f, 0.31f, 2.4f)
    val VAN = CarShape(5.0f, 1.95f, 1.0f, 2.05f, 0.2f, -2.45f, -2.4f, 0.9f, 1.55f, 0.25f, 0.0f, 0.36f, 3.1f)
    val TRUCK = CarShape(7.0f, 2.3f, 1.25f, 2.6f, 0.3f, 0.7f, 0.8f, 2.6f, 3.1f, 0.15f, 0f, 0.45f, 4.2f, boxTruck = true)

    val player = listOf(
        CarSpec(0, "Vanta GT", "Balanced coupe: quick, sure-footed, a strong top speed", 83f, 9.6f, 15.2f, 16f, 1400f, VANTA,
            listOf(c(20, 60, 170), c(200, 20, 30), c(235, 235, 240), c(20, 20, 22), c(255, 150, 0))),
        CarSpec(1, "Kestrel R", "Light and nimble: the best grip and pickup, a little less top speed", 77f, 10.8f, 17.2f, 17f, 1150f, KESTREL,
            listOf(c(250, 200, 0), c(0, 170, 120), c(120, 30, 170), c(235, 235, 240), c(255, 60, 120))),
        CarSpec(2, "Bruiser V8", "Heavy muscle: the highest top speed, a handful in the corners", 87f, 9.0f, 13.6f, 14.5f, 1650f, BRUISER,
            listOf(c(150, 15, 20), c(25, 25, 28), c(15, 90, 50), c(200, 200, 205), c(30, 110, 200))))

    val rivalPaints = listOf(c(230, 90, 0), c(10, 140, 200), c(180, 180, 40), c(140, 20, 120), c(220, 220, 230), c(40, 160, 60))
    val trafficPaints = listOf(c(150, 150, 155), c(60, 60, 70), c(180, 40, 40), c(40, 70, 130), c(210, 205, 190), c(90, 110, 90), c(240, 240, 240))
    val trafficShapes = listOf(SEDAN, HATCH, VAN, TRUCK)

    /** The police car's handling (it can always keep up when it needs to). */
    val COP = CarSpec(9, "Interceptor", "", 88f, 10.2f, 15.5f, 16f, 1600f, INTERCEPTOR, listOf(c(255, 255, 255)))

    /** Builds a car mesh. [police] adds the two-tone paint and the light bar. */
    fun mesh(s: CarShape, police: Boolean = false): Mesh {
        val mb = MeshBuilder()
        if (s.boxTruck) truck(mb, s) else body(mb, s, police)
        wheels(mb, s)
        return mb.build()
    }

    // ------------------------------------------------------------------ body

    private fun body(mb: MeshBuilder, s: CarShape, police: Boolean) {
        val hl = s.length / 2; val hw = s.width / 2
        val yb = s.clearance
        // Lower body: stations from the back to the front (z, half-width, bottom, top)
        val st = arrayOf(
            floatArrayOf(-hl, hw * 0.9f, yb + 0.12f, s.belt - s.tailDrop - 0.05f),
            floatArrayOf(-hl + 0.12f, hw * 0.97f, yb, s.belt - s.tailDrop),
            floatArrayOf(-hl + 0.6f, hw, yb, s.belt - s.tailDrop * 0.3f),
            floatArrayOf(s.cab0, hw, yb, s.belt),
            floatArrayOf((s.cab1 + s.cab2) / 2, hw, yb, s.belt + 0.02f),
            floatArrayOf(s.cab3, hw, yb, s.belt - 0.01f),
            floatArrayOf(hl - 0.55f, hw * 0.98f, yb, s.belt - s.noseDrop * 0.7f),
            floatArrayOf(hl - 0.1f, hw * 0.93f, yb + 0.02f, s.belt - s.noseDrop),
            floatArrayOf(hl, hw * 0.86f, yb + 0.1f, s.belt - s.noseDrop - 0.08f))
        // Cross-section shape (x as a share of the half-width, y as a share of bottom..top), around the loop
        val sx = floatArrayOf(0.9f, 1f, 1f, 0.97f, 0.84f, -0.84f, -0.97f, -1f, -1f, -0.9f)
        val sy = floatArrayOf(0f, 0.22f, 0.62f, 0.9f, 1f, 1f, 0.9f, 0.62f, 0.22f, 0f)
        val np = sx.size
        val base = mb.count
        for ((k, a) in st.withIndex()) {
            val z = a[0]
            for (q in 0 until np) {
                val x = sx[q] * a[1]; val y = a[2] + sy[q] * (a[3] - a[2])
                // Police: white doors (the middle of the car's sides), black elsewhere
                val white = police && k in 3..5 && sy[q] < 0.95f && sy[q] > 0.1f
                val col = if (!police) 1f else if (white) 0.95f else 0.06f
                mb.vert(x, y, z, 0f, 1f, 0f, 0f, 0f, col, col, col, 0f, 0f, Layer.PAINT)
            }
        }
        for (k in 0 until st.size - 1) for (q in 0 until np) {
            val a = base + k * np + q; val b = base + k * np + (q + 1) % np
            val c = base + (k + 1) * np + (q + 1) % np; val d = base + (k + 1) * np + q
            mb.quad(a, b, c, d)
        }
        smoothNormals(mb, base, st.size * np)
        // Ends: the tail and the nose
        cap(mb, st[0], sx, sy, -1f); cap(mb, st[st.size - 1], sx, sy, 1f)

        // Cabin: glass all round, roof in paint
        val cb = arrayOf(
            floatArrayOf(s.cab0, hw * 0.86f, s.belt, s.belt + 0.03f),
            floatArrayOf(s.cab1, hw * 0.74f, s.belt + 0.01f, s.roof),
            floatArrayOf(s.cab2, hw * 0.74f, s.belt + 0.02f, s.roof),
            floatArrayOf(s.cab3, hw * 0.86f, s.belt - 0.01f, s.belt + 0.03f))
        for (k in 0 until 3) {
            val a = cb[k]; val b = cb[k + 1]
            // Sides (glass)
            for (side in intArrayOf(1, -1)) {
                val p = floatArrayOf(
                    side * a[1], a[2], a[0], side * b[1], b[2], b[0],
                    side * b[1] * 0.86f, b[3], b[0], side * a[1] * 0.86f, a[3], a[0])
                mb.faceToward(p, side.toFloat(), 0f, 0f, 1f, 1f, 1f, 0f, 0f, Layer.GLASS)
            }
            // Top: rear window, roof, windscreen
            val p = floatArrayOf(
                -a[1] * 0.86f, a[3], a[0], a[1] * 0.86f, a[3], a[0],
                b[1] * 0.86f, b[3], b[0], -b[1] * 0.86f, b[3], b[0])
            val roofCol = if (police) 0.95f else 1f
            // (Facing up and out: backwards for the rear window, forwards for the windscreen)
            val wz = when (k) { 0 -> -1f; 2 -> 1f; else -> 0f }
            if (k == 1) mb.faceToward(p, 0f, 1f, 0f, roofCol, roofCol, roofCol, 0f, 0f, Layer.PAINT)
            else mb.faceToward(p, 0f, 1f, wz, 1f, 1f, 1f, 0f, 0f, Layer.GLASS)
        }

        // Lights: headlights at the front, tail lights at the back (they brighten under braking)
        val nose = st[st.size - 1]; val tail = st[0]
        for (side in intArrayOf(1, -1)) {
            val x0 = side * nose[1] * 0.45f; val x1 = side * nose[1] * 0.85f
            val y0 = nose[3] - 0.17f; val y1 = nose[3] - 0.04f
            val z = nose[0] + 0.012f
            val hp = if (side > 0) floatArrayOf(x1, y0, z, x0, y0, z, x0, y1, z, x1, y1, z) else floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z)
            mb.faceToward(hp, 0f, 0f, 1f, 0.95f, 0.96f, 1f, 0.6f, 0f, Layer.WHITE)
            val tz = tail[0] - 0.012f
            val ty0 = tail[3] - 0.16f; val ty1 = tail[3] - 0.03f
            val tx0 = side * tail[1] * 0.42f; val tx1 = side * tail[1] * 0.92f
            val tp = if (side > 0) floatArrayOf(tx0, ty0, tz, tx1, ty0, tz, tx1, ty1, tz, tx0, ty1, tz) else floatArrayOf(tx1, ty0, tz, tx0, ty0, tz, tx0, ty1, tz, tx1, ty1, tz)
            mb.faceToward(tp, 0f, 0f, -1f, 1f, 0.1f, 0.06f, 0f, 0f, Layer.BRAKE)
        }
        // Grille
        val gz = nose[0] + 0.01f
        mb.faceToward(floatArrayOf(-nose[1] * 0.4f, nose[2] + 0.06f, gz, nose[1] * 0.4f, nose[2] + 0.06f, gz, nose[1] * 0.4f, nose[3] - 0.2f, gz, -nose[1] * 0.4f, nose[3] - 0.2f, gz),
            0f, 0f, 1f, 0.05f, 0.05f, 0.06f, 0f, 0f, Layer.WHITE)
        if (s.spoiler) {
            mb.box(0f, -hl + 0.25f, s.belt - s.tailDrop + 0.15f, s.belt - s.tailDrop + 0.2f, 0.18f, hw * 0.92f, 0f, 1f, 0.08f, 0.08f, 0.09f, 0f, 0f, Layer.WHITE, Layer.WHITE)
            for (sd in intArrayOf(1, -1)) mb.box(sd * hw * 0.6f, -hl + 0.3f, s.belt - s.tailDrop - 0.02f, s.belt - s.tailDrop + 0.16f, 0.06f, 0.03f, 0f, 1f, 0.08f, 0.08f, 0.09f, 0f, 0f, Layer.WHITE, null)
        }
        if (police) {
            // Light bar across the roof: red one side, blue the other
            val z = (s.cab1 + s.cab2) / 2
            mb.box(0f, z, s.roof, s.roof + 0.05f, 0.14f, hw * 0.72f, 0f, 1f, 0.15f, 0.15f, 0.16f, 0f, 0f, Layer.WHITE, Layer.WHITE)
            mb.box(hw * 0.36f, z, s.roof + 0.05f, s.roof + 0.16f, 0.12f, hw * 0.33f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, Layer.SIREN_RED, Layer.SIREN_RED)
            mb.box(-hw * 0.36f, z, s.roof + 0.05f, s.roof + 0.16f, 0.12f, hw * 0.33f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, Layer.SIREN_BLUE, Layer.SIREN_BLUE)
        }
    }

    /** A flat end on the body (the nose or tail), fanned from its middle. */
    private fun cap(mb: MeshBuilder, a: FloatArray, sx: FloatArray, sy: FloatArray, dir: Float) {
        val c = mb.vert(0f, (a[2] + a[3]) / 2, a[0], 0f, 0f, dir, 0f, 0f, 1f, 1f, 1f, 0f, 0f, Layer.PAINT)
        val first = mb.count
        for (q in sx.indices) mb.vert(sx[q] * a[1], a[2] + sy[q] * (a[3] - a[2]), a[0], 0f, 0f, dir, 0f, 0f, 1f, 1f, 1f, 0f, 0f, Layer.PAINT)
        for (q in sx.indices) {
            val i0 = first + q; val i1 = first + (q + 1) % sx.size
            if (dir > 0) mb.tri(c, i0, i1) else mb.tri(c, i1, i0)
        }
    }

    /** A box truck: a cab at the front and a tall box behind it. */
    private fun truck(mb: MeshBuilder, s: CarShape) {
        val hl = s.length / 2; val hw = s.width / 2
        // Cargo box (light, slightly dirty)
        mb.box(0f, -hl + 2.45f, s.clearance + 0.3f, s.roof + 0.6f, 2.4f, hw, 0f, 1f, 0.88f, 0.88f, 0.86f, 0f, 0f, Layer.WHITE, Layer.WHITE)
        // Cab
        mb.box(0f, hl - 1.1f, s.clearance + 0.2f, s.belt + 0.25f, 1.1f, hw * 0.96f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, Layer.PAINT, null)
        mb.box(0f, hl - 1.25f, s.belt + 0.25f, s.roof, 0.9f, hw * 0.94f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, Layer.PAINT, Layer.PAINT)
        // Windscreen
        val z = hl - 0.34f
        mb.faceToward(floatArrayOf(hw * 0.86f, s.belt + 0.35f, z, -hw * 0.86f, s.belt + 0.35f, z, -hw * 0.86f, s.roof - 0.15f, z, hw * 0.86f, s.roof - 0.15f, z), 0f, 0f, 1f, 1f, 1f, 1f, 0f, 0f, Layer.GLASS)
        for (side in intArrayOf(1, -1)) {
            val hz = hl - 0.19f
            mb.faceToward(floatArrayOf(side * hw * 0.55f, s.clearance + 0.45f, hz, side * hw * 0.85f, s.clearance + 0.45f, hz, side * hw * 0.85f, s.clearance + 0.62f, hz, side * hw * 0.55f, s.clearance + 0.62f, hz),
                0f, 0f, 1f, 0.95f, 0.96f, 1f, 0.6f, 0f, Layer.WHITE)
            val tz = -hl - 0.01f
            mb.faceToward(floatArrayOf(side * hw * 0.6f, s.clearance + 0.35f, tz, side * hw * 0.9f, s.clearance + 0.35f, tz, side * hw * 0.9f, s.clearance + 0.55f, tz, side * hw * 0.6f, s.clearance + 0.55f, tz),
                0f, 0f, -1f, 1f, 0.1f, 0.06f, 0f, 0f, Layer.BRAKE)
        }
    }

    // ------------------------------------------------------------------ wheels

    private fun wheels(mb: MeshBuilder, s: CarShape) {
        val hw = s.width / 2
        val r = s.wheelR; val w = 0.24f
        val segs = 12
        for (fz in floatArrayOf(-s.wheelbase / 2, s.wheelbase / 2)) for (side in intArrayOf(1, -1)) {
            val xo = side * (hw - 0.03f); val xi = side * (hw - 0.03f - w)
            // Tyre tread (a ring of quads)
            val base = mb.count
            for (q in 0..segs) {
                val a = q.toFloat() / segs * TWO_PI_F
                val y = r + cos(a) * r; val z = fz + sin(a) * r
                val ny = cos(a); val nz = sin(a)
                mb.vert(xo, y, z, 0f, ny, nz, 0f, 0f, 0.05f, 0.05f, 0.055f, 0f, 0f, Layer.WHITE)
                mb.vert(xi, y, z, 0f, ny, nz, 0f, 0f, 0.05f, 0.05f, 0.055f, 0f, 0f, Layer.WHITE)
            }
            for (q in 0 until segs) { val a = base + q * 2; mb.quad(a, a + 1, a + 3, a + 2) }
            // Outer face: rim (metal) inside the tyre's sidewall
            val c = mb.vert(xo + side * 0.005f, r, fz, side.toFloat(), 0f, 0f, 0f, 0f, 0.62f, 0.63f, 0.66f, 0f, 0f, Layer.METAL)
            val ring = mb.count
            for (q in 0 until segs) {
                val a = q.toFloat() / segs * TWO_PI_F
                val rr = if (q % 2 == 0) r * 0.68f else r * 0.6f // a hint of spokes
                mb.vert(xo + side * 0.005f, r + cos(a) * rr, fz + sin(a) * rr, side.toFloat(), 0f, 0f, 0f, 0f, 0.55f, 0.56f, 0.6f, 0f, 0f, Layer.METAL)
            }
            for (q in 0 until segs) mb.tri(c, ring + q, ring + (q + 1) % segs)
            val wall = mb.count
            for (q in 0 until segs) {
                val a = q.toFloat() / segs * TWO_PI_F
                mb.vert(xo, r + cos(a) * r * 0.68f, fz + sin(a) * r * 0.68f, side.toFloat(), 0f, 0f, 0f, 0f, 0.06f, 0.06f, 0.065f, 0f, 0f, Layer.WHITE)
                mb.vert(xo, r + cos(a) * r, fz + sin(a) * r, side.toFloat(), 0f, 0f, 0f, 0f, 0.06f, 0.06f, 0.065f, 0f, 0f, Layer.WHITE)
            }
            for (q in 0 until segs) { val a = wall + q * 2; val b = wall + ((q + 1) % segs) * 2; mb.quad(a, b, b + 1, a + 1) }
        }
    }

    /** Smooth normals for a block of vertices, from the triangles that use them. */
    private fun smoothNormals(mb: MeshBuilder, from: Int, count: Int) {
        val v = mb.v.a; val idx = mb.i
        val acc = FloatArray(count * 3)
        var q = 0
        while (q < idx.n) {
            val a = idx.a[q]; val b = idx.a[q + 1]; val c = idx.a[q + 2]; q += 3
            if (a < from || a >= from + count) continue
            val ax = v[b * STRIDE] - v[a * STRIDE]; val ay = v[b * STRIDE + 1] - v[a * STRIDE + 1]; val az = v[b * STRIDE + 2] - v[a * STRIDE + 2]
            val bx = v[c * STRIDE] - v[a * STRIDE]; val by = v[c * STRIDE + 1] - v[a * STRIDE + 1]; val bz = v[c * STRIDE + 2] - v[a * STRIDE + 2]
            val nx = ay * bz - az * by; val ny = az * bx - ax * bz; val nz = ax * by - ay * bx
            for (p in intArrayOf(a, b, c)) if (p in from until from + count) {
                acc[(p - from) * 3] += nx; acc[(p - from) * 3 + 1] += ny; acc[(p - from) * 3 + 2] += nz
            }
        }
        for (p in 0 until count) {
            val x = acc[p * 3]; val y = acc[p * 3 + 1]; val z = acc[p * 3 + 2]
            val l = sqrt(x * x + y * y + z * z); if (l < 1e-6f) continue
            val o = (from + p) * STRIDE
            v[o + 3] = x / l; v[o + 4] = y / l; v[o + 5] = z / l
        }
    }
}
