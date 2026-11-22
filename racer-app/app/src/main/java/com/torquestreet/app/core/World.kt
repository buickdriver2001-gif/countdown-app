package com.torquestreet.app.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vertex layout shared by every world and car mesh (14 floats):
 * position xyz, normal xyz, uv, colour rgb + emissive, baked street-light amount, texture layer.
 */
const val STRIDE = 14

/** Texture layers in the world texture array (see TexGen), plus a few material codes the shader understands. */
object Layer {
    const val ASPHALT = 0f; const val CONCRETE = 1f; const val GRASS = 2f; const val ROCK = 3f; const val SAND = 4f
    const val FACADE0 = 5f // 5..8: four building styles (alpha = lit windows at night)
    const val FOLIAGE = 9f; const val METAL = 10f; const val TUNNEL = 11f; const val WHITE = 12f; const val SKYLINE = 13f
    const val PAINT = 14f // glossy car paint (white texture, vertex colour)
    const val GLASS = 15f
    const val SIREN_RED = 16f; const val SIREN_BLUE = 17f
    const val BRAKE = 18f // tail lights: brighter when braking
    const val TERRAIN = 20f // blends grass / rock / sand by slope and height
    const val COUNT = 14 // texture layers 0..13
}

/** A mesh being built. */
class MeshBuilder {
    val v = FloatList(4096)
    val i = IntList(4096)
    val count get() = v.n / STRIDE

    fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float,
             r: Float, g: Float, b: Float, e: Float, lamp: Float, layer: Float): Int {
        val a = v
        a.add(x); a.add(y); a.add(z); a.add(nx); a.add(ny); a.add(nz); a.add(u); a.add(w)
        a.add(r); a.add(g); a.add(b); a.add(e); a.add(lamp); a.add(layer)
        return count - 1
    }
    fun tri(a: Int, b: Int, c: Int) { i.add(a); i.add(b); i.add(c) }
    /** Two triangles a-b-c, a-c-d (counter-clockwise seen from the front). */
    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** A flat quad with its own normal (corners counter-clockwise seen from the front). */
    fun face(p: FloatArray, u0: Float, v0: Float, u1: Float, v1: Float, r: Float, g: Float, b: Float, e: Float, lamp: Float, layer: Float) {
        val ax = p[3] - p[0]; val ay = p[4] - p[1]; val az = p[5] - p[2]
        val bx = p[9] - p[0]; val by = p[10] - p[1]; val bz = p[11] - p[2]
        var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
        val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
        val a = vert(p[0], p[1], p[2], nx, ny, nz, u0, v0, r, g, b, e, lamp, layer)
        val bb = vert(p[3], p[4], p[5], nx, ny, nz, u1, v0, r, g, b, e, lamp, layer)
        val c = vert(p[6], p[7], p[8], nx, ny, nz, u1, v1, r, g, b, e, lamp, layer)
        val d = vert(p[9], p[10], p[11], nx, ny, nz, u0, v1, r, g, b, e, lamp, layer)
        quad(a, bb, c, d)
    }

    /** [face], turned (if needed) so that it faces the direction (wx, wy, wz). */
    fun faceToward(p: FloatArray, wx: Float, wy: Float, wz: Float, r: Float, g: Float, b: Float, e: Float, lamp: Float, layer: Float) {
        val ax = p[3] - p[0]; val ay = p[4] - p[1]; val az = p[5] - p[2]
        val bx = p[9] - p[0]; val by = p[10] - p[1]; val bz = p[11] - p[2]
        val nx = ay * bz - az * by; val ny = az * bx - ax * bz; val nz = ax * by - ay * bx
        if (nx * wx + ny * wy + nz * wz >= 0f) face(p, 0f, 0f, 1f, 1f, r, g, b, e, lamp, layer)
        else face(floatArrayOf(p[0], p[1], p[2], p[9], p[10], p[11], p[6], p[7], p[8], p[3], p[4], p[5]), 0f, 0f, 1f, 1f, r, g, b, e, lamp, layer)
    }

    /**
     * A box: centre (x, z), base y0 to top y1, half-sizes along its own axes (fx, fz) = forward direction.
     * [layer] for the sides, [top] for the roof (or null for none). UVs in metres / [uvScale].
     */
    fun box(x: Float, z: Float, y0: Float, y1: Float, hl: Float, hwid: Float, fx: Float, fz: Float,
            r: Float, g: Float, b: Float, e: Float, lamp: Float, layer: Float, top: Float?, uvScale: Float = 1f,
            topR: Float = r, topG: Float = g, topB: Float = b) {
        val rx = -fz; val rz = fx
        val cxs = floatArrayOf(-hl, hl, hl, -hl); val cws = floatArrayOf(-hwid, -hwid, hwid, hwid)
        val px = FloatArray(4) { x + fx * cxs[it] + rx * cws[it] }
        val pz = FloatArray(4) { z + fz * cxs[it] + rz * cws[it] }
        val p = FloatArray(12)
        var uAcc = 0f
        for (k in 0 until 4) {
            val j = (k + 1) % 4
            val len = hypot(px[j] - px[k], pz[j] - pz[k])
            // Outward-facing wall (counter-clockwise from outside): bottom k, bottom j, top j, top k
            p[0] = px[j]; p[1] = y0; p[2] = pz[j]
            p[3] = px[k]; p[4] = y0; p[5] = pz[k]
            p[6] = px[k]; p[7] = y1; p[8] = pz[k]
            p[9] = px[j]; p[10] = y1; p[11] = pz[j]
            face(p, (uAcc + len) / uvScale, y0 / uvScale, uAcc / uvScale, y1 / uvScale, r, g, b, e, lamp, layer)
            uAcc += len
        }
        if (top != null) {
            p[0] = px[0]; p[1] = y1; p[2] = pz[0]
            p[3] = px[3]; p[4] = y1; p[5] = pz[3]
            p[6] = px[2]; p[7] = y1; p[8] = pz[2]
            p[9] = px[1]; p[10] = y1; p[11] = pz[1]
            face(p, 0f, 0f, hwid * 2 / uvScale, hl * 2 / uvScale, topR, topG, topB, 0f, lamp, top)
        }
    }

    fun build() = Mesh(v.toArray(), i.toArray())
}

