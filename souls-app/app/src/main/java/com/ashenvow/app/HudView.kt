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
    private val buttons = listOf(
        Btn("ATTACK", A_ATTACK), Btn("HEAVY", A_HEAVY), Btn("ROLL", A_ROLL),
        Btn("HEAL", A_HEAL), Btn("LOCK", A_LOCK), Btn("❚❚", A_PAUSE)
    )

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
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val idx = e.actionIndex
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(idx); val y = e.getY(idx); val id = e.getPointerId(idx)
                if (game.screen != Screen.FIGHT) {
                    if (game.screen == Screen.TITLE || game.screenT > 1.5f) game.input.start.incrementAndGet()
                    return true
                }
                if (game.paused) { game.paused = false; return true }
                val hit = buttons.firstOrNull { val dx = x - it.x; val dy = y - it.y; dx * dx + dy * dy < (it.r * 1.25f) * (it.r * 1.25f) }
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
                if (e.actionMasked == MotionEvent.ACTION_UP) { joyId = -1; camId = -1; game.input.moveX = 0f; game.input.moveY = 0f }
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
        phaseMsg = (phaseMsg - dt).coerceAtLeast(0f)

        when (g.screen) {
            Screen.TITLE -> drawTitle(c)
            Screen.FIGHT -> drawFight(c, dt)
            Screen.DIED -> { drawFight(c, dt, controls = false); drawBanner(c, "YOU DIED", Color.rgb(170, 20, 20), "Tap to try again") }
            Screen.WON -> { drawFight(c, dt, controls = false); drawBanner(c, "ENEMY FELLED", Color.rgb(222, 184, 92), "Tap to fight again") }
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
    }

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
        val left = 24 * d; val top = 22 * d
        val hpW = 250 * d
        bar(c, left, top, hpW, 10 * d, p.hp / Player.MAX_HP, ghostHp / Player.MAX_HP, Color.rgb(176, 32, 32))
        bar(c, left, top + 16 * d, 200 * d, 7 * d, (p.stamina / Player.MAX_STAMINA).coerceAtLeast(0f), 0f, Color.rgb(64, 160, 72))
        // Flask
        val fx = left + 8 * d; val fy = top + 44 * d
        fill.color = Color.rgb(214, 140, 40)
        fill.alpha = if (p.flasks > 0) 255 else 80
        c.drawCircle(fx, fy + 4 * d, 7 * d, fill)
        c.drawRect(fx - 2.5f * d, fy - 9 * d, fx + 2.5f * d, fy - 2 * d, fill)
        fill.alpha = 255
        text.textAlign = Paint.Align.LEFT; text.textSize = 14 * d; text.color = Color.WHITE
        c.drawText("× ${p.flasks}", fx + 13 * d, fy + 9 * d, text)
        text.textAlign = Paint.Align.CENTER

        // Boss bar (bottom centre)
        if (b.state != BState.INTRO || b.t > 0.4f) {
            ghostBoss = if (ghostBoss > b.hp) ghostBoss - dt * 120f else b.hp
            val bw = w * 0.52f
            val bx = (w - bw) / 2; val bY = h - 40 * d
            serif.textAlign = Paint.Align.LEFT; serif.textSize = 15 * d; serif.color = Color.rgb(225, 218, 200)
            c.drawText(Boss.NAME, bx, bY - 8 * d, serif)
            serif.textAlign = Paint.Align.CENTER
            bar(c, bx, bY, bw, 8 * d, b.hp / Boss.MAX_HP, ghostBoss / Boss.MAX_HP, if (b.phase2) Color.rgb(214, 84, 24) else Color.rgb(150, 26, 26))
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
        // Joystick
        if (joyId != -1) {
            fill.color = Color.argb(50, 255, 255, 255); c.drawCircle(joyCX, joyCY, 60 * d, fill)
            fill.color = Color.argb(150, 255, 255, 255); c.drawCircle(joyX, joyY, 26 * d, fill)
        } else {
            text.textSize = 12 * d; text.color = Color.argb(90, 255, 255, 255)
            c.drawText("drag here to move", w * 0.17f, h - 30 * d, text)
        }
        // Buttons
        val nowMs = System.currentTimeMillis()
        for (bt in buttons) {
            val pressed = bt.pressedUntil > nowMs
            val active = when (bt.action) {
                A_LOCK -> g.locked
                A_HEAL -> p.flasks > 0
                else -> true
            }
            fill.color = when {
                pressed -> Color.argb(170, 255, 230, 180)
                bt.action == A_LOCK && active -> Color.argb(120, 220, 180, 90)
                else -> Color.argb(if (active) 70 else 30, 20, 20, 24)
            }
            c.drawCircle(bt.x, bt.y, bt.r, fill)
            stroke.color = Color.argb(if (active) 170 else 70, 230, 220, 200); stroke.strokeWidth = 1.5f * d
            c.drawCircle(bt.x, bt.y, bt.r, stroke)
            text.textSize = (if (bt.action == A_ATTACK) 14f else if (bt.action == A_PAUSE) 12f else 11f) * d
            text.color = if (active) Color.WHITE else Color.argb(110, 255, 255, 255)
            c.drawText(bt.label, bt.x, bt.y + text.textSize * 0.36f, text)
        }
        if (g.paused) {
            fill.color = Color.argb(170, 0, 0, 0); c.drawRect(0f, 0f, w, h, fill)
            serif.textSize = 34 * d; serif.color = Color.rgb(225, 218, 200)
            c.drawText("PAUSED", w / 2, h * 0.45f, serif)
            text.textSize = 15 * d; text.color = Color.LTGRAY
            c.drawText("Tap to keep fighting", w / 2, h * 0.45f + 34 * d, text)
        }
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
            "LOCK keeps the camera on the boss   ·   Everything uses stamina (the green bar)",
            "Watch its eyes: they burn brighter right before it strikes."
        )
        lines.forEachIndexed { i, s -> c.drawText(s, w / 2, h * 0.7f + i * 19 * d, text) }
    }
}
