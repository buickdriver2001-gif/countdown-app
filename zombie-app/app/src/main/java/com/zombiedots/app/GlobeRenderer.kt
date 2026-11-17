package com.zombiedots.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws the game on a spinning planet. The flat map wraps left-right around the globe;
 * its top and bottom edges sit at 60° north/south, beyond which are the polar ice caps.
 */
class GlobeRenderer(private val world: World, private val d: Float) {

    companion object {
        const val WORLD_W = 2200f
        const val WORLD_H = 740f
        val LAT_SPAN = (120.0 * PI / 180.0).toFloat() // the map covers 60°S..60°N
    }

    // Camera
    var yaw = 0f
    var pitch = 0.25f
    var zoom = 1f
    var lastTouch = 0L

    var cx = 0f
    var cy = 0f
    var radius = 1f
    /** Screen pixels per world unit at the equator. */
    val scale get() = radius * 2f * PI.toFloat() / WORLD_W

    private val out = FloatArray(3)

    /** World (x, y) to screen. Result in out[0], out[1]; out[2] = depth (0 at the edge, 1 facing you). */
    fun project(x: Float, y: Float): Boolean {
        val lon = x / WORLD_W * 2f * PI.toFloat() - PI.toFloat()
        val lat = (0.5f - y / WORLD_H) * LAT_SPAN
        return projectLL(lon, lat)
    }

    fun projectLL(lon: Float, lat: Float): Boolean {
        val cl = cos(lat)
        val x = cl * sin(lon); val y = sin(lat); val z = cl * cos(lon)
        val cyw = cos(yaw); val syw = sin(yaw)
        val x1 = x * cyw + z * syw
        val z1 = -x * syw + z * cyw
        val cp = cos(pitch); val sp = sin(pitch)
        val y2 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp
        out[0] = cx + x1 * radius
        out[1] = cy - y2 * radius
        out[2] = z2
        return z2 > 0f
    }

    val sx get() = out[0]
    val sy get() = out[1]
    val depth get() = out[2]

    /** Screen point to world (x, y), or null if it misses the planet or lands on the ice caps. */
    fun unproject(px: Float, py: Float): Pair<Float, Float>? {
        val x1 = (px - cx) / radius
        val y2 = -(py - cy) / radius
        val r2 = x1 * x1 + y2 * y2
        if (r2 > 1f) return null
        val z2 = sqrt(1f - r2)
        val cp = cos(pitch); val sp = sin(pitch)
        val y = y2 * cp + z2 * sp
        val z1 = -y2 * sp + z2 * cp
        val cyw = cos(yaw); val syw = sin(yaw)
        val x = x1 * cyw - z1 * syw
        val z = x1 * syw + z1 * cyw
        val lat = asin(y.coerceIn(-1f, 1f))
        val lon = atan2(x, z)
        if (abs(lat) > LAT_SPAN / 2f) return null
        val wxv = (lon + PI.toFloat()) / (2f * PI.toFloat()) * WORLD_W
        val wyv = (0.5f - lat / LAT_SPAN) * WORLD_H
        return wxv to wyv.coerceIn(5f, WORLD_H - 5f)
    }

    // ---------------------------------------------------------------- camera movement

    fun drag(dx: Float, dy: Float) {
        yaw += dx / radius
        pitch = (pitch + dy / radius).coerceIn(-1.2f, 1.2f)
        lastTouch = System.currentTimeMillis()
    }

