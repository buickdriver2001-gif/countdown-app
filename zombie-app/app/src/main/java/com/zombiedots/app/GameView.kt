package com.zombiedots.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Draws the world and handles touches on the map. All buttons and menus live in MainActivity. */
@SuppressLint("ViewConstructor")
class GameView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onPhaseChanged(phase: Phase)
        fun onAnnounce(message: String)
        fun onAirstrikeLaunched()
        fun onCureTarget(h: Human)
    }

    val world = World()
    private val d = resources.displayMetrics.density
    private var lastFrame = 0L
    var speed = 1f
    var paused = false
    var shapes = false
    var tapAddsZombies = false
    var vibrate = true
    /** Set when the PLANE button was pressed, so the next drag is clearly an airstrike. */
    var planeAimRequested = false
    /** CURE was pressed: the game slows down and tapping a survivor cures them. */
    var cureMode = false
    var best = 0f
    /** 3, 2, 1 while the nuke button is held; 0 otherwise. */
    var nukeCountdown = 0

    /** True while a menu covers the map: stop redrawing so the menu stays smooth. */
    var covered = false
        set(value) {
            field = value
            if (!value) { lastFrame = 0L; postInvalidateOnAnimation() }
        }

    private var flash = 0f
    private var lastPhase = Phase.SETUP
    private var lastWave = 0
    private var lastTurned = 0
    private var lastTeam = 0
    private var lastBehemoth = 0

    // ---------- paints
    private val bg = Color.parseColor("#0B0D10")
    private val gridPaint = Paint().apply { color = Color.parseColor("#13171D"); strokeWidth = 1f }
    private val zombiePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3B3B"); strokeCap = Paint.Cap.ROUND; strokeWidth = World.ZOMBIE_R * 2 * d
    }
    private val splatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5A0F12") }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * d; color = Color.WHITE }
    private val tracerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFE9A0"); strokeWidth = 1.4f * d }
    private val nukePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D8DEE8") }
    private val planeShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#66000000") }
    private val floatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }
    private val aimLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(12 * d, 8 * d), 0f)
    }
    private val aimHead = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aimBand = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val aimText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16 * d; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(5f, 0f, 1f, Color.BLACK)
    }
    private val infPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.parseColor("#9CFF4D"); strokeCap = Paint.Cap.ROUND
    }
    private val infRect = RectF()
    private val path = Path()

    val zombieColor = Color.parseColor("#FF3B3B")
    val runnerColor = Color.parseColor("#FF8A5C")
    val fatColor = Color.parseColor("#B3262B")
    private val bigZombie = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
    private val armorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.parseColor("#C9D1DC")
    }
    val playerColor = Color.parseColor("#FFE066")
    val hunterColor = Color.parseColor("#3DA5FF")
    val strayColor = Color.parseColor("#B8C0CF")

    private var points = FloatArray(0)

    init {
        isFocusable = true
        contentDescription = "Game map. Tap anywhere to put your team down."
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        world.width = w / d
        world.height = h / d
    }

    // ---------- actions from buttons

    fun startAt(xPx: Float, yPx: Float) {
        world.start(xPx / d, yPx / d)
        paused = false
    }

    fun fireNuke(): Boolean {
        val l = world.leader
        val ok = world.nuke(l?.x ?: world.width / 2f, l?.y ?: world.height / 2f)
        if (ok) {
            flash = 1f
            buzz(HapticFeedbackConstants.LONG_PRESS)
            listener.onAnnounce("Nuke! Every zombie is gone. Next wave in ${World.NUKE_BREATHER.toInt()} seconds.")
        }
        return ok
    }

    private fun buzz(type: Int) {
        if (vibrate) performHapticFeedback(type)
    }

    // ---------- input

    private var aiming = false
    private var aimSX = 0f
    private var aimSY = 0f
    private var aimEX = 0f
    private var aimEY = 0f
    private var downX = 0f
    private var downY = 0f
    private var multiTouch = false

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (world.phase == Phase.SETUP && !paused) startAt(e.x, e.y)
            if (cureMode && world.phase == Phase.RUNNING && !paused) {
                // Pick the survivor nearest the tap (generous so moving dots are easy to hit)
                val tx = e.x / d; val ty = e.y / d
                val h = world.humans.filter { it.role != Role.STRAY && !it.dead }
                    .minByOrNull { (it.x - tx) * (it.x - tx) + (it.y - ty) * (it.y - ty) }
                if (h != null && (h.x - tx) * (h.x - tx) + (h.y - ty) * (h.y - ty) < 45f * 45f) listener.onCureTarget(h)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (world.phase == Phase.RUNNING && !paused && tapAddsZombies && !planeAimRequested && !cureMode) {
                world.dropZombies(e.x / d, e.y / d)
            }
            return true
        }

    })

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
                // Swiping only aims the plane after the PLANE button was pressed
                if (!multiTouch && planeAimRequested && world.phase == Phase.RUNNING && !paused) {
                    val dx = event.x - downX; val dy = event.y - downY
                    if (!aiming && dx * dx + dy * dy > (20 * d) * (20 * d)) {
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
                        planeAimRequested = false
                        buzz(HapticFeedbackConstants.VIRTUAL_KEY)
                        listener.onAirstrikeLaunched()
                        listener.onAnnounce("Plane is on the way!")
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
        gestures.onTouchEvent(event)
        return true
    }

    // ---------- loop

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1e9f, 0.05f)
        lastFrame = now
        if (!paused) world.update(dt * speed * (if (cureMode) 0.25f else 1f))
        if (flash > 0f) flash = max(0f, flash - dt * 2.2f)

        checkEvents()
        drawWorld(canvas)
        if (world.phase == Phase.SETUP) drawSetupHint(canvas)
        if (!covered) postInvalidateOnAnimation()
    }

    private fun checkEvents() {
        if (world.phase != lastPhase) {
            lastPhase = world.phase
            if (world.phase == Phase.RUNNING) { lastWave = world.wave; lastTurned = 0; lastTeam = teamSize() }
            listener.onPhaseChanged(world.phase)
        }
        if (world.phase != Phase.RUNNING) return
        if (world.wave != lastWave) {
            lastWave = world.wave
            listener.onAnnounce("Wave ${world.wave} is coming!")
            buzz(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (world.behemothArrived != lastBehemoth) {
            lastBehemoth = world.behemothArrived
            listener.onAnnounce("Warning! A behemoth is coming!")
            buzz(HapticFeedbackConstants.LONG_PRESS)
        }
        if (world.turned != lastTurned) {
            lastTurned = world.turned
            listener.onAnnounce("Oh no! Someone turned into a zombie.")
        }
        val team = teamSize()
        if (team > lastTeam) listener.onAnnounce("A new survivor joined your team!")
        lastTeam = team
    }

    fun teamSize() = world.humans.count { it.role != Role.STRAY && !it.dead }

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

        for (n in world.nukes) {
            val cx = n.x * d; val cy = n.y * d
            val r = (hypot(width.toDouble(), height.toDouble()) * 1.05).toFloat()
            val scorch = (1f - n.age / 6f).coerceIn(0f, 1f)
            nukePaint.style = Paint.Style.FILL
            nukePaint.color = Color.argb((50 * scorch).toInt(), 60, 30, 10)
            c.drawCircle(cx, cy, r * 0.6f, nukePaint)
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

        // Zombies, drawn in one batch (squares in shapes mode)
        zombiePaint.strokeCap = if (shapes) Paint.Cap.SQUARE else Paint.Cap.ROUND
        val zs = world.zombies
        if (points.size < zs.size * 2) points = FloatArray(zs.size * 4)
        for (pass in 0..1) {
            var k = 0
            for (z in zs) {
                if (z.type == ZType.FAT || z.type == ZType.ARMORED || z.type == ZType.BEHEMOTH) continue
                if (z.runner != (pass == 1)) continue
                points[k++] = z.x * d
                points[k++] = z.y * d
            }
            zombiePaint.color = if (pass == 1) runnerColor else zombieColor
            if (k > 0) c.drawPoints(points, 0, k, zombiePaint)
        }
        // Fat and armored zombies are drawn one by one
        for (z in zs) {
            val zx = z.x * d; val zy = z.y * d
            when (z.type) {
                ZType.FAT -> {
                    bigZombie.color = fatColor
                    val r = z.radius * d
                    if (shapes) c.drawRect(zx - r, zy - r, zx + r, zy + r, bigZombie) else c.drawCircle(zx, zy, r, bigZombie)
                    rimPaint.color = Color.argb(150, 255, 120, 110)
                    c.drawCircle(zx, zy, r, rimPaint)
                }
                ZType.ARMORED -> {
                    bigZombie.color = zombieColor
                    val r = z.radius * d
                    if (shapes) c.drawRect(zx - r, zy - r, zx + r, zy + r, bigZombie) else c.drawCircle(zx, zy, r, bigZombie)
                    if (z.armor > 0f) {
                        armorPaint.alpha = (110 + 145 * (z.armor / z.armorMax).coerceIn(0f, 1f)).toInt()
                        c.drawCircle(zx, zy, r + 2.2f * d, armorPaint)
                    }
                }
                ZType.BEHEMOTH -> drawBehemoth(c, z, zx, zy)
                else -> {}
            }
        }

        for (t in world.tracers) {
            tracerPaint.alpha = (t.life / 0.12f * 255).toInt().coerceIn(0, 255)
            c.drawLine(t.x1 * d, t.y1 * d, t.x2 * d, t.y2 * d, tracerPaint)
        }

        for (h in world.humans) drawHuman(c, h)

        for (b in world.blasts) {
            val p = (b.age / 0.7f).coerceIn(0f, 1f)
            val r = World.BOMB_RADIUS * d * (0.35f + 0.65f * sqrt(p))
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
                t.text.startsWith("WAVE") -> { floatPaint.color = zombieColor; floatPaint.textSize = 18 * d }
                t.text == "BEHEMOTH DOWN!" -> { floatPaint.color = Color.parseColor("#FFD27A"); floatPaint.textSize = 20 * d }
                t.text.startsWith("⚠") -> { floatPaint.color = Color.parseColor("#FF5A4A"); floatPaint.textSize = 17 * d }
                t.text.startsWith("ROUND") -> { floatPaint.color = Color.parseColor("#9CFF4D"); floatPaint.textSize = 15 * d }
                t.text.endsWith("zombies") -> { floatPaint.color = Color.parseColor("#FFB0A0"); floatPaint.textSize = 12 * d }
                t.text == "+HP" -> { floatPaint.color = Color.parseColor("#4BE36B"); floatPaint.textSize = 12 * d }
                t.text == "cured!" -> { floatPaint.color = Color.parseColor("#F08BE0"); floatPaint.textSize = 12 * d }
                t.text == "armor broke" -> { floatPaint.color = Color.parseColor("#C9D1DC"); floatPaint.textSize = 10 * d }
                t.text.startsWith("Survivor Joined") -> { floatPaint.color = hunterColor; floatPaint.textSize = 14 * d }
                t.text.endsWith("turned!") -> { floatPaint.color = Color.parseColor("#9CFF4D"); floatPaint.textSize = 13 * d }
                t.text.startsWith("+") -> { floatPaint.color = Color.parseColor("#FFB13B"); floatPaint.textSize = 18 * d }
                else -> { floatPaint.color = Color.WHITE; floatPaint.textSize = 12 * d }
            }
            floatPaint.alpha = (min(1f, t.life) * 255).toInt()
            c.drawText(t.text, t.x * d, t.y * d, floatPaint)
        }

        world.boss?.let { drawBossBar(c, it) }
        if (flash > 0f) c.drawColor(Color.argb((flash * 140).toInt(), 255, 240, 210))
        if (nukeCountdown > 0) drawCountdown(c)
    }

    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    }

    private fun drawCountdown(c: Canvas) {
        val t = (System.currentTimeMillis() % 1000L) / 1000f
        c.drawColor(Color.argb((40 + 50 * (1f - t)).toInt(), 255, 0, 0))
        countPaint.textSize = (150 - 30 * t) * d
        countPaint.setShadowLayer(30f * d, 0f, 0f, Color.RED)
        c.drawText("$nukeCountdown", width / 2f, height / 2f + 50 * d, countPaint)
        countPaint.textSize = 18 * d
        countPaint.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        c.drawText("Keep holding… let go to cancel", width / 2f, height / 2f + 100 * d, countPaint)
    }

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }
    private val hintRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Before the game starts: a pulsing "tap here" hint right on the map. */
    private fun drawSetupHint(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val t = (System.currentTimeMillis() % 1600L) / 1600f
        hintRing.strokeWidth = 2.5f * d
        hintRing.color = Color.argb(((1f - t) * 200).toInt(), 255, 224, 102)
        c.drawCircle(cx, cy - 40 * d, (14f + 36f * t) * d, hintRing)
        corePaint.color = playerColor
        c.drawCircle(cx, cy - 40 * d, World.HUMAN_R * 1.4f * d, corePaint)
        hintPaint.textSize = 20 * d
        c.drawText("👆 Tap anywhere on the map", cx, cy + 30 * d, hintPaint)
        hintPaint.textSize = 16 * d
        c.drawText("to put your team down", cx, cy + 56 * d, hintPaint)
    }

    private val hpBack = Paint().apply { color = Color.parseColor("#AA000000") }
    private val hpFill = Paint().apply { color = Color.parseColor("#FF3B3B") }
    private val armorFill = Paint().apply { color = Color.parseColor("#C9D1DC") }
    private val behemothPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val behemothRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun drawBehemoth(c: Canvas, z: Zombie, zx: Float, zy: Float) {
        val r = z.radius * d
        val pulse = 0.5f + 0.5f * kotlin.math.sin(System.currentTimeMillis() / 400.0).toFloat()
        // Menacing glow
        behemothPaint.color = Color.argb((40 + 40 * pulse).toInt(), 255, 30, 30)
        c.drawCircle(zx, zy, r + 8 * d, behemothPaint)
        behemothPaint.color = Color.parseColor("#7A1018")
        if (shapes) c.drawRect(zx - r, zy - r, zx + r, zy + r, behemothPaint) else c.drawCircle(zx, zy, r, behemothPaint)
        behemothRim.strokeWidth = 2f * d
        behemothRim.color = Color.argb(200, 255, 80, 70)
        c.drawCircle(zx, zy, r * 0.75f, behemothRim)
        if (z.armor > 0f) {
            behemothRim.strokeWidth = 4f * d
            behemothRim.color = Color.argb((120 + 135 * (z.armor / z.armorMax).coerceIn(0f, 1f)).toInt(), 201, 209, 220)
            c.drawCircle(zx, zy, r + 3 * d, behemothRim)
        }
    }

    private val bossName = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD0C8"); textSize = 13 * d; letterSpacing = 0.2f
        typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        setShadowLayer(6f, 0f, 1f, Color.BLACK)
    }
    private val bossFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.parseColor("#AAFFFFFF") }
    private val bossRect = RectF()

    /** Classic boss health bar along the bottom of the map. */
    private fun drawBossBar(c: Canvas, z: Zombie) {
        val left = width * 0.08f
        val right = width * 0.92f
        val barH = 9 * d
        val bottom = height - 16 * d
        val top = bottom - barH
        val armored = z.armorMax > 0f
        c.drawText(if (armored) "☠  ARMORED BEHEMOTH" else "☠  BEHEMOTH", left, top - (if (armored) 12 else 6) * d, bossName)
        bossRect.set(left, top, right, bottom)
        c.drawRoundRect(bossRect, 3 * d, 3 * d, hpBack)
        val hpFrac = (z.hp / z.maxHp).coerceIn(0f, 1f)
        bossRect.set(left, top, left + (right - left) * hpFrac, bottom)
        c.drawRoundRect(bossRect, 3 * d, 3 * d, hpFill)
        bossRect.set(left, top, right, bottom)
        c.drawRoundRect(bossRect, 3 * d, 3 * d, bossFrame)
        if (armored) {
            val at = top - 7 * d
            bossRect.set(left, at, right, at + 4 * d)
            c.drawRoundRect(bossRect, 2 * d, 2 * d, hpBack)
            bossRect.set(left, at, left + (right - left) * (z.armor / z.armorMax).coerceIn(0f, 1f), at + 4 * d)
            c.drawRoundRect(bossRect, 2 * d, 2 * d, armorFill)
        }
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = 1f - t
        return Color.rgb(
            (Color.red(a) * u + Color.red(b) * t).toInt(),
            (Color.green(a) * u + Color.green(b) * t).toInt(),
            (Color.blue(a) * u + Color.blue(b) * t).toInt()
        )
    }

    private fun drawHuman(c: Canvas, h: Human) {
        val base = when (h.role) { Role.PLAYER -> playerColor; Role.HUNTER -> hunterColor; Role.STRAY -> strayColor }
        val inf = h.infection.coerceIn(0f, 1f)
        val col = blend(base, zombieColor, inf * 0.85f)
        val hx = h.x * d; val hy = h.y * d
        glowPaint.color = if (h.hurtFlash > 0f) zombieColor else col
        glowPaint.alpha = (55 + 120 * h.hurtFlash).toInt().coerceAtMost(255)
        c.drawCircle(hx, hy, 10 * d, glowPaint)
        corePaint.color = col
        val r = World.HUMAN_R * d
        if (shapes) {
            when (h.role) {
                Role.PLAYER -> drawStar(c, hx, hy, r * 1.5f)
                Role.HUNTER -> { c.drawCircle(hx, hy, r, corePaint); c.drawCircle(hx, hy, r + 2.2f * d, ringPaint) }
                Role.STRAY -> { ringPaint.color = col; c.drawCircle(hx, hy, r, ringPaint); ringPaint.color = Color.WHITE }
            }
        } else {
            c.drawCircle(hx, hy, r, corePaint)
        }
        if (inf > 0.01f) {
            infRect.set(hx - 8.5f * d, hy - 8.5f * d, hx + 8.5f * d, hy + 8.5f * d)
            c.drawArc(infRect, -90f, 360f * inf, false, infPaint)
        }
        val now = System.currentTimeMillis()
        if (cureMode && h.role != Role.STRAY) {
            val most = world.mostInfected()
            if (h === most) {
                val p = (now % 900L) / 900f
                cureRing.color = Color.argb(((1f - p) * 255).toInt(), 240, 139, 224)
                c.drawCircle(hx, hy, (12f + 10f * p) * d, cureRing)
                labelPaint.color = Color.parseColor("#F08BE0")
                labelPaint.textSize = 10 * d
                c.drawText("most infected", hx, hy + 22 * d, labelPaint)
            } else {
                cureRing.color = Color.argb(140, 240, 139, 224)
                c.drawCircle(hx, hy, 12f * d, cureRing)
            }
            if (inf > 0.01f) {
                labelPaint.color = Color.WHITE
                labelPaint.textSize = 10 * d
                c.drawText("${(inf * 100).toInt()}%", hx, hy - 13 * d, labelPaint)
            }
        } else if (inf >= 0.75f) {
            // About to turn: pulsing warning sign
            val pulse = 0.6f + 0.4f * kotlin.math.sin(now / 150.0).toFloat()
            labelPaint.color = Color.argb((255 * pulse).toInt(), 255, 210, 60)
            labelPaint.textSize = 14 * d
            c.drawText("⚠", hx, hy - 12 * d, labelPaint)
        }
    }

    private val cureRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        for (i in 0 until 10) {
            val a = -Math.PI / 2 + i * Math.PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val px = x + (kotlin.math.cos(a) * rr).toFloat()
            val py = y + (kotlin.math.sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, corePaint)
    }

    private fun drawPlane(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        val s = 1.4f * d
        path.reset()
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
        c.save(); c.translate(x + 10 * d, y + 14 * d); c.rotate(angle); c.drawPath(path, planeShadow); c.restore()
        c.save(); c.translate(x, y); c.rotate(angle); c.drawPath(path, planePaint); c.restore()
    }

    private fun drawAim(c: Canvas) {
        val dx = aimEX - aimSX; val dy = aimEY - aimSY
        val len = hypot(dx, dy)
        val ready = world.airstrikeReady
        val longEnough = len / d >= World.MIN_STRIKE_LENGTH
        val col = when {
            !ready -> Color.parseColor("#8A93A3")
            !longEnough -> Color.parseColor("#BFA070")
            else -> Color.parseColor("#FFB13B")
        }
        aimBand.color = col
        aimBand.alpha = 45
        aimBand.strokeWidth = World.BOMB_RADIUS * 2 * d
        c.drawLine(aimSX, aimSY, aimEX, aimEY, aimBand)
        aimLine.color = col
        c.drawLine(aimSX, aimSY, aimEX, aimEY, aimLine)
        if (len > 1f) {
            val ux = dx / len; val uy = dy / len
            val hs = 16 * d
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
            !ready -> "Plane comes back in ${world.airstrikeCooldown.toInt() + 1}s"
            !longEnough -> "Keep dragging…"
            else -> "Let go to send the plane!"
        }
        c.drawText(label, (aimSX + aimEX) / 2f, (aimSY + aimEY) / 2f - 26 * d, aimText)
    }
}