class Mesh(val verts: FloatArray, val idx: IntArray) {
    val vertexCount get() = verts.size / STRIDE
    val empty get() = idx.isEmpty()
}

/** A piece of the world drawn together: what's solid, and what's cut out (leaves). Culled by distance. */
class Chunk(val x: Float, val z: Float, val radius: Float, val solid: Mesh, val cutout: Mesh)

/** A street light: where its lamp is (it lights the road below it at night). */
class Lamp(val x: Float, val y: Float, val z: Float)

/**
 * Builds everything to draw for a track: the ground shaped around the road, the road itself, curbs and
 * sidewalks, rails, buildings, trees, street lights (whose light is worked out ahead and stored in the
 * vertices), the bridge and tunnel, and the far skyline. Same result every time for a track.
 */
class World(val track: Track) {
    val scene = track.scene
    val chunks = ArrayList<Chunk>()
    val lamps = ArrayList<Lamp>()
    lateinit var skyline: Mesh
    val hasWater = scene == Scene.COAST
    val waterLevel = 0f
    val centerX = (track.minX + track.maxX) / 2
    val centerZ = (track.minZ + track.maxZ) / 2
    private val rnd = java.util.Random(1234L + track.info.id)
    private val noise = Noise(77 + track.info.id)
    private val tmp = FloatArray(3)

    // Ground height field
    private val gStep = 12f
    private var gx0 = 0f; private var gz0 = 0f; private var gnx = 0; private var gnz = 0
    private lateinit var gh: FloatArray

    fun build(): World {
        placeLamps()
        buildLampGrid()
        markBridges()
        buildGround()
        buildTrackChunks()
        buildGroundChunks()
        skyline = buildSkyline()
        return this
    }

    // ------------------------------------------------------------------ the ground

    /** The natural ground height at (x, z), before it's shaped around the road. */
    private fun natural(x: Float, z: Float): Float = when (scene) {
        Scene.CITY -> 0.5f + noise.fbm(x / 300f, z / 300f, 3) * 2f
        Scene.COAST -> {
            val zc = -215f + 40f * sin(x / 310f)
            var hgt = (z - zc) * 0.07f + noise.fbm(x / 420f, z / 420f, 4) * 26f * smooth(150f, 420f, z - zc) - 4f
            // The bay the bridge crosses
            val bx = x - 1270f; val bz = z + 40f
            hgt -= 30f * exp(-(bx * bx + bz * bz) / (190f * 190f))
            max(hgt, -14f)
        }
        Scene.MOUNTAIN -> {
            val ridge = 1f - abs(noise.fbm(x / 520f, z / 520f, 4) * 2f - 1f)
            110f + noise.fbm(x / 900f, z / 900f, 5) * 170f + ridge * ridge * 90f
        }
    }

    private fun buildGround() {
        gx0 = track.minX - 400f; gz0 = track.minZ - 400f
        gnx = ((track.maxX + 400f - gx0) / gStep).toInt() + 1
        gnz = ((track.maxZ + 400f - gz0) / gStep).toInt() + 1
        gh = FloatArray(gnx * gnz)
        for (j in 0 until gnz) for (i in 0 until gnx) {
            val x = gx0 + i * gStep; val z = gz0 + j * gStep
            gh[j * gnx + i] = shaped(x, z)
        }
    }

    /** Ground height shaped around the road: just under it along the road, blending into the land further out. */
    private fun shaped(x: Float, z: Float): Float {
        val base = natural(x, z)
        if (!track.nearest(x, z, 110f)) return base
        val k = track.nearI
        val dist = abs(track.nearD)
        val roadY = track.cy[k]
        val corridor = track.hw[k] + if (scene == Scene.CITY) 5f else 4f
        if (track.tunnel[k]) {
            // The mountain stays over the tunnel
            return max(base, roadY + 18f + dist * 0.4f)
        }
        // Under a bridge the land (or sea) stays as it is
        if (track.bridge[k]) return base
        // Near a tunnel's ends the ground rises over it smoothly
        val under = roadY - (if (scene == Scene.CITY) 0.35f else 0.9f)
        if (dist < corridor) return under
        val t = smooth(corridor, corridor + (if (scene == Scene.CITY) 30f else 70f), dist)
        return lerp(under, base, t)
    }

    /** Ground height at any point (from the height field). */
    fun groundAt(x: Float, z: Float): Float {
        val fx = ((x - gx0) / gStep).coerceIn(0f, gnx - 1.001f); val fz = ((z - gz0) / gStep).coerceIn(0f, gnz - 1.001f)
        val i = fx.toInt(); val j = fz.toInt(); val u = fx - i; val w = fz - j
        val a = gh[j * gnx + i]; val b = gh[j * gnx + i + 1]; val c = gh[(j + 1) * gnx + i]; val d = gh[(j + 1) * gnx + i + 1]
        return lerp(lerp(a, b, u), lerp(c, d, u), w)
    }