    fun pinch(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.7f, 3.5f)
        lastTouch = System.currentTimeMillis()
    }

    /** After a few seconds without touching, gently turn to follow the team. */
    fun follow(dt: Float) {
        if (System.currentTimeMillis() - lastTouch < 4000) return
        val l = world.leader ?: return
        val lon = l.x / WORLD_W * 2f * PI.toFloat() - PI.toFloat()
        val lat = (0.5f - l.y / WORLD_H) * LAT_SPAN
        var dyaw = -lon - yaw
        while (dyaw > PI) dyaw -= (2 * PI).toFloat()
        while (dyaw < -PI) dyaw += (2 * PI).toFloat()
        val k = min(1f, dt * 1.5f)
        yaw += dyaw * k
        pitch += (lat.coerceIn(-1.2f, 1.2f) - pitch) * k
    }

    // ---------------------------------------------------------------- planet scenery

    private val land = ArrayList<FloatArray>() // [lon, lat]
    private val ice = ArrayList<FloatArray>()
    private val stars = ArrayList<FloatArray>() // screen fractions
    private var sceneryReady = false

    private fun buildScenery() {
        sceneryReady = true
        val r = Random(7)
        // Continents: blobs of dots around random centres
        repeat(9) {
            val clon = (r.nextFloat() * 2f - 1f) * PI.toFloat()
            val clat = (r.nextFloat() - 0.5f) * 1.6f
            val size = 0.25f + r.nextFloat() * 0.35f
            repeat(170) {
                val a = r.nextFloat() * 2f * PI.toFloat()
                val dist = size * sqrt(r.nextFloat()) * (0.7f + 0.3f * sin(a * 3f + clon))
                land.add(floatArrayOf(clon + cos(a) * dist / max(0.3f, cos(clat)), (clat + sin(a) * dist).coerceIn(-1.5f, 1.5f)))
            }
        }
        // Polar ice caps beyond 60°
        repeat(700) {
            val north = r.nextBoolean()
            val lat = (LAT_SPAN / 2f + r.nextFloat() * (PI.toFloat() / 2f - LAT_SPAN / 2f)) * (if (north) 1f else -1f)
            ice.add(floatArrayOf((r.nextFloat() * 2f - 1f) * PI.toFloat(), lat))
        }
        repeat(160) { stars.add(floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat())) }
    }

    private val space = Paint().apply { color = Color.parseColor("#04050A") }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val atmo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ocean = Paint(Paint.ANTI_ALIAS_FLAG)
    private val landPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pts = FloatArray(4096)
    private var lastR = -1f

    fun layout(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) * 0.43f * zoom
    }

    /** Planet body, scenery and grid (drawn under the dots). */
    fun drawPlanet(c: Canvas, w: Int, h: Int) {
        if (!sceneryReady) buildScenery()
        layout(w, h)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), space)
        for (s in stars) {
            starPaint.alpha = (60 + 120 * s[2]).toInt()
            starPaint.strokeWidth = (0.8f + s[2] * 1.4f) * d
            c.drawPoint(s[0] * w, s[1] * h, starPaint)
        }
        if (radius != lastR) {
            lastR = radius
            atmo.shader = RadialGradient(cx, cy, radius * 1.12f,
                intArrayOf(Color.argb(0, 80, 160, 255), Color.argb(0, 80, 160, 255), Color.argb(110, 80, 160, 255), Color.argb(0, 80, 160, 255)),
                floatArrayOf(0f, 0.86f, 0.9f, 1f), Shader.TileMode.CLAMP)
            ocean.shader = RadialGradient(cx - radius * 0.35f, cy - radius * 0.4f, radius * 1.5f,
                Color.parseColor("#1B3A66"), Color.parseColor("#060C1C"), Shader.TileMode.CLAMP)
            shade.shader = RadialGradient(cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, radius * 1.12f, atmo)
        c.drawCircle(cx, cy, radius, ocean)

        // Land and ice
        drawDots(c, land, Color.parseColor("#2E6B4A"), 3.2f)
        drawDots(c, ice, Color.parseColor("#DCE8F5"), 3.4f)

        // Latitude / longitude grid
        gridPaint.strokeWidth = 1f * d
        for (latDeg in listOf(-60, -30, 0, 30, 60)) {
            gridPaint.color = if (abs(latDeg) == 60) Color.argb(110, 200, 230, 255) else Color.argb(40, 150, 200, 255)
            val lat = (latDeg * PI / 180).toFloat()
            polyline(c, 72) { t -> projectLL(-PI.toFloat() + t * 2f * PI.toFloat(), lat) }
        }
        gridPaint.color = Color.argb(40, 150, 200, 255)
        for (lonDeg in 0 until 360 step 30) {
            val lon = (lonDeg * PI / 180).toFloat()
            polyline(c, 40) { t -> projectLL(lon, -PI.toFloat() / 2f + t * PI.toFloat()) }
        }
    }

    /** Darkens the planet's edge (drawn over the dots for a round look). */
    fun drawShading(c: Canvas) {
        c.drawCircle(cx, cy, radius, shade)
    }

    private fun drawDots(c: Canvas, list: List<FloatArray>, color: Int, size: Float) {
        if (pts.size < list.size * 2) pts = FloatArray(list.size * 2)
        var k = 0
        for (p in list) {
            if (projectLL(p[0], p[1])) { pts[k++] = sx; pts[k++] = sy }
        }
        landPaint.color = color
        landPaint.strokeWidth = size * d * zoom.coerceAtMost(2f)
        c.drawPoints(pts, 0, k, landPaint)
    }

    private inline fun polyline(c: Canvas, steps: Int, f: (Float) -> Boolean) {
        var px = 0f; var py = 0f; var prevVis = false
        for (i in 0..steps) {
            val vis = f(i.toFloat() / steps)
            val x = sx; val y = sy
            if (vis && prevVis) c.drawLine(px, py, x, y, gridPaint)
            px = x; py = y; prevVis = vis
        }
    }
}
