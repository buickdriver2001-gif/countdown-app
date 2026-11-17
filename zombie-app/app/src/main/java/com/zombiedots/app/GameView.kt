package com.zombiedots.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@SuppressLint("ViewConstructor")
class GameView(context: Context) : View(context) {

    private val world = World()
    private val d = resources.displayMetrics.density
    private var lastFrame = 0L
    private var speed = 1
    private var paused = false
    private val prefs = context.getSharedPreferences("zombiedots", Context.MODE_PRIVATE)
    private var best = prefs.getFloat("best", 0f)
    private var savedBest = false
    private var flash = 0f

    // ---------- paints
    private val bg = Color.parseColor("#0B0D10")
    private val gridPaint = Paint().apply { color = Color.parseColor("#13171D"); strokeWidth = 1f }
    private val zombiePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B3B"); strokeCap = Paint.Cap.ROUND; strokeWidth = World.ZOMBIE_R * 2 * d
    }
    private val splatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A0F12") }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFE9A0"); strokeWidth = 1.4f * d }
    private val nukePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8ECF2"); textSize = 14 * d
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val hudDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#8A93A3"); textSize = 12 * d }
    private val nukeReadyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFB13B"); textSize = 12 * d }
    private val tacticPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3DA5FF"); textSize = 12 * d; letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val blastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D8DEE8") }
    private val planeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66000000") }
    private val aimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * d; strokeCap = Paint.Cap.ROUND
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * d, 7 * d), 0f)
    }
    private val aimHead = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aimBand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val aimText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val path = android.graphics.Path()
    private val airReadyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#7FE0FF"); textSize = 12 * d }
    private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1A1F27") }
    private val btnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B3B"); textSize = 40 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL); letterSpacing = 0.08f
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E8ECF2"); textSize = 16 * d; textAlign = Paint.Align.CENTER
    }
    private val centerDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A93A3"); textSize = 13 * d; textAlign = Paint.Align.CENTER
    }
    private val floatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#B0000000") }

    private val zombieColor = Color.parseColor("#FF3B3B")
    private val infPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.parseColor("#9CFF4D"); strokeCap = Paint.Cap.ROUND
    }
    private val infRect = RectF()

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    private val playerColor = Color.parseColor("#FFE066")
    private val hunterColor = Color.parseColor("#3DA5FF")
    private val strayColor = Color.parseColor("#A8B0BF")

    private var points = FloatArray(0)

    // HUD buttons (screen px)
    private val btnPause = RectF()
    private val btnSpeed = RectF()
    private val btnReset = RectF()
    private var topInset = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        world.width = w / d
        world.height = h / d
        val bw = 46 * d
        val bh = 36 * d
        val top = topInset + 10 * d
        btnReset.set(w - 10 * d - bw, top, w - 10 * d, top + bh)
        btnSpeed.set(btnReset.left - 8 * d - bw, top, btnReset.left - 8 * d, top + bh)
        btnPause.set(btnSpeed.left - 8 * d - bw, top, btnSpeed.left - 8 * d, top + bh)
    }

    // ---------- input

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            when {
                btnPause.contains(e.x, e.y) -> { if (world.phase == Phase.RUNNING) paused = !paused; return true }
                btnSpeed.contains(e.x, e.y) -> { speed = when (speed) { 1 -> 2; 2 -> 4; else -> 1 }; return true }
                btnReset.contains(e.x, e.y) -> { world.reset(); paused = false; savedBest = false; return true }
            }
            when (world.phase) {
                Phase.SETUP -> { world.start(e.x / d, e.y / d); savedBest = false; paused = false }
                Phase.OVER -> world.reset()
                Phase.RUNNING -> {}
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (inButton(e)) return true
            if (world.phase == Phase.RUNNING && !paused) world.dropZombies(e.x / d, e.y / d)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (inButton(e)) return true
            if (world.phase == Phase.RUNNING && !paused) {
                if (world.nuke(e.x / d, e.y / d)) {
                    flash = 1f
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            }
            return true
        }
    })

    private fun inButton(e: MotionEvent) =
        btnPause.contains(e.x, e.y) || btnSpeed.contains(e.x, e.y) || btnReset.contains(e.x, e.y)

    // Airstrike aiming (screen px)
    private var aiming = false
    private var aimSX = 0f
    private var aimSY = 0f
    private var aimEX = 0f
    private var aimEY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                aiming = false
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true; aiming = false }
            MotionEvent.ACTION_MOVE -> {
                if (!multiTouch && world.phase == Phase.RUNNING && !paused) {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (!aiming && dx * dx + dy * dy > (22 * d) * (22 * d) && !inButtonAt(downX, downY)) {
                        aiming = true
                        aimSX = downX; aimSY = downY
                    }
                    if (aiming) { aimEX = event.x; aimEY = event.y }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (aiming) {
                    aiming = false
                    if (world.airstrike(aimSX / d, aimSY / d, aimEX / d, aimEY / d)) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        gestures.onTouchEvent(event)
        return true
    }

    private fun inButtonAt(x: Float, y: Float) =
        btnPause.contains(x, y) || btnSpeed.contains(x, y) || btnReset.contains(x, y)

    // ---------- loop

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 0.05f)
        lastFrame = now
        if (!paused) world.update(dt * speed)
        if (flash > 0f) flash = max(0f, flash - dt * 2.2f)

        if (world.phase == Phase.OVER && !savedBest) {
            savedBest = true
            if (world.time > best) {
                best = world.time
                prefs.edit().putFloat("best", best).apply()
            }
        }

        drawWorld(canvas)
        drawHud(canvas)
        when (world.phase) {
            Phase.SETUP -> drawSetup(canvas)
            Phase.OVER -> drawOver(canvas)
            Phase.RUNNING -> if (paused) drawPaused(canvas)
        }
        postInvalidateOnAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        lastFrame = 0L
    }

    // ---------- drawing

    private fun drawWorld(c: Canvas) {
        c.drawColor(bg)
        val step = 40 * d
        var x = 0f
        while (x < width) { c.drawLine(x, 0f, x, height.toFloat(), gridPaint); x += step }
        var y = 0f
        while (y < height) { c.drawLine(0f, y, width.toFloat(), y, gridPaint); y += step }

        for (s in world.splats) {
            splatPaint.alpha = (min(1f, s.life) * 200).toInt()
            c.drawCircle(s.x * d, s.y * d, s.size * d, splatPaint)
        }

        // Nuke scorch marks and blasts
        for (n in world.nukes) {
            val cx = n.x * d; val cy = n.y * d
            val r = (kotlin.math.hypot(width.toDouble(), height.toDouble()) * 1.05).toFloat()
            val scorch = (1f - n.age / 6f).coerceIn(0f, 1f)
            nukePaint.style = Paint.Style.FILL
            nukePaint.color = Color.argb((60 * scorch).toInt(), 60, 30, 10)
            c.drawCircle(cx, cy, r * 0.85f, nukePaint)
            if (n.age < 0.9f) {
                val p = n.age / 0.9f
                nukePaint.color = Color.argb(((1f - p) * 230).toInt(), 255, 240, 200)
                c.drawCircle(cx, cy, r * (0.2f + 0.5f * p), nukePaint)
                nukePaint.style = Paint.Style.STROKE
                nukePaint.strokeWidth = 6 * d * (1f - p) + 1f
                nukePaint.color = Color.argb(((1f - p) * 255).toInt(), 255, 150, 40)
                c.drawCircle(cx, cy, r * p, nukePaint)
            }
        }

        // Zombies, drawn in one batch
        val zs = world.zombies
        if (points.size < zs.size * 2) points = FloatArray(zs.size * 4)
        for (i in zs.indices) {
            points[i * 2] = zs[i].x * d
            points[i * 2 + 1] = zs[i].y * d
        }
        c.drawPoints(points, 0, zs.size * 2, zombiePaint)

        for (t in world.tracers) {
            tracerPaint.alpha = (t.life / 0.12f * 255).toInt().coerceIn(0, 255)
            c.drawLine(t.x1 * d, t.y1 * d, t.x2 * d, t.y2 * d, tracerPaint)
        }

        for (h in world.humans) {
            val base = when (h.role) { Role.PLAYER -> playerColor; Role.HUNTER -> hunterColor; Role.STRAY -> strayColor }
            // Infected dots drift toward zombie red
            val inf = h.infection.coerceIn(0f, 1f)
            val col = blend(base, zombieColor, inf * 0.85f)
            val hx = h.x * d; val hy = h.y * d
            glowPaint.color = if (h.hurtFlash > 0f) zombieColor else col
            glowPaint.alpha = (55 + 120 * h.hurtFlash).toInt().coerceAtMost(255)
            c.drawCircle(hx, hy, 10 * d, glowPaint)
            corePaint.color = col
            c.drawCircle(hx, hy, World.HUMAN_R * d, corePaint)
            if (inf > 0.01f) {
                infRect.set(hx - 8 * d, hy - 8 * d, hx + 8 * d, hy + 8 * d)
                c.drawArc(infRect, -90f, 360f * inf, false, infPaint)
            }
        }

        for (b in world.blasts) {
            val p = (b.age / 0.7f).coerceIn(0f, 1f)
            val r = World.BOMB_RADIUS * d * (0.35f + 0.65f * kotlin.math.sqrt(p))
            blastPaint.style = Paint.Style.FILL
            blastPaint.color = Color.argb(((1f - p) * 200).toInt(), 255, 140, 40)
            c.drawCircle(b.x * d, b.y * d, r, blastPaint)
            blastPaint.color = Color.argb(((1f - p) * 255).toInt().coerceAtLeast(0), 255, 240, 200)
            c.drawCircle(b.x * d, b.y * d, r * 0.4f * (1f - p), blastPaint)
        }

        for (a in world.airstrikes) drawPlane(c, a.planeX * d, a.planeY * d, a.dx, a.dy)

        if (aiming) drawAim(c)

        for (t in world.texts) {
            when {
                t.text.startsWith("WAVE") -> { floatPaint.color = Color.parseColor("#FF3B3B"); floatPaint.textSize = 28 * d }
                t.text.startsWith("Survivor Joined") -> { floatPaint.color = hunterColor; floatPaint.textSize = 17 * d }
                t.text.endsWith("turned!") -> { floatPaint.color = Color.parseColor("#9CFF4D"); floatPaint.textSize = 15 * d }
                t.text.startsWith("+") -> { floatPaint.color = Color.parseColor("#FFB13B"); floatPaint.textSize = 30 * d }
                else -> { floatPaint.color = Color.WHITE; floatPaint.textSize = 12 * d }
            }
            floatPaint.alpha = (min(1f, t.life) * 255).toInt()
            c.drawText(t.text, t.x * d, t.y * d, floatPaint)
        }

        if (flash > 0f) c.drawColor(Color.argb((flash * 140).toInt(), 255, 240, 210))
    }

    private fun fmt(t: Float): String {
        val s = t.toInt()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun drawHud(c: Canvas) {
        val left = 14 * d
        val top = topInset + 26 * d
        if (world.phase != Phase.SETUP) {
            val alive = world.humans.count { it.role != Role.STRAY }
            val nw = world.nextWaveIn.toInt().coerceAtLeast(0) + 1
            c.drawText("WAVE ${world.wave}   ⏱ ${fmt(world.time)}", left, top, hud)
            val waveLabelX = left + hud.measureText("WAVE ${world.wave}   ⏱ ${fmt(world.time)}   ")
            c.drawText("next wave ${nw}s", waveLabelX, top, if (nw <= 5) nukeReadyPaint else hudDim)
            c.drawText("☠ ${world.kills}   ● ${world.zombies.size}   👥 $alive", left, top + 20 * d, hudDim)
            c.drawText(if (world.nukeReady) "☢ NUKE READY (double-tap)" else "☢ NUKE USED", left, top + 38 * d,
                if (world.nukeReady) nukeReadyPaint else hudDim)
            c.drawText(
                if (world.airstrikeReady) "✈ AIRSTRIKE READY (drag to aim)" else "✈ AIRSTRIKE in ${world.airstrikeCooldown.toInt() + 1}s",
                left, top + 56 * d, if (world.airstrikeReady) airReadyPaint else hudDim
            )
            if (world.phase == Phase.RUNNING) c.drawText("SQUAD: ${world.tactic.label}", left, top + 76 * d, tacticPaint)
        }
        drawButton(c, btnPause, if (paused) "▶" else "❚❚")
        drawButton(c, btnSpeed, "${speed}x")
        drawButton(c, btnReset, "↺")
    }

    private fun drawButton(c: Canvas, r: RectF, label: String) {
        c.drawRoundRect(r, 10 * d, 10 * d, btnPaint)
        c.drawText(label, r.centerX(), r.centerY() + 5 * d, btnText)
    }

    private fun drawSetup(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        c.drawText("ZOMBIE DOTS", cx, cy - 40 * d, title)
        c.drawText("Tap anywhere to drop your survivor + hunter", cx, cy + 4 * d, center)
        c.drawText("Then watch them try to survive.", cx, cy + 28 * d, centerDim)
        c.drawText("Double-tap = your one nuke   ·   Drag = airstrike", cx, cy + 50 * d, centerDim)
        c.drawText("Tap = drop more zombies", cx, cy + 68 * d, centerDim)
        if (best > 0f) c.drawText("Best survival: ${fmt(best)}", cx, cy + 100 * d, centerDim)
    }

    private fun drawOver(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val cx = width / 2f
        val cy = height / 2f
        c.drawText("EVERYONE DIED", cx, cy - 50 * d, title)
        c.drawText("Survived ${fmt(world.time)}  ·  Wave ${world.wave}  ·  ${world.kills} kills", cx, cy, center)
        c.drawText("${world.turned} turned", cx, cy + 46 * d, centerDim)
        val newBest = world.time >= best && best > 0f
        c.drawText(if (newBest) "New best!" else "Best: ${fmt(best)}", cx, cy + 26 * d, centerDim)
        c.drawText("Tap to play again", cx, cy + 80 * d, center)
    }

    private fun drawPlane(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val angle = Math.toDegrees(kotlin.math.atan2(dy, dx).toDouble()).toFloat()
        val s = 1.4f * d
        path.reset()
        // Simple jet silhouette pointing along +x
        path.moveTo(12f * s, 0f)
        path.lineTo(2f * s, -1.6f * s)
        path.lineTo(-1f * s, -10f * s)
        path.lineTo(-4f * s, -10f * s)
        path.lineTo(-3f * s, -1.6f * s)
        path.lineTo(-8f * s, -1.4f * s)
        path.lineTo(-10f * s, -4.5f * s)
        path.lineTo(-12f * s, -4.5f * s)
        path.lineTo(-11f * s, 0f)
        path.lineTo(-12f * s, 4.5f * s)
        path.lineTo(-10f * s, 4.5f * s)
        path.lineTo(-8f * s, 1.4f * s)
        path.lineTo(-3f * s, 1.6f * s)
        path.lineTo(-4f * s, 10f * s)
        path.lineTo(-1f * s, 10f * s)
        path.lineTo(2f * s, 1.6f * s)
        path.close()
        c.save()
        c.translate(x + 10 * d, y + 14 * d)
        c.rotate(angle)
        c.drawPath(path, planeShadow)
        c.restore()
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        c.drawPath(path, planePaint)
        c.restore()
    }

    private fun drawAim(c: Canvas) {
        val dx = aimEX - aimSX; val dy = aimEY - aimSY
        val len = kotlin.math.hypot(dx, dy)
        val ready = world.airstrikeReady
        val longEnough = len / d >= World.MIN_STRIKE_LENGTH
        val col = when {
            !ready -> Color.parseColor("#8A93A3")
            !longEnough -> Color.parseColor("#BFA070")
            else -> Color.parseColor("#FFB13B")
        }
        // Bomb corridor
        aimBand.color = col
        aimBand.alpha = 40
        aimBand.strokeWidth = World.BOMB_RADIUS * 2 * d
        c.drawLine(aimSX, aimSY, aimEX, aimEY, aimBand)
        // Dashed flight line + arrow head
        aimLine.color = col
        c.drawLine(aimSX, aimSY, aimEX, aimEY, aimLine)
        if (len > 1f) {
            val ux = dx / len; val uy = dy / len
            val hs = 14 * d
            path.reset()
            path.moveTo(aimEX + ux * hs * 0.6f, aimEY + uy * hs * 0.6f)
            path.lineTo(aimEX - ux * hs - uy * hs * 0.6f, aimEY - uy * hs + ux * hs * 0.6f)
            path.lineTo(aimEX - ux * hs + uy * hs * 0.6f, aimEY - uy * hs - ux * hs * 0.6f)
            path.close()
            aimHead.color = col
            c.drawPath(path, aimHead)
        }
        aimText.color = col
        val label = when {
            !ready -> "airstrike in ${world.airstrikeCooldown.toInt() + 1}s"
            !longEnough -> "drag farther"
            else -> "let go to call the airstrike"
        }
        c.drawText(label, (aimSX + aimEX) / 2f, (aimSY + aimEY) / 2f - 22 * d, aimText)
    }

    private fun drawPaused(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        c.drawText("PAUSED", width / 2f, height / 2f, title)
    }
}
