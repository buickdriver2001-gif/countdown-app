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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

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
            val r = World.NUKE_RADIUS * d
            val scorch = (1f - n.age / 6f).coerceIn(0f, 1f)
            nukePaint.style = Paint.Style.FILL
            nukePaint.color = Color.argb((60 * scorch).toInt(), 60, 30, 10)
            c.drawCircle(cx, cy, r * 0.85f, nukePaint)
            if (n.age < 0.6f) {
                val p = n.age / 0.6f
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
            val col = when (h.role) { Role.PLAYER -> playerColor; Role.HUNTER -> hunterColor; Role.STRAY -> strayColor }
            glowPaint.color = col
            glowPaint.alpha = 55
            c.drawCircle(h.x * d, h.y * d, 10 * d, glowPaint)
            corePaint.color = col
            c.drawCircle(h.x * d, h.y * d, World.HUMAN_R * d, corePaint)
        }

        for (t in world.texts) {
            when {
                t.text.startsWith("WAVE") -> { floatPaint.color = Color.parseColor("#FF3B3B"); floatPaint.textSize = 28 * d }
                t.text.startsWith("Survivor Joined") -> { floatPaint.color = hunterColor; floatPaint.textSize = 17 * d }
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
            c.drawText("WAVE ${world.wave}   ⏱ ${fmt(world.time)}", left, top, hud)
            c.drawText("☠ ${world.kills}   ● ${world.zombies.size}   👥 $alive", left, top + 20 * d, hudDim)
            val charges = "☢".repeat(world.nukeCharges) + "·".repeat(World.MAX_NUKES - world.nukeCharges)
            c.drawText("NUKES $charges", left, top + 38 * d, hudDim)
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
        c.drawText("Tap = drop zombies   ·   Double-tap = nuke", cx, cy + 50 * d, centerDim)
        if (best > 0f) c.drawText("Best survival: ${fmt(best)}", cx, cy + 82 * d, centerDim)
    }

    private fun drawOver(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        val cx = width / 2f
        val cy = height / 2f
        c.drawText("EVERYONE DIED", cx, cy - 50 * d, title)
        c.drawText("Survived ${fmt(world.time)}  ·  Wave ${world.wave}  ·  ${world.kills} kills", cx, cy, center)
        val newBest = world.time >= best && best > 0f
        c.drawText(if (newBest) "New best!" else "Best: ${fmt(best)}", cx, cy + 26 * d, centerDim)
        c.drawText("Tap to play again", cx, cy + 66 * d, center)
    }

    private fun drawPaused(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        c.drawText("PAUSED", width / 2f, height / 2f, title)
    }
}