    private fun buildGroundChunks() {
        val tile = 20
        val layer = if (scene == Scene.CITY) Layer.CONCRETE else Layer.TERRAIN
        var j0 = 0
        while (j0 < gnz - 1) {
            var i0 = 0
            while (i0 < gnx - 1) {
                val mb = MeshBuilder()
                val i1 = min(i0 + tile, gnx - 1); val j1 = min(j0 + tile, gnz - 1)
                val w = i1 - i0 + 1
                for (j in j0..j1) for (i in i0..i1) {
                    val x = gx0 + i * gStep; val z = gz0 + j * gStep
                    val hL = gh[j * gnx + max(i - 1, 0)]; val hR = gh[j * gnx + min(i + 1, gnx - 1)]
                    val hD = gh[max(j - 1, 0) * gnx + i]; val hU = gh[min(j + 1, gnz - 1) * gnx + i]
                    var nx = hL - hR; var ny = 2f * gStep; var nz = hD - hU
                    val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
                    val y = gh[j * gnx + i]
                    val shade = 0.85f + 0.15f * noise.at(x / 37f, z / 37f)
                    val (cr, cg, cb) = if (scene == Scene.CITY) Triple(0.55f * shade, 0.56f * shade, 0.58f * shade) else Triple(shade, shade, shade)
                    mb.vert(x, y, z, nx, ny, nz, x / 8f, z / 8f, cr, cg, cb, 0f, lampAt(x, y, z), layer)
                }
                for (j in 0 until j1 - j0) for (i in 0 until i1 - i0) {
                    val a = j * w + i; val b = a + 1; val c = a + w + 1; val d = a + w
                    mb.quad(a, d, c, b)
                }
                val cx = gx0 + (i0 + i1) / 2f * gStep; val cz = gz0 + (j0 + j1) / 2f * gStep
                val rad = hypot((i1 - i0) * gStep, (j1 - j0) * gStep) / 2f
                chunks.add(Chunk(cx, cz, rad, mb.build(), Mesh(FloatArray(0), IntArray(0))))
                i0 = i1
            }
            j0 = minOf(j0 + tile, gnz - 1)
            if (j0 >= gnz - 1) break
        }
    }

    // ------------------------------------------------------------------ street lights

    private fun placeLamps() {
        val t = track
        val every = when (scene) { Scene.CITY -> 17; Scene.COAST -> 24; Scene.MOUNTAIN -> 9 } // samples (2 m each)
        var side = 1f
        var k = 0
        while (k < t.n) {
            val useHere = when (scene) {
                Scene.CITY -> true
                Scene.COAST -> true
                Scene.MOUNTAIN -> t.tunnel[k]
            }
            if (useHere) {
                if (t.tunnel[k]) {
                    // Tunnel lights: in the ceiling, over the middle of the road
                    t.pos(k * Track.STEP, 0f, 6.6f, tmp)
                    lamps.add(Lamp(tmp[0], tmp[1], tmp[2]))
                } else {
                    val s = if (scene == Scene.COAST) -1f else side // the coast road's lights stand on the sea side
                    t.pos(k * Track.STEP, s * (t.hw[k] - 2.4f), 8.6f, tmp)
                    lamps.add(Lamp(tmp[0], tmp[1], tmp[2]))
                    lampSides.add(s); lampIdx.add(k)
                }
            }
            side = -side
            k += every
        }
    }
    private val lampSides = FloatList(256)
    private val lampIdx = IntList(256)

    private val lCell = 30f
    private val lampGrid = HashMap<Long, IntList>()
    private fun lkey(i: Int, j: Int) = (i.toLong() shl 32) xor (j.toLong() and 0xffffffffL)
    private fun buildLampGrid() {
        for ((n, l) in lamps.withIndex()) {
            val k = lkey(floor(l.x / lCell).toInt(), floor(l.z / lCell).toInt())
            lampGrid.getOrPut(k) { IntList(8) }.add(n)
        }
    }

    /** How much street light reaches (x, y, z), 0..~1.5 (stored in the vertices, used at night). */
    fun lampAt(x: Float, y: Float, z: Float): Float {
        val ci = floor(x / lCell).toInt(); val cj = floor(z / lCell).toInt()
        var sum = 0f
        for (j in cj - 1..cj + 1) for (i in ci - 1..ci + 1) {
            val l = lampGrid[lkey(i, j)] ?: continue
            for (q in 0 until l.n) {
                val lp = lamps[l.a[q]]
                val dx = x - lp.x; val dy = y - lp.y; val dz = z - lp.z
                val dd = dx * dx + dz * dz
                if (dd > 26f * 26f) continue
                // A bright pool under the lamp (about 7 m across its brightest), with a faint wider spill
                val up = if (dy > 0f) 0.15f else 1f // (things above the lamp get little of it)
                sum += (exp(-dd / (2f * 6f * 6f)) * 1.5f + 0.03f * (1f - sqrt(dd) / 26f)) * up
            }
        }
        return min(sum, 2f)
    }

    // ------------------------------------------------------------------ the track and what's beside it

