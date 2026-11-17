package com.zombiedots.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Draws the game on a massive planet. The map wraps in every direction (no poles, no edges):
 * walk far enough any way and you come back round. The camera looks straight down at a spot
 * on the surface, and everything curves away towards the horizon from there.
 */
class GlobeRenderer(private val world: World, private val d: Float) {

    companion object {
        /** Distance all the way round the planet, in world units (both directions). */
        const val WORLD_W = 6000f
        const val WORLD_H = 6000f
        /** Planet radius in world units. */
        val R = (WORLD_W / (2.0 * PI)).toFloat()
        private const val BASE_SCALE = 0.48f // dp per world unit at zoom 1
    }

    // Camera: the world spot straight below us
    var camX = WORLD_W / 2f
    var camY = WORLD_H / 2f
    var zoom = 1f
    var lastTouch = 0L

    var cx = 0f
    var cy = 0f
    /** Planet radius on screen, in pixels. */
    var radius = 1f
    /** Screen pixels per world unit (near the middle of the view). */
    val scale get() = BASE_SCALE * d * zoom
    private var minZoom = 0.2f

    private val out = FloatArray(3)

    private fun wrapD(v: Float, size: Float): Float {
        var r = v % size
        if (r > size / 2f) r -= size
        if (r < -size / 2f) r += size
        return r
    }

    private fun wrapP(v: Float, size: Float): Float {
        var r = v % size
        if (r < 0f) r += size
        return r
    }

    /** World (x, y) to screen. Result in sx, sy; depth = 1 straight below, 0 at the horizon, < 0 behind. */
    fun project(x: Float, y: Float): Boolean {
        val dx = wrapD(x - camX, WORLD_W)
        val dy = wrapD(y - camY, WORLD_H)
        val s = sqrt(dx * dx + dy * dy)
        if (s < 0.0001f) { out[0] = cx; out[1] = cy; out[2] = 1f; return true }
        val th = s / R
        val k = sin(min(th, PI.toFloat() / 2f)) * radius / s
        out[0] = cx + dx * k
        out[1] = cy + dy * k
        out[2] = cos(th)
        return th < PI.toFloat() / 2f
    }

    val sx get() = out[0]
    val sy get() = out[1]
    val depth get() = out[2]

    /** Screen point to world (x, y), or null if it misses the planet. */
    fun unproject(px: Float, py: Float): Pair<Float, Float>? {
        val ux = (px - cx) / radius
        val uy = (py - cy) / radius
        val r = sqrt(ux * ux + uy * uy)
        if (r >= 0.999f) return null
        if (r < 0.00001f) return camX to camY
        val s = asin(r) * R
        return wrapP(camX + ux / r * s, WORLD_W) to wrapP(camY + uy / r * s, WORLD_H)
    }

    // ---------------------------------------------------------------- camera movement

    fun drag(dx: Float, dy: Float) {
        camX = wrapP(camX - dx / scale, WORLD_W)
        camY = wrapP(camY - dy / scale, WORLD_H)
        lastTouch = System.currentTimeMillis()
    }

    fun pinch(factor: Float) {
        zoom = (zoom * factor).coerceIn(minZoom, 3.5f)
        lastTouch = System.currentTimeMillis()
    }

    /** After a few seconds without touching, glide over to follow the team. */
    fun follow(dt: Float) {
        if (System.currentTimeMillis() - lastTouch < 4000) return
        val l = world.leader ?: return
        val k = min(1f, dt * 1.5f)
        camX = wrapP(camX + wrapD(l.x - camX, WORLD_W) * k, WORLD_W)
        camY = wrapP(camY + wrapD(l.y - camY, WORLD_H) * k, WORLD_H)
    }

    // ---------------------------------------------------------------- scenery

    private val stars = ArrayList<FloatArray>() // screen fractions
    private var sceneryReady = false

    private fun buildScenery() {
        sceneryReady = true
        val r = Random(7)
        repeat(160) { stars.add(floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat())) }
    }

    private val space = Paint().apply { color = Color.parseColor("#04050A") }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private val atmo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ocean = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastR = -1f
    private var lastCx = -1f

    fun layout(w: Int, h: Int) {
        cx = w / 2f
        cy = h / 2f
        // Zoomed all the way out, the whole planet just fits on screen
        minZoom = (min(w, h) * 0.45f) / (R * BASE_SCALE * d)
        if (zoom < minZoom) zoom = minZoom
        radius = R * scale
    }

    /** Planet body and grid (drawn under the dots). */
    fun drawPlanet(c: Canvas, w: Int, h: Int) {
        if (!sceneryReady) buildScenery()
        layout(w, h)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), space)
        for (s in stars) {
            starPaint.alpha = (60 + 120 * s[2]).toInt()
            starPaint.strokeWidth = (0.8f + s[2] * 1.4f) * d
            c.drawPoint(s[0] * w, s[1] * h, starPaint)
        }
        if (radius != lastR || cx != lastCx) {
            lastR = radius; lastCx = cx
            atmo.shader = RadialGradient(cx, cy, radius * 1.08f,
                intArrayOf(Color.argb(0, 80, 160, 255), Color.argb(0, 80, 160, 255), Color.argb(110, 80, 160, 255), Color.argb(0, 80, 160, 255)),
                floatArrayOf(0f, 0.9f, 0.925f, 1f), Shader.TileMode.CLAMP)
            ocean.shader = RadialGradient(cx - radius * 0.3f, cy - radius * 0.35f, radius * 1.5f,
                Color.parseColor("#1B3A66"), Color.parseColor("#060C1C"), Shader.TileMode.CLAMP)
            shade.shader = RadialGradient(cx, cy, radius,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)),
                floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(cx, cy, radius * 1.08f, atmo)
        c.drawCircle(cx, cy, radius, ocean)

        // Faint grid lines on the surface, curving away with the planet
        gridPaint.strokeWidth = 1f * d
        gridPaint.color = Color.argb(40, 150, 200, 255)
        val step = WORLD_W / 24f
        val half = WORLD_W / 4f // only the near side is visible
        val firstX = kotlin.math.floor((camX - half) / step) * step
        val firstY = kotlin.math.floor((camY - half) / step) * step
        var gx = firstX
        while (gx <= camX + half) {
            val lx = gx
            polyline(c, 40) { t -> project(lx, camY - half + t * 2f * half) }
            gx += step
        }
        var gy = firstY
        while (gy <= camY + half) {
            val ly = gy
            polyline(c, 40) { t -> project(camX - half + t * 2f * half, ly) }
            gy += step
        }
    }

    /** Darkens the planet's edge (drawn over the dots for a round look). */
    fun drawShading(c: Canvas) {
        c.drawCircle(cx, cy, radius, shade)
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
