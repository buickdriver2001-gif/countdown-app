package com.ashenvow.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/** Touch controls and the health / stamina / boss bars, drawn over the 3D view. */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val game: Game, private val renderer: GameRenderer) : View(context) {

    private val d = resources.displayMetrics.density

    private class Btn(val label: String, val action: Int) {
        var x = 0f; var y = 0f; var r = 0f
        var pressedUntil = 0L
    }

    private val A_ATTACK = 0; private val A_HEAVY = 1; private val A_ROLL = 2; private val A_HEAL = 3; private val A_LOCK = 4; private val A_PAUSE = 5
    private val A_BLOCK = 6
    private val buttons = listOf(
        Btn("ATTACK", A_ATTACK), Btn("HEAVY", A_HEAVY), Btn("ROLL", A_ROLL),
        Btn("HEAL", A_HEAL), Btn("LOCK", A_LOCK), Btn("", A_PAUSE), Btn("BLOCK", A_BLOCK)
    )
    /** The finger holding BLOCK (the shield stays up until it lifts). */
    private var blockId = -1

    // Pointer roles
    private var joyId = -1
    private var joyCX = 0f; private var joyCY = 0f; private var joyX = 0f; private var joyY = 0f
    private var camId = -1
    private var camLastX = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val serif = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val rect = RectF()

    private var ghostHp = Player.MAX_HP
    private var ghostBoss = Boss.MAX_HP
    private var lastPHit = 0; private var lastBHit = 0; private var lastPhase = 0; private var lastDied = 0; private var lastWon = 0; private var lastBlock = 0
    private var hitVignette = 0f
    private var phaseMsg = 0f
    private var lastFrame = 0L

    @Volatile var vibrate = true
    /** Opens the NEXUS (set by the activity). */
    var onHub: () -> Unit = {}
    private val hubRect = RectF()
    private val pauseHubRect = RectF()

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) (context.getSystemService(VibratorManager::class.java))?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
    } catch (_: Exception) { null }

    private fun buzz(ms: Long, amp: Int) {
        if (!vibrate) return
        try { vibrator?.vibrate(VibrationEffect.createOneShot(ms, amp)) } catch (_: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val by = h.toFloat(); val bx = w.toFloat()
        fun place(b: Btn, x: Float, y: Float, r: Float) { b.x = x; b.y = y; b.r = r }
        place(buttons[A_ATTACK], bx - 92 * d, by - 88 * d, 50 * d)
        place(buttons[A_ROLL], bx - 205 * d, by - 62 * d, 38 * d)
        place(buttons[A_HEAVY], bx - 178 * d, by - 168 * d, 36 * d)
        place(buttons[A_HEAL], bx - 80 * d, by - 196 * d, 32 * d)
        place(buttons[A_LOCK], bx - 58 * d, by - 275 * d, 28 * d)
        place(buttons[A_PAUSE], bx - 36 * d, 34 * d, 22 * d)
        place(buttons[A_BLOCK], bx - 268 * d, by - 150 * d, 34 * d)
        hubRect.set(16 * d, 14 * d, 150 * d, 50 * d)
        pauseHubRect.set(bx / 2 - 110 * d, by * 0.45f + 58 * d, bx / 2 + 110 * d, by * 0.45f + 102 * d)
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx); val id = e.getPointerId(idx)
                if (game.screen != Screen.FIGHT) {
                    if (hubRect.contains(x, y)) { onHub(); return true }
                    if (game.screen == Screen.TITLE || game.screenT > 1.5f) game.input.start.incrementAndGet()
                    return true
                }
                if (game.paused) {
                    if (pauseHubRect.contains(x, y)) onHub() else game.paused = false
                    return true
                }
                val hit = buttons.firstOrNull { val dx = x - it.x; val dy = y - it.y; dx * dx + dy * dy < (it.r * 1.25f) * (it.r * 1.25f) }
                if (hit != null && hit.action == A_BLOCK) {
                    // Hold to block
                    blockId = id; game.input.blockHeld = true; buzz(12, 60)
                    return true
                }
                if (hit != null) { press(hit); return true }
                if (x < width * 0.45f && joyId == -1) {
                    joyId = id; joyCX = x; joyCY = y; joyX = x; joyY = y
                } else if (camId == -1) { camId = id; camLastX = x }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == joyId) {
                        joyX = e.getX(i); joyY = e.getY(i)
                        val max = 55 * d
                        var dx = joyX - joyCX; var dy = joyY - joyCY
                        val l = sqrt(dx * dx + dy * dy)
                        if (l > max) {
                            // Drag the stick's centre along so it never feels stuck
                            joyCX += dx / l * (l - max); joyCY += dy / l * (l - max)
                            dx = joyX - joyCX; dy = joyY - joyCY
                        }
                        game.input.moveX = dx / max; game.input.moveY = -dy / max
                    } else if (id == camId) {
                        val x = e.getX(i)
                        game.input.addDrag(-(x - camLastX) / d * 0.009f) // swipe left = turn left
                        camLastX = x
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(idx)
                if (e.actionMasked == MotionEvent.ACTION_CANCEL || id == joyId) {
                    if (id == joyId || e.actionMasked == MotionEvent.ACTION_CANCEL) { joyId = -1; game.input.moveX = 0f; game.input.moveY = 0f }
                }
                if (id == camId || e.actionMasked == MotionEvent.ACTION_CANCEL) camId = -1
                if (id == blockId || e.actionMasked == MotionEvent.ACTION_CANCEL) { blockId = -1; game.input.blockHeld = false }
                if (e.actionMasked == MotionEvent.ACTION_UP) { joyId = -1; camId = -1; blockId = -1; game.input.blockHeld = false; game.input.moveX = 0f; game.input.moveY = 0f }
            }
        }
        return true
    }

    private fun press(b: Btn) {
        b.pressedUntil = System.currentTimeMillis() + 140
        val inp = game.input
        when (b.action) {
            A_ATTACK -> inp.attack.incrementAndGet()
            A_HEAVY -> inp.heavy.incrementAndGet()
            A_ROLL -> inp.roll.incrementAndGet()
            A_HEAL -> inp.heal.incrementAndGet()
            A_LOCK -> inp.lock.incrementAndGet()
            A_PAUSE -> game.paused = true
        }
        buzz(12, 60)
    }

    // ------------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1e9f)
        lastFrame = now
        val g = game
        checkEvents()
        hitVignette = (hitVignette - dt * 2.2f).coerceAtLeast(0f)
        guardFlash = (guardFlash - dt * 3f).coerceAtLeast(0f)
        if (g.screen != Screen.FIGHT || g.paused) { if (blockId != -1 || g.input.blockHeld) { blockId = -1; g.input.blockHeld = false } }
        phaseMsg = (phaseMsg - dt).coerceAtLeast(0f)

        when (g.screen) {
            Screen.TITLE -> { drawTitle(c); hubButton(c, hubRect, "⌂  NEXUS") }
            Screen.FIGHT -> drawFight(c, dt)
            Screen.DIED -> { drawFight(c, dt, controls = false); drawBanner(c, "YOU DIED", Color.rgb(170, 20, 20), "Tap to try again"); hubButton(c, hubRect, "⌂  NEXUS") }
            Screen.WON -> { drawFight(c, dt, controls = false); drawBanner(c, "ENEMY FELLED", Color.rgb(222, 184, 92), "Tap to fight again"); hubButton(c, hubRect, "⌂  NEXUS") }
        }
        postInvalidateOnAnimation()
    }

    private fun checkEvents() {
        val g = game
        val ph = g.evPlayerHit.get(); if (ph != lastPHit) { lastPHit = ph; hitVignette = 1f; buzz(70, 230) }
        val bh = g.evBossHit.get(); if (bh != lastBHit) { lastBHit = bh; buzz(25, 120) }
        val pz = g.evPhase.get(); if (pz != lastPhase) { lastPhase = pz; phaseMsg = 3.5f; buzz(400, 255) }
        val dd = g.evDied.get(); if (dd != lastDied) { lastDied = dd; buzz(500, 255) }
        val ww = g.evWon.get(); if (ww != lastWon) { lastWon = ww; buzz(300, 200) }
        val bl = g.evBlocked.get(); if (bl != lastBlock) { lastBlock = bl }
        val gd = g.evGuard.get(); if (gd != lastGuard) { lastGuard = gd; guardFlash = 1f; buzz(35, 150) }
        val gb = g.evGuardBreak.get(); if (gb != lastGuardBreak) { lastGuardBreak = gb; guardFlash = 1f; hitVignette = 0.7f; buzz(140, 255) }
    }
    private var lastGuard = 0; private var lastGuardBreak = 0
    /** Brief glow on the stamina bar when the shield takes a blow. */
    private var guardFlash = 0f

    private fun drawFight(c: Canvas, dt: Float, controls: Boolean = true) {
        val g = game
        val p = g.player; val b = g.boss
        val w = width.toFloat(); val h = height.toFloat()

        // Red flash at the screen edges when you get hit
        if (hitVignette > 0f) {
            fill.shader = RadialGradient(w / 2, h / 2, w * 0.7f, intArrayOf(Color.TRANSPARENT, Color.argb((150 * hitVignette).toInt(), 160, 0, 0)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill)
            fill.shader = null
        }

        // Health, stamina, flasks (top left)
        ghostHp = if (ghostHp > p.hp) ghostHp - dt * 40f else p.hp
        val left = 26 * d; val top = 22 * d
        fancyBar(c, left, top, 250 * d, 12 * d, p.hp / Player.MAX_HP, ghostHp / Player.MAX_HP,
            Color.rgb(120, 14, 14), Color.rgb(214, 46, 40), p.hp < Player.MAX_HP * 0.3f)
        val st = (p.stamina / Player.MAX_STAMINA).coerceAtLeast(0f)
        fancyBar(c, left, top + 20 * d, 200 * d, 8 * d, st, 0f,
            Color.rgb(30, 96, 40), Color.rgb(96, 200, 90), p.stamina <= 0f)
        if (guardFlash > 0f) {
            // The shield took a blow: the stamina bar flashes so you can see what it cost
            fill.color = Color.argb((150 * guardFlash).toInt(), 255, 240, 200)
            c.drawRect(left, top + 20 * d, left + 200 * d * st, top + 28 * d, fill)
        }
        drawFlask(c, left + 14 * d, top + 62 * d, p.flasks)

        // Boss bar: bottom, between the move area and the buttons (never under them)
        if (b.state != BState.INTRO || b.t > 0.4f) {
            ghostBoss = if (ghostBoss > b.hp) ghostBoss - dt * 120f else b.hp
            val bx = w * 0.17f
            val bRight = min(w - 290 * d, w * 0.83f)
            val bw = bRight - bx
            val bY = h - 30 * d
            serif.textAlign = Paint.Align.LEFT; serif.textSize = 15 * d; serif.color = Color.rgb(225, 218, 200)
            serif.setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
            c.drawText(Boss.NAME, bx, bY - 10 * d, serif)
            serif.setShadowLayer(0f, 0f, 0f, 0)
            serif.textAlign = Paint.Align.CENTER
            fancyBar(c, bx, bY, bw, 9 * d, b.hp / Boss.MAX_HP, ghostBoss / Boss.MAX_HP,
                if (b.phase2) Color.rgb(140, 40, 8) else Color.rgb(100, 12, 12),
                if (b.phase2) Color.rgb(240, 120, 40) else Color.rgb(190, 34, 30), false)
        }

        // Lock-on marker on the boss
        if (g.locked && renderer.lockVisible) {
            val pulse = 0.75f + 0.25f * kotlin.math.sin(System.currentTimeMillis() / 140.0).toFloat()
            fill.color = Color.argb((230 * pulse).toInt(), 255, 245, 220)
            c.drawCircle(renderer.lockX, renderer.lockY, 5 * d, fill)
            stroke.color = Color.argb(140, 255, 245, 220); stroke.strokeWidth = 1.5f * d
            c.drawCircle(renderer.lockX, renderer.lockY, 10 * d, stroke)
        }

        if (phaseMsg > 0f) {
            val a = (min(1f, phaseMsg) * 255).toInt()
            serif.textSize = 26 * d; serif.color = Color.argb(a, 240, 140, 60)
            c.drawText("The Warden's fury ignites!", w / 2, h * 0.28f, serif)
        }

        if (!controls) return
        // Joystick: a ring with direction chevrons and a thumb pad
        if (joyId != -1) {
            fill.color = Color.argb(40, 255, 255, 255); c.drawCircle(joyCX, joyCY, 62 * d, fill)
            stroke.color = Color.argb(110, 255, 255, 255); stroke.strokeWidth = 2f * d
            c.drawCircle(joyCX, joyCY, 62 * d, stroke)
            for (k in 0 until 4) chevron(c, joyCX, joyCY, 50 * d, k * 90f, Color.argb(120, 255, 255, 255))
            fill.shader = RadialGradient(joyX, joyY - 6 * d, 30 * d, intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(150, 190, 190, 200)), null, Shader.TileMode.CLAMP)
            c.drawCircle(joyX, joyY, 27 * d, fill); fill.shader = null
        } else {
            stroke.color = Color.argb(60, 255, 255, 255); stroke.strokeWidth = 1.5f * d
            c.drawCircle(w * 0.15f, h - 90 * d, 50 * d, stroke)
            for (k in 0 until 4) chevron(c, w * 0.15f, h - 90 * d, 40 * d, k * 90f, Color.argb(60, 255, 255, 255))
        }
        // Buttons with icons
        val nowMs = System.currentTimeMillis()
        for (bt in buttons) {
            val pressed = bt.pressedUntil > nowMs || (bt.action == A_BLOCK && blockId != -1)
            val usable = when (bt.action) {
                A_HEAL -> p.flasks > 0
                A_ATTACK, A_HEAVY, A_ROLL, A_BLOCK -> p.stamina > 0f
                else -> true
            }
            val on = (bt.action == A_LOCK && g.locked) || (bt.action == A_BLOCK && p.state == PState.BLOCK)
            val r = bt.r * (if (pressed) 0.92f else 1f)
            // Dark glass disc with a soft inner gradient
            fill.shader = RadialGradient(bt.x, bt.y - r * 0.4f, r * 1.3f,
                intArrayOf(Color.argb(if (pressed) 200 else 130, 70, 70, 80), Color.argb(if (pressed) 200 else 120, 10, 10, 14)), null, Shader.TileMode.CLAMP)
            c.drawCircle(bt.x, bt.y, r, fill); fill.shader = null
            // Outer ring: gold when active / pressed
            stroke.strokeWidth = (if (bt.action == A_ATTACK) 2.5f else 2f) * d
            stroke.color = when {
                pressed -> Color.argb(255, 255, 220, 140)
                on -> Color.argb(230, 230, 180, 90)
                !usable -> Color.argb(70, 200, 200, 200)
                else -> Color.argb(170, 220, 210, 190)
            }
            c.drawCircle(bt.x, bt.y, r, stroke)
            val ic = if (!usable) Color.argb(90, 255, 255, 255) else if (on || pressed) Color.rgb(255, 228, 160) else Color.WHITE
            drawIcon(c, bt.action, bt.x, bt.y, r * 0.55f, ic)
            if (bt.action == A_HEAL) {
                // Flask count badge
                fill.color = if (p.flasks > 0) Color.rgb(200, 120, 30) else Color.rgb(80, 80, 80)
                c.drawCircle(bt.x + r * 0.72f, bt.y - r * 0.72f, 9 * d, fill)
                text.textSize = 11 * d; text.color = Color.WHITE
                c.drawText("${p.flasks}", bt.x + r * 0.72f, bt.y - r * 0.72f + 4 * d, text)
            }
        }
        if (g.paused) {
            fill.color = Color.argb(170, 0, 0, 0); c.drawRect(0f, 0f, w, h, fill)
            serif.textSize = 34 * d; serif.color = Color.rgb(225, 218, 200)
            c.drawText("PAUSED", w / 2, h * 0.45f, serif)
            text.textSize = 15 * d; text.color = Color.LTGRAY
            c.drawText("Tap to keep fighting", w / 2, h * 0.45f + 34 * d, text)
            hubButton(c, pauseHubRect, "⌂  Back to NEXUS")
        }
    }

    private fun hubButton(c: Canvas, r: RectF, label: String) {
        fill.color = Color.argb(150, 20, 20, 24)
        c.drawRoundRect(r, 10 * d, 10 * d, fill)
        stroke.color = Color.argb(170, 230, 220, 200); stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(r, 10 * d, 10 * d, stroke)
        text.textSize = 14 * d; text.color = Color.WHITE
        c.drawText(label, r.centerX(), r.centerY() + 5 * d, text)
    }

    private val iconPath = android.graphics.Path()
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun chevron(c: Canvas, cx: Float, cy: Float, dist: Float, angleDeg: Float, color: Int) {
        c.save(); c.rotate(angleDeg, cx, cy)
        iconStroke.color = color; iconStroke.strokeWidth = 2f * d
        iconPath.reset()
        iconPath.moveTo(cx - 6 * d, cy - dist + 5 * d); iconPath.lineTo(cx, cy - dist); iconPath.lineTo(cx + 6 * d, cy - dist + 5 * d)
        c.drawPath(iconPath, iconStroke)
        c.restore()
    }

    /** Simple symbols: sword, heavy swing, dodge, flask, target, pause, shield. */
    private fun drawIcon(c: Canvas, action: Int, cx: Float, cy: Float, s: Float, color: Int) {
        iconStroke.color = color; iconFill.color = color
        iconStroke.strokeWidth = s * 0.14f
        when (action) {
            A_ATTACK -> sword(c, cx, cy, s, -45f)
            A_HEAVY -> {
                // A big two-handed blade with a swing arc behind it
                sword(c, cx + s * 0.1f, cy + s * 0.05f, s * 1.05f, -45f, thick = true)
                iconStroke.strokeWidth = s * 0.1f
                rect.set(cx - s * 1.05f, cy - s * 1.05f, cx + s * 0.85f, cy + s * 0.85f)
                c.drawArc(rect, 200f, 70f, false, iconStroke)
                rect.set(cx - s * 0.85f, cy - s * 0.85f, cx + s * 0.65f, cy + s * 0.65f)
                c.drawArc(rect, 210f, 50f, false, iconStroke)
            }
            A_ROLL -> {
                // A curled arrow: dodge
                rect.set(cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy + s * 0.7f)
                iconStroke.strokeWidth = s * 0.16f
                c.drawArc(rect, 140f, 260f, false, iconStroke)
                val ax = cx + kotlin.math.cos(Math.toRadians(40.0)).toFloat() * s * 0.7f
                val ay = cy + kotlin.math.sin(Math.toRadians(40.0)).toFloat() * s * 0.7f
                iconPath.reset()
                iconPath.moveTo(ax + s * 0.34f, ay - s * 0.1f); iconPath.lineTo(ax, ay); iconPath.lineTo(ax - s * 0.06f, ay - s * 0.38f)
                c.drawPath(iconPath, iconStroke)
                // Little speed lines
                iconStroke.strokeWidth = s * 0.1f
                c.drawLine(cx - s * 1.15f, cy + s * 0.1f, cx - s * 0.9f, cy + s * 0.1f, iconStroke)
                c.drawLine(cx - s * 1.1f, cy + s * 0.4f, cx - s * 0.85f, cy + s * 0.4f, iconStroke)
            }
            A_HEAL -> {
                // A round flask with a cork and liquid line
                iconStroke.strokeWidth = s * 0.13f
                c.drawCircle(cx, cy + s * 0.25f, s * 0.55f, iconStroke)
                c.drawRect(cx - s * 0.18f, cy - s * 0.65f, cx + s * 0.18f, cy - s * 0.28f, iconStroke)
                iconFill.alpha = Color.alpha(color)
                c.drawRect(cx - s * 0.24f, cy - s * 0.85f, cx + s * 0.24f, cy - s * 0.65f, iconFill)
                iconPath.reset(); iconPath.addCircle(cx, cy + s * 0.25f, s * 0.42f, android.graphics.Path.Direction.CW)
                c.save(); c.clipPath(iconPath)
                c.drawRect(cx - s, cy + s * 0.2f, cx + s, cy + s, iconFill)
                c.restore()
            }
            A_LOCK -> {
                // Target reticle
                iconStroke.strokeWidth = s * 0.12f
                c.drawCircle(cx, cy, s * 0.6f, iconStroke)
                c.drawCircle(cx, cy, s * 0.12f, iconFill)
                for (k in 0 until 4) {
                    val a = Math.toRadians(k * 90.0)
                    val ux = kotlin.math.cos(a).toFloat(); val uy = kotlin.math.sin(a).toFloat()
                    c.drawLine(cx + ux * s * 0.35f, cy + uy * s * 0.35f, cx + ux * s * 0.95f, cy + uy * s * 0.95f, iconStroke)
                }
            }
            A_PAUSE -> {
                c.drawRect(cx - s * 0.45f, cy - s * 0.55f, cx - s * 0.12f, cy + s * 0.55f, iconFill)
                c.drawRect(cx + s * 0.12f, cy - s * 0.55f, cx + s * 0.45f, cy + s * 0.55f, iconFill)
            }
            A_BLOCK -> {
                // Heater shield outline with a cross on it
                iconStroke.strokeWidth = s * 0.13f
                iconPath.reset()
                iconPath.moveTo(cx - s * 0.62f, cy - s * 0.72f)
                iconPath.lineTo(cx + s * 0.62f, cy - s * 0.72f)
                iconPath.lineTo(cx + s * 0.62f, cy - s * 0.05f)
                iconPath.quadTo(cx + s * 0.55f, cy + s * 0.6f, cx, cy + s * 0.92f)
                iconPath.quadTo(cx - s * 0.55f, cy + s * 0.6f, cx - s * 0.62f, cy - s * 0.05f)
                iconPath.close()
                c.drawPath(iconPath, iconStroke)
                iconStroke.strokeWidth = s * 0.1f
                c.drawLine(cx, cy - s * 0.5f, cx, cy + s * 0.55f, iconStroke)
                c.drawLine(cx - s * 0.38f, cy - s * 0.15f, cx + s * 0.38f, cy - s * 0.15f, iconStroke)
            }
        }
    }

    private fun sword(c: Canvas, cx: Float, cy: Float, s: Float, angle: Float, thick: Boolean = false) {
        c.save(); c.rotate(angle, cx, cy)
        val bw = s * (if (thick) 0.2f else 0.13f)
        iconPath.reset()
        iconPath.moveTo(cx, cy - s * 1.0f)
        iconPath.lineTo(cx + bw, cy - s * 0.8f); iconPath.lineTo(cx + bw, cy + s * 0.35f)
        iconPath.lineTo(cx - bw, cy + s * 0.35f); iconPath.lineTo(cx - bw, cy - s * 0.8f); iconPath.close()
        c.drawPath(iconPath, iconFill)
        c.drawRect(cx - s * 0.42f, cy + s * 0.35f, cx + s * 0.42f, cy + s * 0.48f, iconFill)   // guard
        c.drawRect(cx - s * 0.07f, cy + s * 0.48f, cx + s * 0.07f, cy + s * 0.85f, iconFill)   // grip
        c.drawCircle(cx, cy + s * 0.92f, s * 0.11f, iconFill)                                   // pommel
        c.restore()
    }

    /** Health-style bar with a dark frame, gold end caps, a gradient fill and a shine line. */
    private fun fancyBar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, ghost: Float, dark: Int, bright: Int, warn: Boolean) {
        val f = frac.coerceIn(0f, 1f)
        fill.color = Color.argb(190, 0, 0, 0)
        rect.set(x - 3 * d, y - 3 * d, x + w + 3 * d, y + h + 3 * d)
        c.drawRoundRect(rect, 2 * d, 2 * d, fill)
        if (ghost > f) { fill.color = Color.argb(210, 235, 215, 160); c.drawRect(x, y, x + w * ghost.coerceIn(0f, 1f), y + h, fill) }
        if (f > 0f) {
            fill.shader = LinearGradient(0f, y, 0f, y + h, intArrayOf(bright, dark), null, Shader.TileMode.CLAMP)
            c.drawRect(x, y, x + w * f, y + h, fill); fill.shader = null
            fill.color = Color.argb(90, 255, 255, 255)
            c.drawRect(x, y + h * 0.12f, x + w * f, y + h * 0.3f, fill)
            if (warn) {
                val pulse = (0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 120.0)).toFloat()
                fill.color = Color.argb((90 * pulse).toInt(), 255, 255, 255)
                c.drawRect(x, y, x + w * f, y + h, fill)
            }
        }
        stroke.color = Color.argb(200, 170, 140, 80); stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(rect, 2 * d, 2 * d, stroke)
        // Gold diamond end caps
        for (ex in floatArrayOf(x - 3 * d, x + w + 3 * d)) {
            iconPath.reset()
            val cy = y + h / 2; val r = h / 2 + 4 * d
            iconPath.moveTo(ex, cy - r); iconPath.lineTo(ex + r * 0.6f, cy); iconPath.lineTo(ex, cy + r); iconPath.lineTo(ex - r * 0.6f, cy); iconPath.close()
            fill.color = Color.rgb(150, 116, 50); c.drawPath(iconPath, fill)
            stroke.color = Color.rgb(230, 196, 120); c.drawPath(iconPath, stroke)
        }
    }

    /** A glowing glass flask with the number left. */
    private fun drawFlask(c: Canvas, cx: Float, cy: Float, n: Int) {
        val full = n > 0
        if (full) {
            fill.shader = RadialGradient(cx, cy, 22 * d, intArrayOf(Color.argb(120, 255, 150, 40), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, 22 * d, fill); fill.shader = null
        }
        // Liquid
        iconPath.reset(); iconPath.addCircle(cx, cy + 3 * d, 10 * d, android.graphics.Path.Direction.CW)
        c.save(); c.clipPath(iconPath)
        fill.shader = LinearGradient(0f, cy - 4 * d, 0f, cy + 13 * d, intArrayOf(if (full) Color.rgb(255, 196, 80) else Color.rgb(70, 60, 50), if (full) Color.rgb(200, 90, 20) else Color.rgb(40, 34, 30)), null, Shader.TileMode.CLAMP)
        c.drawRect(cx - 12 * d, cy - 1 * d, cx + 12 * d, cy + 14 * d, fill); fill.shader = null
        c.restore()
        // Glass, neck and cork
        stroke.color = Color.argb(220, 230, 225, 210); stroke.strokeWidth = 1.6f * d
        c.drawCircle(cx, cy + 3 * d, 10 * d, stroke)
        c.drawRect(cx - 3.5f * d, cy - 13 * d, cx + 3.5f * d, cy - 6 * d, stroke)
        fill.color = Color.rgb(120, 84, 50); c.drawRect(cx - 4.5f * d, cy - 17 * d, cx + 4.5f * d, cy - 13 * d, fill)
        fill.color = Color.argb(140, 255, 255, 255); c.drawCircle(cx - 4 * d, cy, 2.2f * d, fill)
        text.textAlign = Paint.Align.LEFT; text.textSize = 16 * d
        text.color = if (full) Color.rgb(255, 230, 190) else Color.GRAY
        text.setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
        c.drawText("× $n", cx + 16 * d, cy + 9 * d, text)
        text.setShadowLayer(0f, 0f, 0f, 0)
        text.textAlign = Paint.Align.CENTER
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, ghost: Float, color: Int) {
        fill.color = Color.argb(170, 0, 0, 0)
        c.drawRect(x - 2 * d, y - 2 * d, x + w + 2 * d, y + h + 2 * d, fill)
        if (ghost > frac) { fill.color = Color.argb(200, 230, 210, 150); c.drawRect(x, y, x + w * ghost.coerceIn(0f, 1f), y + h, fill) }
        fill.color = color
        c.drawRect(x, y, x + w * frac.coerceIn(0f, 1f), y + h, fill)
        stroke.color = Color.argb(120, 200, 180, 140); stroke.strokeWidth = 1f * d
        c.drawRect(x - 2 * d, y - 2 * d, x + w + 2 * d, y + h + 2 * d, stroke)
    }

    private fun drawBanner(c: Canvas, title: String, color: Int, hint: String) {
        val w = width.toFloat(); val h = height.toFloat()
        val k = min(1f, game.screenT / 1.2f)
        fill.shader = LinearGradient(0f, h * 0.36f, 0f, h * 0.64f,
            intArrayOf(Color.TRANSPARENT, Color.argb((200 * k).toInt(), 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.36f, w, h * 0.64f, fill)
        fill.shader = null
        serif.textSize = 54 * d * (0.94f + 0.06f * k)
        serif.color = Color.argb((255 * k).toInt(), Color.red(color), Color.green(color), Color.blue(color))
        c.drawText(title, w / 2, h * 0.5f + 18 * d, serif)
        if (game.screenT > 1.5f) {
            text.textSize = 15 * d; text.color = Color.argb(200, 230, 230, 230)
            c.drawText(hint, w / 2, h * 0.5f + 58 * d, text)
        }
    }

    private fun drawTitle(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        fill.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.argb(120, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.argb(200, 0, 0, 0)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        serif.textSize = 60 * d; serif.color = Color.rgb(232, 222, 196)
        c.drawText("ASHEN VOW", w / 2, h * 0.36f, serif)
        serif.textSize = 17 * d; serif.color = Color.rgb(196, 150, 80)
        c.drawText("Defeat The Hollow Warden", w / 2, h * 0.36f + 32 * d, serif)
        val pulse = 0.6f + 0.4f * kotlin.math.sin(System.currentTimeMillis() / 400.0).toFloat()
        text.textSize = 17 * d; text.color = Color.argb((255 * pulse).toInt(), 255, 255, 255)
        c.drawText("Tap to begin", w / 2, h * 0.58f, text)
        text.textSize = 12.5f * d; text.color = Color.argb(200, 210, 210, 210)
        val lines = listOf(
            "Left side: drag to move   ·   Right side: drag to look around",
            "ATTACK swings (tap again for a combo)   ·   HEAVY hits hard and breaks its balance",
            "ROLL through attacks: you can't be hurt mid-roll   ·   HEAL drinks a flask (you have 4)",
            "Hold BLOCK to raise your shield: it soaks most of a hit, but costs stamina and a little health",
            "LOCK keeps the camera on the boss   ·   Everything uses stamina (the green bar)",
            "Watch its eyes: they burn brighter right before it strikes."
        )
        lines.forEachIndexed { i, s -> c.drawText(s, w / 2, h * 0.7f + i * 19 * d, text) }
    }
}