    private fun buildTrackChunks() {
        val t = track
        val per = 25
        // Buildings and trees need to know what's already taken (5 m squares)
        var k0 = 0
        while (k0 < t.n) {
            val k1 = min(k0 + per, t.n)
            val solid = MeshBuilder(); val cut = MeshBuilder()
            road(solid, k0, k1)
            sides(solid, k0, k1)
            if (scene == Scene.MOUNTAIN) tunnelPart(solid, k0, k1)
            bridgePart(solid, k0, k1)
            lampPosts(solid, k0, k1)
            when (scene) {
                Scene.CITY -> buildings(solid, k0, k1)
                Scene.COAST -> { villas(solid, k0, k1); trees(cut, k0, k1) }
                Scene.MOUNTAIN -> trees(cut, k0, k1)
            }
            if (k0 == 0) startLine(solid)
            val mid = (k0 + k1) / 2
            chunks.add(Chunk(t.cx[mid], t.cz[mid], 120f, solid.build(), cut.build()))
            k0 = k1
        }
    }

    /** The road surface: five strips across, the texture's lane lines running along it. */
    private fun road(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        val cols = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val base = mb.count
        for (k in k0..k1) {
            val kk = t.idx(k)
            val hw = t.hw[kk]
            // Surface normal: tilted by the slope and the banking
            val sl = t.slope[kk]; val bk = t.bank[kk]
            // (Height across the road is -d * bank, so the surface leans towards the right by the bank)
            var nx = -t.tx[kk] * sl - t.tz[kk] * bk
            var ny = 1f
            var nz = -t.tz[kk] * sl + t.tx[kk] * bk
            val l = sqrt(nx * nx + ny * ny + nz * nz); nx /= l; ny /= l; nz /= l
            for (c in cols) {
                t.pos(k * Track.STEP, c * hw, 0f, tmp)
                mb.vert(tmp[0], tmp[1], tmp[2], nx, ny, nz, (c + 1f) / 2f, k * Track.STEP / 16f, 1f, 1f, 1f, 0f, lampAt(tmp[0], tmp[1], tmp[2]), Layer.ASPHALT)
            }
        }
        for (r in 0 until k1 - k0) for (c in 0 until 4) {
            val a = base + r * 5 + c
            mb.quad(a, a + 1, a + 6, a + 5)
        }
    }

    /** Curbs and sidewalks (city) or shoulders and banks (elsewhere), both sides. */
    private fun sides(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        for (side in intArrayOf(-1, 1)) {
            // Cross-section outward from the road's edge: (offset from the edge, height)
            val prof: FloatArray = if (scene == Scene.CITY)
                floatArrayOf(0f, 0f, 0f, 0.16f, 0.25f, 0.16f, 4.4f, 0.16f, 4.6f, -1.2f)
            else floatArrayOf(0f, 0f, 1.7f, -0.05f, 3.2f, -1.6f)
            val np = prof.size / 2
            val base = mb.count
            for (k in k0..k1) {
                val kk = t.idx(k)
                val hw = t.hw[kk]
                val skipBank = t.bridge[kk] || t.tunnel[kk]
                var acc = 0f
                for (q in 0 until np) {
                    val off = prof[q * 2]; var hgt = prof[q * 2 + 1]
                    if (skipBank && q == np - 1) hgt = prof[(q - 1) * 2 + 1] - 0.05f
                    t.pos(k * Track.STEP, side * (hw + off), hgt, tmp)
                    if (q > 0) acc += hypot(prof[q * 2] - prof[q * 2 - 2], prof[q * 2 + 1] - prof[q * 2 - 1])
                    val layer = if (scene == Scene.CITY || q < np - 1) Layer.CONCRETE else Layer.ROCK
                    val shade = if (scene == Scene.CITY) (if (q == 1) 0.8f else 0.9f) else 0.75f
                    mb.vert(tmp[0], tmp[1], tmp[2], 0f, 1f, 0f, acc / 2f, k * Track.STEP / 4f, shade, shade, shade, 0f, lampAt(tmp[0], tmp[1], tmp[2]), layer)
                }
            }
            for (r in 0 until k1 - k0) for (q in 0 until np - 1) {
                val a = base + r * np + q
                if (side > 0) mb.quad(a, a + 1, a + np + 1, a + np) else mb.quad(a, a + np, a + np + 1, a + 1)
            }
            fixNormals(mb, base, (k1 - k0 + 1) * np)
            if (scene != Scene.CITY) rails(mb, k0, k1, side)
        }
    }

