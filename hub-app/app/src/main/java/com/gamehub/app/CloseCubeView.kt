package com.gamehub.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Shown while NEXUS closes apps fully: a glass cube turning with the closing apps' icons on its faces. As each
 * app closes, its icon peels off the cube and falls away past the bottom of the screen.
 */
class CloseCubeView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density

    /** A face picture: dark glass with an app's icon in the middle (or plain glass). */
    private val faceSize = 192
    private var glass: Bitmap? = null
    private val faces = arrayOfNulls<Bitmap>(6)
    /** Which app is on which face (null: plain glass), and the icons of apps that didn't fit on a face. */
    private val facePkg = arrayOfNulls<String>(6)
    private val spare = LinkedHashMap<String, Bitmap>()

    /** An icon falling away. Its corners start where it was on the cube and settle into a flat tile. */
    private class Piece(val bmp: Bitmap, val start: FloatArray, var cx: Float, var cy: Float, val size: Float,
                        val vx: Float, var vy: Float, val spin: Float) {
        var age = 0f; var ang = 0f
    }
    private val pieces = ArrayList<Piece>()
    private val queuedPeels = ArrayList<String?>()
    private var nextPeelAt = 0L

    private var running = false
    private var shownAt = 0L
    private var lastNs = 0L
    private var t = 0f
    private var cubeAng = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowShader = RadialGradient(0f, 0f, 1f, Color.rgb(60, 120, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val mtx = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val rot = FloatArray(3)
    private val px = FloatArray(8); private val py = FloatArray(8)
    private val faceDepth = FloatArray(6)
    private val sortedFaces = IntArray(6)
    private val flat = FloatArray(8)
    private val corners = arrayOf(floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f))
    private val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
    // Each face: outward direction (n) and its "up" direction (u), so pictures sit the right way round
    private val faceDefs = arrayOf(
        floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f)
    )
    private val rnd = java.util.Random()

    private fun glassFace(): Bitmap = glass ?: Bitmap.createBitmap(faceSize, faceSize, Bitmap.Config.ARGB_8888).also { b ->
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, faceSize.toFloat(), Color.rgb(16, 30, 70), Color.rgb(6, 10, 26), Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, faceSize.toFloat(), faceSize.toFloat(), p)
        glass = b
    }

    private fun iconFace(icon: Drawable?): Bitmap {
        val b = glassFace().copy(Bitmap.Config.ARGB_8888, true)
        if (icon != null) try {
            icon.setBounds(faceSize / 5, faceSize / 5, faceSize * 4 / 5, faceSize * 4 / 5)
            icon.draw(Canvas(b))
        } catch (_: Exception) {}
        return b
    }

    /** Starts the show with these apps' icons on the cube (the side faces first, then the top). */
    fun start(apps: List<Pair<String, Drawable?>>) {
        pieces.clear(); queuedPeels.clear(); spare.clear()
        for (f in 0 until 6) { faces[f] = glassFace(); facePkg[f] = null }
        val order = intArrayOf(0, 1, 2, 3, 4, 5)
        apps.forEachIndexed { i, (pkg, icon) ->
            if (i < 5) { val f = order[i]; faces[f] = iconFace(icon); facePkg[f] = pkg } // (the bottom face never shows)
            else if (spare.size < 6) spare[pkg] = iconFace(icon)
        }
        running = true; shownAt = SystemClock.uptimeMillis(); lastNs = 0L; t = 0f; nextPeelAt = 0L
        visibility = VISIBLE
        invalidate()
    }

    fun stop() { running = false; pieces.clear(); queuedPeels.clear(); visibility = GONE }

    /** [pkg] has been closed: its icon peels off (null: the next icon still on the cube). */
    fun peel(pkg: String?) { queuedPeels.add(pkg); invalidate() }

    /** Everything left peels off, one after another. */
    fun peelAll() {
        val left = facePkg.count { it != null } + spare.size - queuedPeels.size
        repeat(left.coerceAtLeast(0)) { queuedPeels.add(null) }
        invalidate()
    }

    /** When the last icon will be gone from the screen (uptime ms). */
    fun fallsEnd(): Long {
        if (!running) return 0L
        val waiting = queuedPeels.size + (if (pieces.isEmpty()) 0 else 1)
        return SystemClock.uptimeMillis() + if (waiting == 0) 0L else 800L + 110L * queuedPeels.size
    }

    private fun turn(x: Float, y: Float, z: Float, ang: Float) {
        val ca = cos(ang); val sa = sin(ang); val cb = cos(0.5f); val sb = sin(0.5f)
        val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
        rot[0] = x1; rot[1] = y * cb - z1 * sb; rot[2] = y * sb + z1 * cb
    }

    /** Where face [f]'s corners are on screen right now (into [out]); returns how much it faces you (negative = towards you). */
    private fun faceQuad(f: Int, cx: Float, cy: Float, s: Float, out: FloatArray): Float {
        val fd = faceDefs[f]
        turn(fd[0], fd[1], fd[2], cubeAng)
        val depth = rot[2]
        val nx = fd[0]; val ny = fd[1]; val nz = fd[2]; val ux = fd[3]; val uy = fd[4]; val uz = fd[5]
        val rx = ny * uz - nz * uy; val ry = nz * ux - nx * uz; val rz = nx * uy - ny * ux
        for (k in 0 until 4) {
            val cr = corners[k]
            turn(nx + rx * cr[0] + ux * cr[1], ny + ry * cr[0] + uy * cr[1], nz + rz * cr[0] + uz * cr[1], cubeAng)
            val pr = 3.2f / (3.2f + rot[2])
            out[k * 2] = cx + rot[0] * s * pr; out[k * 2 + 1] = cy - rot[1] * s * pr
        }
        return depth
    }

    private fun startPeel(pkg: String?, cx: Float, cy: Float, s: Float) {
        // Which face it comes off (the app's own face; for "next", the one facing you most)
        var f = if (pkg != null) facePkg.indexOf(pkg) else -1
        if (f < 0 && pkg == null) {
            var best = -1; var bestDepth = 9f
            for (i in 0 until 6) if (facePkg[i] != null) {
                val fd = faceDefs[i]; turn(fd[0], fd[1], fd[2], cubeAng)
                if (rot[2] < bestDepth) { bestDepth = rot[2]; best = i }
            }
            f = best
        }
        val bmp: Bitmap
        val start = FloatArray(8)
        var depth = 1f
        if (f >= 0) {
            bmp = faces[f] ?: return
            depth = faceQuad(f, cx, cy, s, start)
            faces[f] = spare.entries.firstOrNull()?.let { e -> spare.remove(e.key); facePkg[f] = e.key; e.value } ?: run { facePkg[f] = null; glassFace() }
        } else {
            // Not on a face (one that didn't fit, or already gone): it pops out of the cube
            val key = if (pkg != null) (pkg.takeIf { spare.containsKey(it) } ?: return) else (spare.keys.firstOrNull() ?: return)
            bmp = spare.remove(key) ?: return
        }
        if (f < 0 || depth > -0.05f) {
            // Out of the cube's middle, small, so it never appears from behind
            val r = s * 0.3f
            start[0] = cx - r; start[1] = cy - r; start[2] = cx + r; start[3] = cy - r
            start[4] = cx + r; start[5] = cy + r; start[6] = cx - r; start[7] = cy + r
        }
        val mx = (start[0] + start[2] + start[4] + start[6]) / 4f
        val my = (start[1] + start[3] + start[5] + start[7]) / 4f
        val side = (rnd.nextFloat() - 0.5f)
        pieces.add(Piece(bmp, start, mx, my, s * 1.15f,
            vx = side * 260 * d, vy = -(150 + rnd.nextFloat() * 90) * d, spin = (if (side < 0) -1 else 1) * (140 + rnd.nextFloat() * 160)))
    }

    override fun onDraw(c: Canvas) {
        if (!running) return
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now; t += dt
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / 0.28f).let { 1f - (1f - it) * (1f - it) }
        cubeAng += dt * 1.5f
        val cx = w / 2; val cy = h * 0.42f
        val s = min(w, h) * 0.19f * (0.8f + 0.2f * intro)

        // The next icon peels off (a little apart from the one before, so each one is seen)
        val ms = SystemClock.uptimeMillis()
        if (queuedPeels.isNotEmpty() && ms >= nextPeelAt && t > 0.25f) {
            startPeel(queuedPeels.removeAt(0), cx, cy, s)
            nextPeelAt = ms + 110
        }

        // Glow and the cube
        c.save(); c.translate(cx, cy); c.scale(s * 3.2f, s * 3.2f)
        fill.color = Color.WHITE; fill.alpha = (80 * intro).toInt(); fill.shader = glowShader
        c.drawCircle(0f, 0f, 1f, fill); fill.shader = null
        c.restore()
        for (f in 0 until 6) { val fd = faceDefs[f]; turn(fd[0], fd[1], fd[2], cubeAng); faceDepth[f] = rot[2] }
        for (x in 0 until 6) sortedFaces[x] = x
        for (x in 1 until 6) { val v = sortedFaces[x]; var y = x - 1; while (y >= 0 && faceDepth[sortedFaces[y]] < faceDepth[v]) { sortedFaces[y + 1] = sortedFaces[y]; y-- }; sortedFaces[y + 1] = v }
        for (f in sortedFaces) {
            if (faceDepth[f] > -0.05f) continue
            val bmp = faces[f] ?: continue
            faceQuad(f, cx, cy, s, dst)
            src[0] = 0f; src[1] = 0f; src[2] = bmp.width.toFloat(); src[3] = 0f
            src[4] = bmp.width.toFloat(); src[5] = bmp.height.toFloat(); src[6] = 0f; src[7] = bmp.height.toFloat()
            mtx.setPolyToPoly(src, 0, dst, 0, 4)
            facePaint.alpha = (255 * intro * (0.45f + 0.55f * (-faceDepth[f]).coerceIn(0f, 1f))).toInt().coerceIn(0, 255)
            c.drawBitmap(bmp, mtx, facePaint)
        }
        var i = 0
        for (yy in 0..1) for (zz in 0..1) for (xx in 0..1) {
            turn((xx * 2 - 1).toFloat(), (yy * 2 - 1).toFloat(), (zz * 2 - 1).toFloat(), cubeAng)
            val pr = 3.2f / (3.2f + rot[2])
            px[i] = cx + rot[0] * s * pr; py[i] = cy - rot[1] * s * pr
            i++
        }
        line.strokeWidth = 1.4f * d; line.color = Color.argb((230 * intro).toInt(), 170, 210, 255)
        var k = 0
        while (k < edges.size) { c.drawLine(px[edges[k]], py[edges[k]], px[edges[k + 1]], py[edges[k + 1]], line); k += 2 }

        // Falling icons: they come off the face, settle flat, tumble and drop past the bottom
        val g = 2600 * d
        val it = pieces.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            p.vy += g * dt
            p.cx += p.vx * dt; p.cy += p.vy * dt
            p.ang += p.spin * dt
            if (p.cy - p.size > h) { it.remove(); continue }
            val half = p.size / 2
            val ca = cos(Math.toRadians(p.ang.toDouble())).toFloat(); val sa = sin(Math.toRadians(p.ang.toDouble())).toFloat()
            for (q in 0 until 4) {
                val ox = corners[q][0] * half; val oy = -corners[q][1] * half
                flat[q * 2] = p.cx + ox * ca - oy * sa; flat[q * 2 + 1] = p.cy + ox * sa + oy * ca
            }
            val m = min(1f, p.age / 0.18f).let { 1f - (1f - it) * (1f - it) }
            for (q in 0 until 8) {
                // The starting corners travel with the piece, then blend into the flat tile
                val startMoved = p.start[q] + (if (q % 2 == 0) p.cx - (p.start[0] + p.start[2] + p.start[4] + p.start[6]) / 4f
                                               else p.cy - (p.start[1] + p.start[3] + p.start[5] + p.start[7]) / 4f)
                dst[q] = startMoved + (flat[q] - startMoved) * m
            }
            src[0] = 0f; src[1] = 0f; src[2] = p.bmp.width.toFloat(); src[3] = 0f
            src[4] = p.bmp.width.toFloat(); src[5] = p.bmp.height.toFloat(); src[6] = 0f; src[7] = p.bmp.height.toFloat()
            mtx.setPolyToPoly(src, 0, dst, 0, 4)
            facePaint.alpha = 255
            c.drawBitmap(p.bmp, mtx, facePaint)
            // A thin glowing rim, like the cube's edges
            line.strokeWidth = 1.2f * d; line.color = Color.argb(200, 170, 210, 255)
            for (q in 0 until 4) { val n = (q + 1) % 4; c.drawLine(dst[q * 2], dst[q * 2 + 1], dst[n * 2], dst[n * 2 + 1], line) }
        }
        postInvalidateOnAnimation()
    }
}