    /** Recomputes smooth normals for vertices [from] until [from]+[count] from the triangles that use them. */
    private fun fixNormals(mb: MeshBuilder, from: Int, count: Int) {
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
            val l = sqrt(x * x + y * y + z * z)
            if (l < 1e-6f) continue
            val o = (from + p) * STRIDE
            v[o + 3] = x / l; v[o + 4] = y / l; v[o + 5] = z / l
        }
    }

    /** Steel guard rails along the edge, with posts (not in the tunnel). */
    private fun rails(mb: MeshBuilder, k0: Int, k1: Int, side: Int) {
        val t = track
        val p = FloatArray(12)
        val a = FloatArray(3); val b = FloatArray(3)
        for (k in k0 until k1) {
            val kk = t.idx(k)
            if (t.tunnel[kk]) continue
            val off = t.wall[kk] + 0.15f
            for ((y0, y1) in arrayOf(0.48f to 0.8f)) {
                t.pos(k * Track.STEP, side * off, y0, a); t.pos((k + 1) * Track.STEP, side * off, y0, b)
                val ay1 = a[1] + (y1 - y0); val by1 = b[1] + (y1 - y0)
                // Facing the road (drawn from both sides; its light is worked out for the road side)
                if (side < 0) { p[0] = a[0]; p[1] = a[1]; p[2] = a[2]; p[3] = b[0]; p[4] = b[1]; p[5] = b[2]; p[6] = b[0]; p[7] = by1; p[8] = b[2]; p[9] = a[0]; p[10] = ay1; p[11] = a[2] }
                else { p[0] = b[0]; p[1] = b[1]; p[2] = b[2]; p[3] = a[0]; p[4] = a[1]; p[5] = a[2]; p[6] = a[0]; p[7] = ay1; p[8] = a[2]; p[9] = b[0]; p[10] = by1; p[11] = b[2] }
                val lamp = lampAt(a[0], a[1], a[2])
                mb.face(p, 0f, 0f, 1f, 0.3f, 0.85f, 0.87f, 0.9f, 0f, lamp, Layer.METAL)
            }
            if (k % 2 == 0) {
                t.pos(k * Track.STEP, side * (off + 0.12f), 0f, a)
                mb.box(a[0], a[2], a[1] - 0.6f, a[1] + 0.75f, 0.07f, 0.07f, t.tx[kk], t.tz[kk], 0.5f, 0.5f, 0.52f, 0f, 0f, Layer.METAL, null)
            }
        }
    }

    /** The start/finish line: a band across the road with a chequered pattern. */
    private fun startLine(mb: MeshBuilder) {
        val t = track
        val hw = t.hw[0]
        val n = 16
        val a = FloatArray(3); val b = FloatArray(3); val c = FloatArray(3); val d = FloatArray(3)
        for (row in 0 until 2) for (i in 0 until n) {
            val d0 = -hw + 2 * hw * i / n; val d1 = -hw + 2 * hw * (i + 1) / n
            val s0 = row * 0.9f; val s1 = s0 + 0.9f
            t.pos(s0, d0, 0.03f, a); t.pos(s0, d1, 0.03f, b); t.pos(s1, d1, 0.03f, c); t.pos(s1, d0, 0.03f, d)
            val w = if ((i + row) % 2 == 0) 0.92f else 0.08f
            mb.face(floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]), 0f, 0f, 1f, 1f, w, w, w, 0f, lampAt(a[0], a[1], a[2]), Layer.WHITE)
        }
        // Gantry over the line
        for (s in intArrayOf(-1, 1)) {
            t.pos(0.45f, s * (t.wall[0] + 0.6f), 0f, a)
            mb.box(a[0], a[2], a[1] - 0.5f, a[1] + 7f, 0.3f, 0.3f, t.tx[0], t.tz[0], 0.25f, 0.27f, 0.3f, 0f, 0f, Layer.METAL, Layer.METAL)
        }
        t.pos(0.45f, 0f, 6.4f, a)
        mb.box(a[0], a[2], a[1], a[1] + 1.2f, 0.35f, t.wall[0] + 0.9f, t.tx[0], t.tz[0], 0.12f, 0.12f, 0.14f, 0f, 0f, Layer.METAL, Layer.METAL)
        // A strip of light along its front
        t.pos(0.0f, 0f, 6.95f, a)
        mb.box(a[0], a[2], a[1] - 0.15f, a[1] + 0.15f, 0.05f, t.wall[0] + 0.6f, t.tx[0], t.tz[0], 1f, 0.35f, 0.15f, 1f, 0f, Layer.WHITE, null)
    }

    /** Lamp posts: a pole, an arm over the road, and a lamp whose underside glows. */
    private fun lampPosts(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        for (q in 0 until lampIdx.n) {
            val k = lampIdx.a[q]
            if (k < k0 || k >= k1) continue
            val side = lampSides.a[q]
            val hw = t.hw[k]
            val poleD = side * (hw + (if (scene == Scene.CITY) 1.0f else 1.9f))
            t.pos(k * Track.STEP, poleD, 0f, tmp)
            val px = tmp[0]; val py = tmp[1]; val pz = tmp[2]
            val fx = t.tx[k]; val fz = t.tz[k]
            mb.box(px, pz, py - 1f, py + 9f, 0.11f, 0.11f, fx, fz, 0.32f, 0.33f, 0.36f, 0f, 0.3f, Layer.METAL, Layer.METAL)
            // Arm reaching over the road
            val armD = side * (hw - 2.4f)
            t.pos(k * Track.STEP, (poleD + armD) / 2, 8.9f, tmp)
            val armLen = abs(poleD - armD) / 2 + 0.2f
            mb.box(tmp[0], tmp[2], tmp[1], tmp[1] + 0.14f, 0.07f, armLen, fx, fz, 0.32f, 0.33f, 0.36f, 0f, 0.3f, Layer.METAL, Layer.METAL)
            // Lamp head (its glowing underside faces down)
            t.pos(k * Track.STEP, armD, 8.6f, tmp)
            mb.box(tmp[0], tmp[2], tmp[1], tmp[1] + 0.32f, 0.5f, 0.25f, fx, fz, 0.2f, 0.2f, 0.22f, 0f, 0.3f, Layer.METAL, Layer.METAL)
            val hx = 0.45f; val hz = 0.2f
            val rx = -fz; val rz = fx
            val y = tmp[1] - 0.01f
            val p = floatArrayOf(
                tmp[0] - fx * hx - rx * hz, y, tmp[2] - fz * hx - rz * hz,
                tmp[0] - fx * hx + rx * hz, y, tmp[2] - fz * hx + rz * hz,
                tmp[0] + fx * hx + rx * hz, y, tmp[2] + fz * hx + rz * hz,
                tmp[0] + fx * hx - rx * hz, y, tmp[2] + fz * hx - rz * hz)
            mb.face(p, 0f, 0f, 1f, 1f, 1f, 0.82f, 0.55f, 1f, 0f, Layer.WHITE)
        }
    }

    /** The tunnel: walls and a ceiling over the road, with portals at each end. */
    private fun tunnelPart(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        val a = FloatArray(3)
        for (side in intArrayOf(-1, 1)) {
            val base = mb.count
            var rows = 0
            for (k in k0..k1) {
                val kk = t.idx(k)
                if (!t.tunnel[kk] && !t.tunnel[t.idx(k - 1)]) continue
                val w = t.wall[kk] + 0.2f
                // Wall up from the shoulder, curving into the ceiling
                val pts = floatArrayOf(w, -0.3f, w, 4.6f, w * 0.75f, 6.6f, 0f, 7.3f)
                for (q in 0 until 4) {
                    t.pos(k * Track.STEP, side * pts[q * 2], pts[q * 2 + 1], a)
                    mb.vert(a[0], a[1], a[2], 0f, 1f, 0f, k * Track.STEP / 4f, q * 1.5f, 0.85f, 0.85f, 0.82f, 0f, lampAt(a[0], a[1] - 4f, a[2]), Layer.TUNNEL)
                }
                rows++
            }
            for (r in 0 until rows - 1) for (q in 0 until 3) {
                val i0 = base + r * 4 + q
                if (side > 0) mb.quad(i0, i0 + 1, i0 + 5, i0 + 4) else mb.quad(i0, i0 + 4, i0 + 5, i0 + 1)
            }
            if (rows > 1) fixNormals(mb, base, rows * 4)
        }
        // Its lights: glowing panels along the ceiling
        for (k in k0 until k1) {
            val kk = t.idx(k)
            if (!t.tunnel[kk] || k % 9 != 0) continue
            t.pos(k * Track.STEP, 0f, 7.05f, a)
            mb.box(a[0], a[2], a[1], a[1] + 0.2f, 1.4f, 0.35f, t.tx[kk], t.tz[kk], 1f, 0.92f, 0.75f, 1f, 0f, Layer.WHITE, null)
        }
        // Portals: a concrete face around each end
        for (k in k0 until k1) {
            val kk = t.idx(k)
            val enter = t.tunnel[kk] && !t.tunnel[t.idx(k - 1)]
            val exit = !t.tunnel[kk] && t.tunnel[t.idx(k - 1)]
            if (!enter && !exit) continue
            t.pos(k * Track.STEP, 0f, 0f, a)
            val w = t.wall[kk] + 0.2f
            val fx = t.tx[kk]; val fz = t.tz[kk]
            // Above the opening, and either side of it
            mb.box(a[0], a[2], a[1] + 7.3f, a[1] + 22f, 0.8f, w + 14f, fx, fz, 0.6f, 0.6f, 0.58f, 0f, 0f, Layer.CONCRETE, Layer.CONCRETE, 2f)
            for (s in intArrayOf(-1, 1)) {
                t.pos(k * Track.STEP, s * (w + 7.2f), 0f, a)
                mb.box(a[0], a[2], a[1] - 2f, a[1] + 7.4f, 0.8f, 7f, fx, fz, 0.6f, 0.6f, 0.58f, 0f, 0f, Layer.CONCRETE, Layer.CONCRETE, 2f)
            }
        }
    }

    /** Where the road is high above the ground: a bridge deck with pillars and concrete parapets. */
    private fun bridgePart(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        val a = FloatArray(3)
        for (k in k0 until k1) {
            val kk = t.idx(k)
            if (!t.bridge[kk]) continue
            t.pos(k * Track.STEP, 0f, 0f, a)
            val fx = t.tx[kk]; val fz = t.tz[kk]
            // Deck underside
            mb.box(a[0], a[2], a[1] - 1.6f, a[1] - 0.2f, Track.STEP * 0.55f, t.wall[kk] + 0.9f, fx, fz, 0.55f, 0.55f, 0.53f, 0f, 0f, Layer.CONCRETE, null, 3f)
            if (k % 20 == 0) {
                // Pillars down to the ground (or sea floor)
                val g = naturalUnder(a[0], a[2])
                for (s in intArrayOf(-1, 1)) {
                    t.pos(k * Track.STEP, s * (t.hw[kk] - 2f), 0f, a)
                    mb.box(a[0], a[2], g - 2f, a[1] - 1.5f, 1.1f, 1.1f, fx, fz, 0.6f, 0.6f, 0.58f, 0f, 0f, Layer.CONCRETE, null, 3f)
                }
            }
        }
    }

    /** Where the road runs high over the land or the sea, it's a bridge (short gaps filled, tiny ones dropped). */
    private fun markBridges() {
        val t = track
        val a = FloatArray(3)
        for (k in 0 until t.n) {
            t.pos(k * Track.STEP, 0f, 0f, a)
            t.bridge[k] = !t.tunnel[k] && a[1] - naturalUnder(a[0], a[2]) > 14f
        }
        fun runs(v: Boolean, maxLen: Int) {
            var k = 0
            while (k < t.n) {
                if (t.bridge[k] == v) { k++; continue }
                var e = k; while (e < t.n && t.bridge[e] != v) e++
                if (e - k < maxLen && k > 0 && e < t.n) for (q in k until e) t.bridge[q] = v
                k = e
            }
        }
        runs(true, 20)  // fill short gaps in a bridge
        runs(false, 15) // drop bits too short to be a bridge
    }

    /** The ground below a point, ignoring the shaping around the road (so a bridge knows how high it is). */
    private fun naturalUnder(x: Float, z: Float) = if (hasWater) max(natural(x, z), waterLevel - 6f) else natural(x, z)

    // ------------------------------------------------------------------ buildings

    private val taken = HashSet<Long>()
    private fun cellKey(x: Float, z: Float) = lkey(floor(x / 5f).toInt(), floor(z / 5f).toInt())

    /** Is the footprint (centre, half-length along f, half-width) clear of the road and of other buildings? */
    private fun footprintFree(x: Float, z: Float, hl: Float, hwid: Float, fx: Float, fz: Float, clearance: Float, mark: Boolean): Boolean {
        val rx = -fz; val rz = fx
        var a = -hl
        val keys = ArrayList<Long>()
        while (a <= hl) {
            var b = -hwid
            while (b <= hwid) {
                val px = x + fx * a + rx * b; val pz = z + fz * a + rz * b
                if (track.nearest(px, pz, track.hw[0] + clearance)) return false
                val key = cellKey(px, pz)
                if (key in taken) return false
                keys.add(key)
                b += 4f
            }
            a += 4f
        }
        if (mark) taken.addAll(keys)
        return true
    }

    /** City: rows of buildings along both sides, a second, taller row behind, all different. */
    private fun buildings(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        for (side in intArrayOf(-1, 1)) for (row in 0..1) {
            var k = k0
            while (k < k1) {
                val front = 10f + rnd.nextFloat() * 16f // half-length along the street
                val depth = 9f + rnd.nextFloat() * 10f
                val gapS = 1f + rnd.nextFloat() * 3f
                val kk = t.idx(k + (front / Track.STEP).toInt())
                val setback = t.hw[kk] + 5.2f + depth + row * 32f
                t.pos(kk * Track.STEP, side * setback, 0f, tmp)
                val fx = t.tx[kk]; val fz = t.tz[kk]
                // Taller towards the middle of the city, the back row taller still
                val dc = hypot(tmp[0] - 290f, tmp[2] - 260f)
                val tall = smooth(520f, 80f, dc)
                var h = 12f + rnd.nextFloat() * 22f + tall * (20f + rnd.nextFloat() * 70f)
                if (row == 1) h *= 1.25f
                if (footprintFree(tmp[0], tmp[2], front, depth, fx, fz, if (row == 0) 4.6f else 4.6f, true)) {
                    val style = when {
                        h > 55f -> if (rnd.nextFloat() < 0.6f) 0 else 3
                        h > 30f -> if (rnd.nextFloat() < 0.5f) 2 else 3
                        else -> if (rnd.nextFloat() < 0.6f) 1 else 2
                    }
                    val y0 = t.cy[kk] - 4f
                    val tint = 0.85f + rnd.nextFloat() * 0.15f
                    val lampL = lampAt(tmp[0] - side * (-fz) * depth, t.cy[kk] + 1f, tmp[2] - side * fx * depth)
                    mb.box(tmp[0], tmp[2], y0, t.cy[kk] + h, front, depth, fx, fz, tint, tint, tint, 0f, lampL * 0.6f, Layer.FACADE0 + style, Layer.WHITE, 16f, 0.22f, 0.22f, 0.24f)
                    // Roof clutter
                    if (h > 25f) {
                        val ux = tmp[0] + fx * (rnd.nextFloat() - 0.5f) * front; val uz = tmp[2] + fz * (rnd.nextFloat() - 0.5f) * front
                        mb.box(ux, uz, t.cy[kk] + h, t.cy[kk] + h + 3f, 3f, 2.5f, fx, fz, 0.35f, 0.36f, 0.38f, 0f, 0f, Layer.METAL, Layer.METAL)
                    }
                    // Tall towers: a red light on top (for aircraft)
                    if (h > 70f) {
                        mb.box(tmp[0], tmp[2], t.cy[kk] + h, t.cy[kk] + h + 0.8f, 0.4f, 0.4f, fx, fz, 1f, 0.12f, 0.08f, 1f, 0f, Layer.WHITE, Layer.WHITE)
                        beacons.add(tmp[0]); beacons.add(t.cy[kk] + h + 0.6f); beacons.add(tmp[2])
                    }
                }
                k += ((front * 2 + gapS) / Track.STEP).toInt().coerceAtLeast(2)
            }
        }
    }
    /** Tops of tall towers (x, y, z): small red lights that glow at night. */
    val beacons = FloatList(64)

    /** Coast: low houses on the land side now and then. */
    private fun villas(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        var k = k0 + rnd.nextInt(6)
        while (k < k1) {
            if (!t.bridge[k] && rnd.nextFloat() < 0.35f) {
                val front = 6f + rnd.nextFloat() * 6f; val depth = 5f + rnd.nextFloat() * 5f
                val off = t.hw[k] + 18f + rnd.nextFloat() * 30f
                t.pos(k * Track.STEP, off, 0f, tmp) // land side (right) on the coast road
                val g = groundAt(tmp[0], tmp[2])
                if (g > 1f && footprintFree(tmp[0], tmp[2], front, depth, t.tx[k], t.tz[k], 9f, true)) {
                    val h = 5f + rnd.nextFloat() * 6f
                    val style = if (rnd.nextBoolean()) 1 else 2
                    mb.box(tmp[0], tmp[2], g - 2f, g + h, front, depth, t.tx[k], t.tz[k], 1f, 0.97f, 0.92f, 0f, 0f, Layer.FACADE0 + style, Layer.WHITE, 16f, 0.55f, 0.3f, 0.22f)
                }
            }
            k += 8
        }
    }

    // ------------------------------------------------------------------ trees

    /** Trees: crossed cut-out pictures (pines in the mountains, palms by the sea), never on the road. */
    private fun trees(mb: MeshBuilder, k0: Int, k1: Int) {
        val t = track
        val per = if (scene == Scene.MOUNTAIN) 9 else 3
        for (k in k0 until k1) {
            if (t.tunnel[k]) continue
            for (q in 0 until per) {
                if (rnd.nextFloat() > 0.5f) continue
                val side = if (rnd.nextBoolean()) 1f else -1f
                if (scene == Scene.COAST && side > 0 && rnd.nextFloat() < 0.7f) continue // palms mostly on the sea side
                val off = t.wall[k] + 3.5f + rnd.nextFloat().let { it * it } * (if (scene == Scene.MOUNTAIN) 90f else 40f)
                val sa = k * Track.STEP + rnd.nextFloat() * Track.STEP
                t.pos(sa, side * off, 0f, tmp)
                if (track.nearest(tmp[0], tmp[2], t.hw[k] + 3f)) continue
                val g = groundAt(tmp[0], tmp[2])
                if (hasWater && g < waterLevel + 0.8f) continue
                if (scene == Scene.MOUNTAIN && abs(g - groundAt(tmp[0] + 4f, tmp[2])) > 3.5f) continue // not on cliffs
                val kind = when (scene) { Scene.MOUNTAIN -> if (rnd.nextFloat() < 0.85f) 0 else 2; Scene.COAST -> if (rnd.nextFloat() < 0.75f) 1 else 3; else -> 2 }
                val h = when (kind) { 0 -> 9f + rnd.nextFloat() * 9f; 1 -> 8f + rnd.nextFloat() * 5f; 2 -> 6f + rnd.nextFloat() * 5f; else -> 1.6f + rnd.nextFloat() }
                tree(mb, tmp[0], g - 0.3f, tmp[2], h, kind, lampAt(tmp[0], g + 2f, tmp[2]))
            }
        }
    }

    private fun tree(mb: MeshBuilder, x: Float, y: Float, z: Float, h: Float, kind: Int, lamp: Float) {
        val w = h * (if (kind == 1) 0.75f else 0.5f)
        val u0 = (kind % 2) * 0.5f; val v0 = (kind / 2) * 0.5f
        val tint = 0.8f + rnd.nextFloat() * 0.25f
        val a0 = rnd.nextFloat() * PI_F
        for (p in 0 until 2) {
            val a = a0 + p * PI_F / 2
            val dx = cos(a) * w; val dz = sin(a) * w
            val base = mb.count
            // Upward normals, so the leaves are lit evenly from any side
            mb.vert(x - dx, y, z - dz, 0f, 1f, 0f, u0 + 0.004f, v0 + 0.496f, tint, tint, tint, 0f, lamp, Layer.FOLIAGE)
            mb.vert(x + dx, y, z + dz, 0f, 1f, 0f, u0 + 0.496f, v0 + 0.496f, tint, tint, tint, 0f, lamp, Layer.FOLIAGE)
            mb.vert(x + dx, y + h, z + dz, 0f, 1f, 0f, u0 + 0.496f, v0 + 0.004f, tint, tint, tint, 0f, lamp, Layer.FOLIAGE)
            mb.vert(x - dx, y + h, z - dz, 0f, 1f, 0f, u0 + 0.004f, v0 + 0.004f, tint, tint, tint, 0f, lamp, Layer.FOLIAGE)
            mb.quad(base, base + 1, base + 2, base + 3)
        }
    }

    // ------------------------------------------------------------------ far skyline

    /** A ring far away: a city skyline or mountain ridges, so the horizon is never empty. */
    private fun buildSkyline(): Mesh {
        val mb = MeshBuilder()
        val r = 2600f
        val segs = 48
        val y0 = if (scene == Scene.MOUNTAIN) 40f else -30f
        val hgt = when (scene) { Scene.CITY -> 330f; Scene.COAST -> 260f; Scene.MOUNTAIN -> 700f }
        for (i in 0..segs) {
            val a = i.toFloat() / segs * TWO_PI_F
            val x = centerX + cos(a) * r; val z = centerZ + sin(a) * r
            val u = i.toFloat() / segs * 4f
            mb.vert(x, y0, z, -cos(a), 0f, -sin(a), u, 1f, 1f, 1f, 1f, 0f, 0f, Layer.SKYLINE)
            mb.vert(x, y0 + hgt, z, -cos(a), 0f, -sin(a), u, 0f, 1f, 1f, 1f, 0f, 0f, Layer.SKYLINE)
        }
        for (i in 0 until segs) { val a = i * 2; mb.quad(a, a + 1, a + 3, a + 2) }
        return mb.build()
    }
}
