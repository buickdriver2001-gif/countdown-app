package com.gamehub.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** One game in the hub. */
class GameEntry(val name: String, val pkg: String, val accent: Int, val icon: Int,
                val custom: Boolean = false, val drawable: android.graphics.drawable.Drawable? = null) {
    var installed = false
    /** Tile size in grid cells (width × height). */
    var tileW = 2
    var tileH = 1

    // Text layout, worked out once per tile size rather than every frame
    internal var rowsW = -1f; internal var rowsStep = -1f; internal var rowsSub: String? = null; internal var rowsBase = -1; internal var rows = 1
    internal var planW = -1f; internal var planH = -1f; internal var planBase = -1f; internal var planSub: String? = null
    internal var plan: TileText.Plan? = null
    // PS2 menu: the name as shown on its line (shortened with … only if it's too long), worked out once
    internal var listName: String? = null; internal var listNameW = -1f
}

/**
 * The hub screen: a dark void full of slowly turning towers of glowing glass cubes,
 * drifting motes of light, and a simple glowing menu of games.
 */
@SuppressLint("ViewConstructor")
class HubView(context: Context, var games: List<GameEntry>, private val onPick: (GameEntry) -> Unit,
              private val onAdd: () -> Unit, private val onRemove: (GameEntry) -> Unit, private val sfx: Sfx,
              private val onSettings: () -> Unit, private val onReorder: (Int, Int) -> Unit,
              private val onDrawer: () -> Unit, private val onRecent: () -> Unit,
              private val onSystem: () -> Unit) : View(context) {

    private val d = resources.displayMetrics.density
    private val rnd = Random(3)

    /** A column of small glass blocks. Heights slowly rise and fall. */
    private class Tower(val x: Float, val z: Float, val cubes: Int, val size: Float, val phase: Float, val swing: Float)
    /** Towers stand in clusters around you, like a city of light. */
    private class Cluster(val x: Float, val z: Float)
    private val clusters = List(8) {
        val a = it / 8f * 6.283f + rnd.nextFloat() * 0.5f
        val r = 7f + rnd.nextFloat() * 8f
        Cluster(sin(a) * r, cos(a) * r)
    }
    private val towers = clusters.flatMap { cl ->
        List(4 + rnd.nextInt(4)) {
            val ox = (rnd.nextFloat() - 0.5f) * 2.6f; val oz = (rnd.nextFloat() - 0.5f) * 2.6f
            Tower(cl.x + ox, cl.z + oz, 3 + rnd.nextInt(13), 0.42f + rnd.nextFloat() * 0.12f, rnd.nextFloat() * 6.283f, 1f + rnd.nextFloat() * 3f)
        }
    }
    /** Soft clouds of blue haze drifting through the scene (screen space). */
    private class Haze(var x: Float, val y: Float, val r: Float, val speed: Float, val alpha: Int)
    private val haze = List(7) { Haze(rnd.nextFloat(), 0.15f + rnd.nextFloat() * 0.7f, 0.35f + rnd.nextFloat() * 0.45f, (rnd.nextFloat() - 0.5f) * 0.02f, 18 + rnd.nextInt(22)) }
    private class Mote(var x: Float, var y: Float, var z: Float, val speed: Float)
    private val motes = List(70) { Mote((rnd.nextFloat() - 0.5f) * 30f, -4f + rnd.nextFloat() * 10f, (rnd.nextFloat() - 0.5f) * 30f, 0.15f + rnd.nextFloat() * 0.3f) }

    private var yaw = 0f
    private var t = 0f
    /** The world behind the menu: grey glass towers and balls of coloured light (after the PS2's start-up screen). */
    private val world = Ps2World(context)
    /** A NEXUS panel (Settings and the like) is open over the home screen: crystals come out, the menu steps back. */
    var panelOpen = false
        set(v) { field = v; invalidate() }
    private var crystalK = 0f
    /** The PS2-style crystals: a slow ring of them turning in the space above the menu. */
    private val crystals by lazy { Crystals(context) }
    private var homeCrystalK = 0f
    /** Start-up animation clock (the hub appears when it finishes). */
    private var bootT = 0f
    var booting = true
    private val BOOT_LEN = 5.2f
    /** How long the home screen takes to fade up: slow after the start-up show, quick otherwise. */
    private var introLen = 2.5f
    /** Taps count once the home screen has been fading up this long. */
    private var readyT = 1.2f

    /**
     * Straight to the home screen with a quick fade (no start-up show): used whenever Android simply
     * brings NEXUS back (after unlocking, from Recent Apps, or if it had to restart NEXUS in the background).
     */
    fun skipIntro() { booting = false; t = 0f; introLen = 0.45f; readyT = 0.12f; lastNs = 0L; invalidate() }
    private class Spark(val a: Float, val r0: Float, val speed: Float, val size: Float)
    private val bootSparks = List(140) { Spark(rnd.nextFloat() * 6.283f, 0.6f + rnd.nextFloat() * 0.8f, 0.6f + rnd.nextFloat() * 0.8f, 0.6f + rnd.nextFloat() * 1.6f) }
    private var lastNs = 0L
    private var selected = -1
    private var launchFlash = 0f
    private var pending: GameEntry? = null
    /** Fade up from black when coming back from an app. */
    private var enterFade = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val path = Path()
    private val rect = RectF()
    /** The clock follows the phone's 12 / 24-hour setting. */
    private var clock = SimpleDateFormat("h:mm a", Locale.getDefault())
    private var clock24: Boolean? = null

    private val rows = ArrayList<RectF>()
    /** Reused every frame: the tiles' touch areas, and the cached tile layout. */
    private val hitPool = ArrayList<RectF>()
    private val tileR = RectF()
    private val glyphR = RectF()
    private var packGames: List<GameEntry>? = null
    private var packSig = 0
    private var packOrder = ArrayList<Int>()
    private var packPos: Array<IntArray> = emptyArray()

    // Shaders made once and placed by moving/scaling the canvas, so drawing a frame creates no new objects
    private val hLine3 = LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val ruleShader = LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(Color.TRANSPARENT, Color.rgb(130, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val streakShader = LinearGradient(0f, 0f, 1f, 0f, Color.TRANSPARENT, Color.rgb(190, 220, 255), Shader.TileMode.CLAMP)
    private val shotShade = LinearGradient(0f, 0f, 0f, 1f, Color.argb(40, 2, 4, 14), Color.argb(235, 2, 4, 14), Shader.TileMode.CLAMP)
    private val flashGlow = RadialGradient(0f, 0f, 1f, Color.argb(200, 170, 210, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val emberGlow = RadialGradient(0f, 0f, 1f, Color.argb(110, 255, 140, 50), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val topFade = LinearGradient(0f, 0f, 0f, 1f, Color.rgb(4, 8, 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val bottomFade = LinearGradient(0f, 0f, 0f, 1f, Color.TRANSPARENT, Color.rgb(2, 4, 12), Shader.TileMode.CLAMP)
    /** PS2 menu: a soft dark band behind the menu, so names stand out from the world behind. */
    private val menuScrim = LinearGradient(0f, 0f, 0f, 1f,
        intArrayOf(Color.TRANSPARENT, Color.argb(120, 2, 4, 14), Color.argb(120, 2, 4, 14), Color.TRANSPARENT),
        floatArrayOf(0f, 0.18f, 0.82f, 1f), Shader.TileMode.CLAMP)

    // Date and clock text, worked out once a second rather than every frame
    private var clockSec = -1L
    private var dateText = ""
    private var clockText = ""
    private fun updateClock() {
        val now = System.currentTimeMillis()
        if (now / 1000 == clockSec) return
        clockSec = now / 1000
        val h24 = try { android.text.format.DateFormat.is24HourFormat(context) } catch (_: Exception) { false }
        if (h24 != clock24) { clock24 = h24; clock = SimpleDateFormat(if (h24) "H:mm" else "h:mm a", Locale.getDefault()) }
        val date = Date(now)
        dateText = dateFmt.format(date).uppercase(); clockText = clock.format(date)
    }

    // ------------------------------------------------------------------ 3D helpers

    private var px = 0f; private var py = 0f; private var pz = 0f
    private val camY = 0.6f

    /** World point to screen; returns false when behind the camera. */
    private fun project(x: Float, y: Float, z: Float): Boolean {
        val c = cos(yaw); val s = sin(yaw)
        val xr = x * c - z * s
        val zr = x * s + z * c
        if (zr < 0.6f) return false
        val f = width * 0.95f
        px = width / 2f + xr / zr * f
        py = height * 0.42f - (y - camY) / zr * f
        pz = zr
        return true
    }

    // ------------------------------------------------------------------ drawing

    /** True while something covers NEXUS (the app drawer): stop animating to save battery. */
    var covered = false
        set(v) { field = v; if (!v) { lastNs = 0L; invalidate() } }

    /** Draws a frame; if anything ever goes wrong while drawing, that frame is skipped and noted, never a crash. */
    override fun onDraw(c: Canvas) {
        val saved = c.saveCount
        try { drawFrame(c) } catch (t: Throwable) {
            c.restoreToCount(saved)
            if (!drawProblemNoted) { drawProblemNoted = true; App.noteProblem(context, "the home screen", t) }
            postInvalidateOnAnimation()
        }
    }
    private var drawProblemNoted = false

    private fun drawFrame(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now
        if (booting) {
            bootT += dt
            drawBoot(c)
            if (bootT >= BOOT_LEN) { booting = false; t = 0f; introLen = 2.5f; readyT = 1.2f }
            postInvalidateOnAnimation()
            return
        }
        t += dt
        yaw += dt * 0.045f
        adaptQuality(dt)
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / introLen)

        // Deep blue-black void with a soft horizon glow (drawn once, then reused)
        c.drawBitmap(backdrop(), 0f, 0f, null)

        drawHaze(c, dt, intro)
        world.quality = quality
        world.draw(c, width, height, dt, intro, 0.6f, if (ps2Menu) 0.62f else 0.8f)
        // (A panel open: the menu steps back; the panel's own layer shows the crystals)
        crystalK = if (panelOpen) min(1f, crystalK + dt * 2.5f) else max(0f, crystalK - dt * 3f)
        // Crystals over the towers, in the open space above the menu: a carousel seen from a little above
        // (they make way for a panel's own crystals while one is open)
        if (ps2Menu) {
            homeCrystalK = if (panelOpen) max(0f, homeCrystalK - dt * 3f) else min(1f, homeCrystalK + dt * 1.2f)
            val ck = homeCrystalK * intro * 0.85f
            if (ck > 0.01f) crystals.draw(c, width, height, dt, ck, w / 2f, h * 0.25f, w * 0.34f, h * 0.06f, 0.8f)
        }
        drawMenu(c, intro, dt)

        if (launchFlash > 0f) {
            launchFlash -= dt
            val k = 1f - (launchFlash / LAUNCH_LEN).coerceIn(0f, 1f)
            // A soft glow blooms out of the chosen row, then everything sinks into deep blue-black
            val row = rows.getOrNull(selected)
            if (row != null) {
                val rad = max(1f, w * (0.2f + 1.4f * k))
                c.save(); c.translate(row.centerX(), row.centerY()); c.scale(rad, rad)
                fill.color = Color.WHITE; fill.alpha = (255 * (1f - k * 0.6f)).toInt(); fill.shader = flashGlow
                c.drawRect(-row.centerX() / rad, -row.centerY() / rad, (w - row.centerX()) / rad, (h - row.centerY()) / rad, fill); fill.shader = null
                c.restore()
            }
            fill.color = Color.argb((255 * (k * k)).toInt().coerceIn(0, 255), 2, 4, 14)
            c.drawRect(0f, 0f, w, h, fill)
            // The app starts opening just after the glow begins (no waiting for the animation to finish);
            // the rest of the animation plays underneath as the app rises in over it
            if (k >= LAUNCH_START || launchFlash <= 0f) { val p = pending; pending = null; p?.let(onPick) }
        }
        if (enterFade > 0f) {
            enterFade -= dt
            val r = (enterFade / ENTER_LEN).coerceIn(0f, 1f)
            fill.color = Color.argb((255 * r * r).toInt(), 2, 4, 14)
            c.drawRect(0f, 0f, w, h, fill)
        }
        // Fade in from black at start-up
        if (intro < 1f) { fill.color = Color.argb(((1f - intro) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, fill) }
        if (!covered) {
            // Under NEXUS's cover (an app opening or closing, apps being closed): hold this frame and pause, so the
            // phone puts everything into the change; carry on the moment the cover starts to lift
            if (NexusAccessibility.homeHidden && launchFlash <= 0f) { removeCallbacks(resumeAfterCover); postDelayed(resumeAfterCover, 50) }
            // Nothing moving but the slow background, on a screen faster than 60 Hz: every other frame (it looks the
            // same, for about half the work); a touch brings back every frame at once
            else if (idle(intro)) {
                // (One frame waiting at a time, so an extra redraw from elsewhere can't start a second chain)
                val now = android.os.SystemClock.uptimeMillis()
                if (!framePosted || now - framePostedAt > 100L) { framePosted = true; framePostedAt = now; postOnAnimation(nextFrame) }
            }
            else postInvalidateOnAnimation()
        }
    }

    private var framePosted = false
    private var framePostedAt = 0L
    private val nextFrame = Runnable { framePosted = false; postInvalidateOnAnimation() }
    /** When the screen was last touched. */
    private var touchedAt = 0L
    private var refreshHz = 60f
    private var refreshAt = 0L

    /** Only the slow background is moving (no touch for a moment, no scrolling or animations of the menu itself). */
    private fun idle(intro: Float): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - refreshAt > 2000L) { refreshAt = now; refreshHz = try { display?.refreshRate ?: 60f } catch (_: Throwable) { 60f } }
        if (refreshHz < 75f || now - touchedAt < 2500L) return false
        if (intro < 1f || dragging || moveIndex >= 0 || scrollVel != 0f || homing || !snapTo.isNaN() || settling) return false
        if (launchFlash > 0f || enterFade > 0f || settle > 0f || pending != null) return false
        if (crystalK != 0f && crystalK != 1f) return false
        if (ps2Menu && homeCrystalK != 0f && homeCrystalK != 1f) return false
        return now - nothingOpenAt > NOTE_MS
    }

    private val resumeAfterCover = object : Runnable {
        override fun run() { if (NexusAccessibility.homeHidden) postDelayed(this, 50) else invalidate() }
    }

    /**
     * Start-up: sparks of light spiral in out of the dark, gather into a turning glass cube,
     * a burst of light, then the name, then the hub fades up.
     */
    private fun drawBoot(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val bt = bootT
        val cx0 = w / 2; val cy0 = h * 0.45f
        c.drawColor(Color.BLACK)
        val unit = min(w, h)
        // 1) Sparks spiral inward
        if (bt < 2.6f) {
            for (sp in bootSparks) {
                val k = ((bt * sp.speed) / 2.2f).coerceIn(0f, 1f)
                val r = sp.r0 * unit * (1f - k * k)
                val ang = sp.a + k * 3.5f
                val a = (min(1f, bt / 0.6f) * (1f - k * 0.3f) * 230).toInt()
                fill.color = Color.argb(a, 150, 195, 255)
                c.drawCircle(cx0 + cos(ang) * r, cy0 + sin(ang) * r * 0.75f, sp.size * d, fill)
            }
        }
        // 2) The glass cube forms and turns
        if (bt in 1.6f..4.2f) {
            val k = ((bt - 1.6f) / 0.9f).coerceIn(0f, 1f)
            val fade = 1f - ((bt - 3.6f) / 0.6f).coerceIn(0f, 1f)
            val size = unit * 0.12f * (0.4f + 0.6f * k)
            fill.shader = RadialGradient(cx0, cy0, size * 3f, intArrayOf(Color.argb((120 * k * fade).toInt(), 70, 130, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx0, cy0, size * 3f, fill); fill.shader = null
            bootCube(c, cx0, cy0, size, bt * 1.4f, (k * fade))
            // Light rays
            line.strokeWidth = 1.5f * d
            for (i in 0 until 12) {
                val a = i / 12f * 6.283f + bt * 0.4f
                line.color = Color.argb((60 * k * fade).toInt(), 160, 200, 255)
                c.drawLine(cx0 + cos(a) * size * 1.4f, cy0 + sin(a) * size * 1.4f, cx0 + cos(a) * size * (3f + 2f * k), cy0 + sin(a) * size * (3f + 2f * k), line)
            }
        }
        // 3) A burst of light
        if (bt in 3.3f..4.3f) {
            val k = (bt - 3.3f) / 1f
            val a = (sin(k * Math.PI).toFloat() * 255).toInt().coerceIn(0, 255)
            fill.shader = RadialGradient(cx0, cy0, unit * (0.2f + k * 1.2f), intArrayOf(Color.argb(a, 230, 240, 255), Color.argb(a / 3, 120, 170, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fill); fill.shader = null
        }
        // 4) The name
        if (bt > 3.7f) {
            val k = ((bt - 3.7f) / 0.8f).coerceIn(0f, 1f)
            val out = ((bt - 4.6f) / 0.6f).coerceIn(0f, 1f)
            val a = (255 * k * (1f - out)).toInt()
            text.textAlign = Paint.Align.CENTER
            text.textSize = 36 * d
            text.letterSpacing = 0.9f - 0.55f * k
            text.setShadowLayer(18 * d, 0f, 0f, Color.argb(a, 80, 150, 255))
            text.color = Color.argb(a, 240, 246, 255)
            // (No name shown: just the light)
            text.setShadowLayer(0f, 0f, 0f, 0)
            text.letterSpacing = 0.15f
        }
    }

    private val bx = FloatArray(8); private val by = FloatArray(8)

    private fun bootCube(c: Canvas, cx0: Float, cy0: Float, s: Float, ang: Float, alpha: Float) {
        val ca = cos(ang); val sa = sin(ang); val cb = cos(0.5f); val sb = sin(0.5f)
        var i = 0
        for (yy in 0..1) for (zz in 0..1) for (xx in 0..1) {
            val x = (xx * 2 - 1).toFloat(); val y = (yy * 2 - 1).toFloat(); val z = (zz * 2 - 1).toFloat()
            val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
            val y2 = y * cb - z1 * sb; val z2 = y * sb + z1 * cb
            val persp = 3.2f / (3.2f + z2)
            bx[i] = cx0 + x1 * s * persp; by[i] = cy0 - y2 * s * persp
            i++
        }
        val faces = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
        for (f in faces) {
            path.reset(); path.moveTo(bx[f[0]], by[f[0]])
            for (j in 1..3) path.lineTo(bx[f[j]], by[f[j]])
            path.close()
            fill.color = Color.argb((40 * alpha).toInt(), 90, 150, 255)
            c.drawPath(path, fill)
        }
        line.strokeWidth = 1.8f * d
        line.color = Color.argb((230 * alpha).toInt(), 170, 210, 255)
        val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        var e = 0
        while (e < edges.size) { c.drawLine(bx[edges[e]], by[edges[e]], bx[edges[e + 1]], by[edges[e + 1]], line); e += 2 }
    }

    /**
     * Detail level for the background: 2 = everything, 1 = no grid floor, 0 = also no far towers / extras.
     * If the phone starts dropping frames, detail is lowered so taps and transitions stay smooth;
     * when there's room again it comes back.
     */
    private var quality = 2
    private var frameAvg = 1f / 60f
    private var slowFor = 0f
    private var fastFor = 0f

    private fun adaptQuality(dt: Float) {
        if (dt <= 0f || dt > 0.25f) return // ignore pauses (screen off, app switch)
        frameAvg += (dt - frameAvg) * 0.08f
        if (frameAvg > 1f / 45f) { slowFor += dt; fastFor = 0f } else if (frameAvg < 1f / 57f) { fastFor += dt; slowFor = 0f } else { slowFor = 0f; fastFor = 0f }
        if (slowFor > 1.2f && quality > 0) { quality--; slowFor = 0f }
        if (fastFor > 6f && quality < 2) { quality++; fastFor = 0f }
    }

    private var bgBmp: android.graphics.Bitmap? = null

    private fun backdrop(): android.graphics.Bitmap {
        val cur = bgBmp
        if (cur != null && cur.width == width && cur.height == height) return cur
        cur?.recycle()
        val w = width.toFloat(); val h = height.toFloat()
        val b = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(2, 3, 10), Color.rgb(6, 12, 34), Color.rgb(1, 2, 6)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = RadialGradient(w / 2, h * 0.45f, w * 0.9f, intArrayOf(Color.argb(80, 40, 90, 220), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        // Dark vignette around the edges
        p.shader = RadialGradient(w / 2, h * 0.45f, max(w, h) * 0.75f, intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(170, 0, 0, 4)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        bgBmp = b
        return b
    }

    private val order = ArrayList<Int>()

    // Batched drawing buffers (reused every frame, so nothing is created while animating)
    private var vtx = FloatArray(6 * 2 * 5 * 12)
    private var vcol = IntArray(6 * 5 * 12 * 2)
    private var vn = 0
    private val lineBuf = Array(3) { FloatArray(4 * 12 * 12) }
    private val lineN = IntArray(3)
    private val useVertices = android.os.Build.VERSION.SDK_INT >= 29

    /** A soft round glow, drawn once and reused (scaled) for every glow on screen. */
    private val glowBmp: android.graphics.Bitmap by lazy {
        // (High resolution, with a smooth bell-shaped falloff: no rings, sharp at any size)
        val s = 256
        val b = android.graphics.Bitmap.createBitmap(s, s, android.graphics.Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f,
            intArrayOf(Color.argb(255, 120, 170, 255), Color.argb(200, 105, 160, 255), Color.argb(130, 90, 150, 255), Color.argb(70, 80, 140, 255),
                Color.argb(30, 75, 130, 255), Color.argb(8, 70, 120, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.14f, 0.3f, 0.48f, 0.66f, 0.84f, 1f), Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(s / 2f, s / 2f, s / 2f, p)
        b
    }
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glowDst = RectF()

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        if (alpha <= 0 || r < 1f) return
        glowPaint.alpha = alpha.coerceIn(0, 255)
        glowDst.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(glowBmp, null, glowDst, glowPaint)
    }

    private fun drawTowers(c: Canvas, intro: Float) {
        // Draw far towers first
        order.clear(); order.addAll(towers.indices)
        val cy = cos(yaw); val sy = sin(yaw)
        order.sortByDescending { val tw = towers[it]; tw.x * sy + tw.z * cy }
        for (i in order) {
            val tw = towers[i]
            if (quality == 0 && (tw.x * sy + tw.z * cy) > 11f) continue
            val bob = sin(t * 0.4f + tw.phase) * 0.06f
            // Heights breathe slowly, like the towers are alive
            val live = (tw.cubes + sin(t * 0.28f + tw.phase * 2f) * tw.swing).coerceAtLeast(1f)
            val built = (intro * 1.4f * live).toInt().coerceAtMost(live.toInt())
            vn = 0; lineN.fill(0)
            var fogT = 1f
            for (k in 0 until built) {
                val y0 = -3.4f + k * tw.size * 1.12f + bob
                fogT = addCube(tw.x, y0, tw.z, tw.size, k, tw)
            }
            flushTower(c, fogT)
        }
    }

    /** Glow pooled under each cluster of towers. */
    private fun clusterGlows(c: Canvas) {
        for (cl in clusters) if (project(cl.x, -3.5f, cl.z)) glow(c, px, py, width * 2.2f / pz, (110 * fogK(pz)).toInt())
    }

    private fun drawHaze(c: Canvas, dt: Float, intro: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        for (hz in haze) {
            hz.x += hz.speed * dt
            if (hz.x < -0.6f) hz.x += 2.2f
            if (hz.x > 1.6f) hz.x -= 2.2f
            glow(c, hz.x * w, hz.y * h, hz.r * w, (hz.alpha * intro).toInt())
        }
    }

    /** A soft orb of light wandering slowly through the towers. */
    private fun drawOrb(c: Canvas, intro: Float) {
        val ox = sin(t * 0.07f) * 6f; val oz = cos(t * 0.05f) * 6f + 2f
        val oy = 0.8f + sin(t * 0.23f) * 1.2f
        if (!project(ox, oy, oz)) return
        val r = width * 0.9f / pz
        val pulse = 0.8f + 0.2f * sin(t * 1.7f)
        glow(c, px, py, r * 2.6f, (70 * intro * pulse).toInt())
        glow(c, px, py, r, (190 * intro * pulse).toInt())
        fill.color = Color.argb((235 * intro).toInt(), 235, 245, 255)
        c.drawCircle(px, py, max(1.5f, r * 0.14f), fill)
    }

    private fun flushTower(c: Canvas, fog: Float) {
        if (vn > 0) {
            if (useVertices) {
                c.drawVertices(Canvas.VertexMode.TRIANGLES, vn * 2, vtx, 0, null, 0, vcol, 0, null, 0, 0, fill.apply { color = Color.WHITE; shader = null })
            } else {
                // Older Android: draw the triangles as small paths
                var v = 0
                while (v < vn) {
                    path.reset(); path.moveTo(vtx[v * 2], vtx[v * 2 + 1]); path.lineTo(vtx[v * 2 + 2], vtx[v * 2 + 3]); path.lineTo(vtx[v * 2 + 4], vtx[v * 2 + 5]); path.close()
                    fill.color = vcol[v]; c.drawPath(path, fill); v += 3
                }
            }
        }
        line.strokeWidth = max(0.6f, 1.2f * d * fog)
        for (b in 0 until 3) {
            if (lineN[b] == 0) continue
            line.color = Color.argb(((70 + 60 * b) * fog).toInt(), 150 + 30 * b, 195 + 20 * b, 255)
            c.drawLines(lineBuf[b], 0, lineN[b], line)
        }
    }

    private fun fogK(z: Float) = (1f - (z - 4f) / 16f).coerceIn(0.12f, 1f)

    private val cx = FloatArray(8); private val cyA = FloatArray(8)
    private val faces = arrayOf(intArrayOf(4, 5, 7, 6), intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6), intArrayOf(0, 2, 6, 4), intArrayOf(1, 3, 7, 5))
    private val quadOrder = intArrayOf(0, 1, 2, 0, 2, 3)
    private val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)

    /** Adds one glass cube's faces and edges to the batch. Returns its fog amount. */
    private fun addCube(x: Float, y: Float, z: Float, s: Float, k: Int, tw: Tower): Float {
        val h = s / 2
        var i = 0
        for (dy in 0..1) for (dz in 0..1) for (dx in 0..1) {
            if (!project(x + (dx * 2 - 1) * h, y + dy * s, z + (dz * 2 - 1) * h)) return 1f
            cx[i] = px; cyA[i] = py
            i++
        }
        project(x, y + h, z)
        val fog = fogK(pz)
        // Some cubes shimmer brighter now and then
        val twinkle0 = max(0f, sin(t * 1.3f + tw.phase * 3f + k * 1.7f)).let { it * it * it * it }
        // Every few seconds a band of white light passes across all the towers
        val pass = (t % PASS_EVERY) / PASS_LEN
        val bandX = -0.3f * width + pass * width * 1.6f
        val sweep = if (pass <= 1f) kotlin.math.exp(-((px - bandX) / (70 * d)).let { it * it }) else 0f
        val twinkle = min(1f, twinkle0 + sweep * 1.2f)
        val base = 26 + (40 * twinkle).toInt()
        if (vtx.size < (vn + 30) * 2) { vtx = vtx.copyOf(vtx.size * 2); vcol = vcol.copyOf(vcol.size * 2) }
        for (fi in faces.indices) {
            val f = faces[fi]
            val top = fi == 0
            val col = Color.argb(((if (top) base + 20 else base) * fog).toInt(), if (top) 120 else 60, if (top) 170 else 115, 255)
            for (j in quadOrder) {
                vtx[vn * 2] = cx[f[j]]; vtx[vn * 2 + 1] = cyA[f[j]]; vcol[vn] = col; vn++
            }
        }
        // Edges go into one of three brightness groups
        val b = (twinkle * 2.99f).toInt().coerceIn(0, 2)
        var buf = lineBuf[b]
        if (buf.size < lineN[b] + 48) { buf = buf.copyOf(buf.size * 2); lineBuf[b] = buf }
        var e = 0
        while (e < edges.size) {
            buf[lineN[b]++] = cx[edges[e]]; buf[lineN[b]++] = cyA[edges[e]]
            buf[lineN[b]++] = cx[edges[e + 1]]; buf[lineN[b]++] = cyA[edges[e + 1]]
            e += 2
        }
        return fog
    }

    private val PASS_EVERY = 6.5f
    private val PASS_LEN = 2.2f

    /** A faint grid far below, with a scan ring pulsing out from the middle. */
    private val gridBuf = Array(4) { FloatArray(1024) }
    private val gridN = IntArray(4)

    private fun drawGrid(c: Canvas, intro: Float) {
        val gy = -3.6f
        val span = 22f
        gridN.fill(0)
        var gx = -span
        while (gx <= span) {
            gridLine(gx, gy, -span, gx, gy, span)
            gridLine(-span, gy, gx, span, gy, gx)
            gx += 2.75f
        }
        // Nearer lines are brighter: four brightness groups, one draw call each
        line.strokeWidth = 1f * d
        for (b in 0 until 4) {
            if (gridN[b] == 0) continue
            line.color = Color.argb(((8 + b * 8) * intro).toInt(), 110, 160, 255)
            c.drawLines(gridBuf[b], 0, gridN[b], line)
        }
        // Scan ring
        val k = (t % 5f) / 5f
        val rr = 1f + k * 21f
        val a = ((1f - k) * 120 * intro).toInt()
        line.color = Color.argb(a, 120, 180, 255); line.strokeWidth = 1.4f * d
        var prevOk = false; var lx = 0f; var ly = 0f
        for (i in 0..56) {
            val ang = i / 56f * 6.283f
            val ok = project(sin(ang) * rr, gy, cos(ang) * rr)
            if (ok && prevOk) c.drawLine(lx, ly, px, py, line)
            prevOk = ok; lx = px; ly = py
        }
    }

    private fun gridLine(x0: Float, y: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
        // Short pieces so the far end can fade and pieces behind the camera are skipped
        val n = 6
        var prevOk = false; var lx = 0f; var ly = 0f; var lz = 0f
        for (i in 0..n) {
            val u = i.toFloat() / n
            val ok = project(x0 + (x1 - x0) * u, y + (y1 - y) * u, z0 + (z1 - z0) * u)
            if (ok && prevOk) {
                val b = (fogK((pz + lz) / 2) * 3.99f).toInt().coerceIn(0, 3)
                var buf = gridBuf[b]
                if (buf.size < gridN[b] + 4) { buf = buf.copyOf(buf.size * 2); gridBuf[b] = buf }
                buf[gridN[b]++] = lx; buf[gridN[b]++] = ly; buf[gridN[b]++] = px; buf[gridN[b]++] = py
            }
            prevOk = ok; lx = px; ly = py; lz = pz
        }
    }

    /** One thin tilted ring around the whole scene, with a light travelling along it. */
    private fun drawOrbit(c: Canvas, intro: Float) {
        val r = 9.5f
        line.strokeWidth = 1f * d
        var prevOk = false; var lx = 0f; var ly = 0f
        val tilt = 0.22f
        for (i in 0..96) {
            val ang = i / 96f * 6.283f
            val x = sin(ang) * r; val z = cos(ang) * r
            val ok = project(x, 1.2f + sin(ang + 1f) * r * tilt, z)
            if (ok && prevOk) { line.color = Color.argb((34 * fogK(pz) * intro).toInt(), 160, 200, 255); c.drawLine(lx, ly, px, py, line) }
            prevOk = ok; lx = px; ly = py
        }
        val ang = t * 0.35f
        if (project(sin(ang) * r, 1.2f + sin(ang + 1f) * r * tilt, cos(ang) * r)) {
            val rad = max(2f, 30 * d / pz)
            glow(c, px, py, rad * 3f, (230 * intro).toInt())
        }
    }

    private class Streak(var x: Float, val y: Float, val len: Float, val speed: Float, val alpha: Int)
    private val streaks = ArrayList<Streak>()

    /** Faint lines of light that zip across now and then. */
    private fun drawStreaks(c: Canvas, dt: Float, intro: Float) {
        if (rnd.nextFloat() < dt * 0.9f) {
            val dir = if (rnd.nextBoolean()) 1f else -1f
            streaks.add(Streak(if (dir > 0) -200 * d else width + 200 * d, height * (0.05f + rnd.nextFloat() * 0.85f),
                (60 + rnd.nextFloat() * 140) * d, dir * (500 + rnd.nextFloat() * 700) * d, 40 + rnd.nextInt(70)))
        }
        val it = streaks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.x += s.speed * dt
            if (s.x < -400 * d || s.x > width + 400 * d) { it.remove(); continue }
            val tail = s.x - kotlin.math.sign(s.speed) * s.len
            val span = s.x - tail
            if (kotlin.math.abs(span) < 0.5f) continue
            c.save(); c.translate(tail, 0f); c.scale(span, 1f)
            fill.color = Color.WHITE; fill.alpha = (s.alpha * intro).toInt().coerceIn(0, 255); fill.shader = streakShader
            c.drawRect(0f, s.y, 1f, s.y + 1f * d, fill); fill.shader = null
            c.restore()
        }
    }

    private fun drawMotes(c: Canvas, dt: Float, intro: Float) {
        for (mi in motes.indices) {
            val m = motes[mi]
            if (quality == 0 && mi % 2 == 1) continue
            m.y += m.speed * dt
            if (m.y > 7f) { m.y = -4f }
            if (!project(m.x, m.y, m.z)) continue
            val a = (fogK(pz) * 200 * intro * (0.5f + 0.5f * sin(t * 2f + m.x))).toInt().coerceIn(0, 255)
            fill.color = Color.argb(a, 180, 215, 255)
            c.drawCircle(px, py, max(1f, 2.4f * d * 6f / pz), fill)
        }
    }

    /** Room left at the bottom for NEXUS's Back / Home / Recent buttons (0 when they're off). */
    var navInset = 0f
        set(v) { field = v; invalidate() }

    // Scrolling list state
    private var scroll = 0f
    private var scrollVel = 0f
    /** Gliding back to the top (Home pressed while already on NEXUS). */
    private var homing = false
    /** Swiping down from the top of the list: opens the notifications (set by NEXUS). */
    var onPullDown: () -> Unit = {}
    private var pullArmed = false
    private var maxScroll = 0f
    private var listTop = 0f
    private var listBottom = 0f
    /**
     * Home screen style: the PS2-style menu (one app per line, big soft glowing words, the one in the middle
     * shining brightest) or tiles on a grid. NEXUS settings → Home screen style.
     */
    var ps2Menu = true
    /** PS2 menu: room above the first line and below the last, so each can sit in the middle. */
    private var listPad = 0f
    /** PS2 menu: the distance from one line to the next. */
    private var listStep = 0f
    /** PS2 menu: where the list is gliding to (an app that was tapped comes to the middle), or NaN. */
    private var snapTo = Float.NaN
    /** PS2 menu: the line that was chosen and is opening (its confirm flash plays), or -1. */
    private var launchIdx = -1
    /** PS2 menu: the line in the middle last frame (a tick plays each time another one comes to the middle). */
    private var lastCentred = -1
    private var lastTickAt = 0L

    /** The style was switched: back to the top, laid out afresh. */
    fun styleChanged() { scroll = 0f; scrollVel = 0f; selected = -1; packSig = -1; homing = false; invalidate() }
    private val dateFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private fun drawMenu(c: Canvas, intro: Float, dt: Float) {
        val w = width.toFloat(); val h = height.toFloat()
        val a = ((intro - 0.4f) / 0.6f).coerceIn(0f, 1f) * (1f - 0.8f * crystalK)
        if (a <= 0f) return
        // (No title: the apps start right under the status row)
        // Status corners: date left, clock right, thin brackets
        text.letterSpacing = 0.12f; text.textSize = 11.5f * d
        text.color = Color.argb((170 * a).toInt(), 190, 210, 245)
        updateClock()
        text.textAlign = Paint.Align.LEFT; c.drawText(dateText, 20 * d, 32 * d, text)
        val dateEnd = 20 * d + text.measureText(dateText)
        text.textAlign = Paint.Align.RIGHT; c.drawText(clockText, w - 20 * d, 32 * d, text)
        // NEXUS's own status bar: signal and battery beside the clock, notification icons beside the date
        val clockStart = w - 20 * d - text.measureText(clockText)
        drawStatus(c, dateEnd, clockStart, 32 * d, a)
        // Settings gear under the clock
        gearRect.set(w - 50 * d, 40 * d, w - 14 * d, 76 * d)
        drawGear(c, gearRect.centerX(), gearRect.centerY(), 8 * d, Color.argb((150 * a).toInt(), 180, 205, 245))
        line.strokeWidth = 1f * d; line.color = Color.argb((70 * a).toInt(), 140, 190, 255)


        // App tiles on a home-screen grid, or the PS2-style menu (one app per line) - both scroll
        rows.clear()
        listTop = max(h * 0.12f, 88 * d); listBottom = h - 76 * d - navInset
        val list = ps2Menu
        val gap = if (list) 4 * d else 10 * d
        val left = 18 * d; val right = w - 18 * d
        val cols = if (list) 1 else gridCols.coerceIn(2, 6)
        layoutCols = cols
        cellW = (right - left - gap * (cols - 1)) / cols
        cellH = if (list) 56 * d else ((listBottom - listTop - gap * (gridRows - 1)) / gridRows).coerceIn(cellW * 0.7f, cellW * 1.3f)
        val stepX = cellW + gap; val stepY = cellH + gap
        listPad = if (list) max(0f, (listBottom - listTop) / 2 - cellH / 2) else 0f
        if (list) listStep = stepY
        // Pack tiles in order (first free spot that fits, like a phone home screen). (Adding apps is ADD APP at the bottom.)
        var target = -1
        val order: ArrayList<Int>
        val pos: Array<IntArray>
        if (moveIndex in games.indices) {
            order = ArrayList(games.size)
            // Where would the dragged tile land? Look at the layout without it and find the tile under the finger
            val rest = games.indices.filter { it != moveIndex }
            val restPos = pack(rest, cols)
            val fx = lastX; val fy = moveY + scroll - listTop - listPad
            var hit = rest.size
            for ((k, idx) in rest.withIndex()) {
                val p = restPos[k]
                val x0 = left + p[0] * stepX; val y0 = p[1] * stepY
                if (fx >= x0 && fx <= x0 + p[2] * stepX && fy >= y0 && fy <= y0 + p[3] * stepY) { hit = k; break }
                if (idx >= 0 && fy < y0) { hit = k; break }
            }
            target = hit.coerceIn(0, rest.size)
            if (target != lastTarget) { if (lastTarget >= 0) sfx.tick(); lastTarget = target }
            order.addAll(rest); order.add(target, moveIndex)
            pos = pack(order, cols)
        } else {
            // Not moving anything: the layout only changes when the apps, their sizes or the grid change
            var sig = cols * 31 + games.size
            for (g in games) sig = (sig * 31 + g.tileW) * 31 + rowsFor(g, cols)
            if (games !== packGames || sig != packSig || packOrder.size != games.size) {
                packGames = games; packSig = sig
                packOrder = ArrayList<Int>(games.size).apply { addAll(games.indices) }
                packPos = pack(packOrder, cols)
            }
            order = packOrder; pos = packPos
        }
        var usedRows = 0
        for (p in pos) usedRows = max(usedRows, p[1] + p[3])
        val total = usedRows * stepY - gap + 2 * listPad
        maxScroll = max(0f, total - (listBottom - listTop))
        // PS2 menu: gliding to the app that was tapped (it comes to the middle)
        if (!snapTo.isNaN() && !dragging) {
            scrollVel = 0f; homing = false
            scroll += (snapTo - scroll) * min(1f, dt * 13f)
            if (kotlin.math.abs(snapTo - scroll) < 0.5f) { scroll = snapTo; snapTo = Float.NaN }
        }
        // Fling with friction, springing back at the ends
        if (homing && !dragging) {
            scrollVel = 0f
            scroll += (0f - scroll) * min(1f, dt * 9f)
            if (kotlin.math.abs(scroll) < 0.5f) { scroll = 0f; homing = false }
        }
        if (!dragging && snapTo.isNaN()) {
            val bound = scroll.coerceIn(0f, maxScroll)
            if (bound != scroll) {
                if (scrollVel * (scroll - bound) > 0f) {
                    // Gliding past an end: it eases a little way past, then springs back (no hard stop)
                    scrollVel *= kotlin.math.exp(-dt * 26f)
                    if (kotlin.math.abs(scrollVel) < 60f * d) scrollVel = 0f
                    scroll += scrollVel * dt
                } else {
                    scrollVel = 0f
                    scroll += (bound - scroll) * min(1f, dt * 11f)
                    if (kotlin.math.abs(bound - scroll) < 0.5f) scroll = bound
                }
            } else {
                scroll += scrollVel * dt
                // Lighter friction than before, so a flick glides on naturally
                scrollVel *= kotlin.math.exp(-dt * 2.3f)
                if (kotlin.math.abs(scrollVel) < 5f) scrollVel = 0f
                // PS2 menu: once it's nearly stopped, it settles with an app in the middle
                if (list && moveIndex < 0 && kotlin.math.abs(scrollVel) < 240f * d) {
                    scrollVel = 0f
                    val target = (kotlin.math.round(scroll / stepY) * stepY).coerceIn(0f, maxScroll)
                    scroll += (target - scroll) * min(1f, dt * 12f)
                    if (kotlin.math.abs(target - scroll) < 0.5f) scroll = target
                }
            }
        }
        // PS2 menu: a soft tick (and the lightest buzz) each time another app comes to the middle, like turning
        // a wheel - at most about 20 a second on a fast flick
        if (list && listStep > 0f && moveIndex < 0) {
            val centred = kotlin.math.round(scroll / listStep).toInt().coerceIn(0, max(0, games.size - 1))
            if (centred != lastCentred) {
                val now = android.os.SystemClock.uptimeMillis()
                if (lastCentred >= 0 && a > 0.5f && now - lastTickAt > 48) {
                    lastTickAt = now
                    sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                lastCentred = centred
            }
        }
        if (list) {
            val top = listTop - 40 * d; val bot = listBottom + 30 * d
            c.save(); c.translate(0f, top); c.scale(1f, bot - top)
            fill.color = Color.WHITE; fill.alpha = (255 * a).toInt().coerceIn(0, 255); fill.shader = menuScrim
            c.drawRect(0f, 0f, w, 1f, fill); fill.shader = null
            c.restore()
        }
        val startY = listTop + listPad - scroll
        if (anim.size != games.size) anim = Array(games.size) { RectF(Float.NaN, 0f, 0f, 0f) }
        val k = min(1f, dt * 14f)
        while (hitPool.size < games.size) hitPool.add(RectF())
        for (hi in games.indices) hitPool[hi].setEmpty()
        c.save()
        c.clipRect(0f, listTop - 8 * d, w, listBottom + 8 * d)
        if (settle > 0f) {
            settle = (settle - dt / 0.42f).coerceAtLeast(0f)
            val k = settle * settle
            c.scale(1f + 0.06f * k, 1f + 0.06f * k, w / 2, (listTop + listBottom) / 2)
        }
        for (oi in order.indices) {
            val idx = order[oi]
            val p = pos[oi]
            val x0 = left + p[0] * stepX; val y0 = startY + p[1] * stepY
            val tr = tileR
            tr.set(x0, y0, x0 + p[2] * stepX - gap, y0 + p[3] * stepY - gap)
            hitPool[idx].set(tr)
            val ar = anim[idx]
            // Tiles glide to their new spots (while moving apps, and just after dropping)
            if (ar.left.isNaN() || (moveIndex < 0 && !settling)) ar.set(tr)
            else ar.set(ar.left + (tr.left - ar.left) * k, ar.top + (tr.top - ar.top) * k, ar.right + (tr.right - ar.right) * k, ar.bottom + (tr.bottom - ar.bottom) * k)
            if (idx != moveIndex && ar.bottom > listTop - 10 * d && ar.top < listBottom + 10 * d) {
                if (list) drawListItem(c, games[idx], ar, idx == downRow && !dragging && moveIndex < 0 && !longFired, a,
                    if (idx == launchIdx && launchFlash > 0f) 1f - launchFlash / LAUNCH_LEN else -1f, idx == selected && moveIndex < 0)
                else drawTile(c, games[idx], ar, idx == selected && moveIndex < 0, a)
            }
        }
        for (hi in games.indices) rows.add(hitPool[hi])
        if (settling) { settleT -= dt; if (settleT <= 0f) settling = false }
        c.restore()
        // No apps on the home screen at all: say how to add some
        if (games.isEmpty()) {
            text.textAlign = Paint.Align.CENTER; text.letterSpacing = 0.08f
            text.textSize = 15 * d; text.color = Color.argb((210 * a).toInt(), 200, 220, 250)
            val my = (listTop + listBottom) / 2
            c.drawText("No apps here yet", w / 2, my - 6 * d, text)
            text.textSize = 12 * d; text.color = Color.argb((160 * a).toInt(), 170, 195, 235)
            c.drawText("Tap ADD APP below to add some", w / 2, my + 18 * d, text)
        }
        // The tile being moved floats above everything, a little bigger and glowing
        if (moveIndex in games.indices) {
            if (moveY < listTop + 50 * d) scroll = max(0f, scroll - dt * 230f * d)
            if (moveY > listBottom - 50 * d) scroll = min(maxScroll, scroll + dt * 230f * d)
            val g = games[moveIndex]
            val tw = min(g.tileW, cols) * stepX - gap; val th = rowsFor(g, cols) * stepY - gap
            val r = RectF(lastX - grabFx * tw, moveY - grabFy * th, lastX - grabFx * tw + tw, moveY - grabFy * th + th)
            c.save(); c.scale(1.05f, 1.05f, r.centerX(), r.centerY())
            if (list) drawListItem(c, g, r, false, a, -1f, false)
            else {
                glow(c, r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.9f, 150)
                fill.color = Color.argb(210, 6, 12, 30); c.drawRoundRect(r, 8 * d, 8 * d, fill)
                drawTile(c, g, r, true, a)
            }
            c.restore()
        }
        // Soft fades where the list scrolls under the edges (the PS2 menu fades its lines by itself)
        if (maxScroll > 0f && !list) {
            fill.color = Color.WHITE
            c.save(); c.translate(0f, listTop - 6 * d); c.scale(1f, 30 * d)
            fill.shader = topFade; c.drawRect(0f, 0f, w, 1f, fill)
            c.restore()
            c.save(); c.translate(0f, listBottom - 24 * d); c.scale(1f, 30 * d)
            fill.shader = bottomFade; c.drawRect(0f, 0f, w, 1f, fill)
            c.restore(); fill.shader = null
            // Thin scroll indicator
            val trackH = listBottom - listTop
            val thumbH = max(30 * d, trackH * trackH / (trackH + maxScroll))
            val ty = listTop + (trackH - thumbH) * (scroll / maxScroll).coerceIn(0f, 1f)
            fill.color = Color.argb((120 * a).toInt(), 140, 190, 255)
            glyphR.set(w - 9 * d, ty, w - 7 * d, ty + thumbH)
            c.drawRoundRect(glyphR, d, d, fill)
        }
        text.textAlign = Paint.Align.CENTER
        text.textSize = 10.5f * d; text.letterSpacing = 0.12f
        text.color = Color.argb((110 * a).toInt(), 160, 185, 230)
        // Bottom bar: ADD APP · ALL APPS (Recent Apps is the square button)
        val gapB = 12 * d
        val pw = min(142 * d, (w - 48 * d - gapB) / 2f)
        val bx0 = w / 2 - (pw * 2 + gapB) / 2
        val barTop = h - 54 * d - navInset; val barBottom = h - 18 * d - navInset
        recentRect.setEmpty()
        addRect.set(bx0, barTop, bx0 + pw, barBottom)
        allAppsRect.set(bx0 + pw + gapB, barTop, bx0 + pw * 2 + gapB, barBottom)
        systemRect.setEmpty()
        bottomPill(c, addRect, "ADD APP", 3, a)
        bottomPill(c, allAppsRect, "ALL APPS", 2, a)
        // Recent Apps asked for with no apps open stays here: say so briefly, just above the bottom bar
        val since = android.os.SystemClock.uptimeMillis() - nothingOpenAt
        if (since in 0 until NOTE_MS) {
            val f = since / NOTE_MS.toFloat()
            val nk = min(1f, f * 8f) * min(1f, (1f - f) * 4f)
            text.textAlign = Paint.Align.CENTER; text.textSize = 12 * d; text.letterSpacing = 0.1f
            text.color = Color.argb((230 * nk * a).toInt(), 220, 235, 255)
            c.drawText(NOTE, w / 2, barTop - 10 * d - (1f - nk) * 4 * d, text)
        }
        text.textAlign = Paint.Align.CENTER
    }

    companion object {
        private const val NOTE = "No open apps"
        private const val NOTE_MS = 2200L
        @Volatile private var nothingOpenAt = -NOTE_MS
        /** Recent Apps was asked for with no apps open: the home screen says so for a moment. */
        fun noteNothingOpen() { nothingOpenAt = android.os.SystemClock.uptimeMillis() }
    }

    private val addRect = RectF()

    private val gearRect = RectF()

    private fun drawGear(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        line.color = color; line.strokeWidth = 1.6f * d
        c.drawCircle(cx, cy, r * 0.62f, line)
        c.drawCircle(cx, cy, r * 0.25f, line)
        for (k in 0 until 8) {
            val a = k / 8f * 6.283f
            c.drawLine(cx + cos(a) * r * 0.62f, cy + sin(a) * r * 0.62f, cx + cos(a) * r, cy + sin(a) * r, line)
        }
    }

    private val allAppsRect = RectF()
    private val systemRect = RectF()

    /** A glass pill with a small glyph and a label (glyph 0 = stacked cards, 1 = gauge, 2 = grid, 3 = plus). */
    private fun bottomPill(c: Canvas, r: RectF, label: String, glyph: Int, a: Float) {
        if (ps2Menu) {
            // PS2 style: soft glowing words, no box (each glow only as wide as its button, so they don't run together)
            val sx = min(2.4f, r.width() / (r.height() * 0.9f) * 0.55f)
            c.save(); c.scale(sx, 0.6f, r.centerX(), r.centerY())
            glow(c, r.centerX(), r.centerY(), r.height() * 0.9f, (55 * a).toInt())
            c.restore()
        } else {
            fill.color = Color.argb((60 * a).toInt(), 40, 80, 200)
            c.drawRoundRect(r, 18 * d, 18 * d, fill)
            line.strokeWidth = 1f * d; line.color = Color.argb((120 * a).toInt(), 140, 190, 255)
            c.drawRoundRect(r, 18 * d, 18 * d, line)
        }
        text.textAlign = Paint.Align.LEFT; text.textSize = 11 * d; text.letterSpacing = 0.18f
        val tw = text.measureText(label)
        val gw = 14 * d
        val x0 = r.centerX() - (gw + 8 * d + tw) / 2
        val cy = r.centerY()
        line.color = Color.argb((220 * a).toInt(), 200, 225, 255); fill.color = Color.argb((220 * a).toInt(), 200, 225, 255)
        when (glyph) {
            0 -> { // stacked cards
                glyphR.set(x0, cy - 6 * d, x0 + 10 * d, cy + 7 * d); c.drawRoundRect(glyphR, 2 * d, 2 * d, line)
                c.drawLine(x0 + 3 * d, cy - 9 * d, x0 + 13 * d, cy - 9 * d, line); c.drawLine(x0 + 13 * d, cy - 9 * d, x0 + 13 * d, cy + 4 * d, line)
            }
            1 -> { // gauge
                glyphR.set(x0, cy - 7 * d, x0 + 14 * d, cy + 7 * d); c.drawArc(glyphR, 150f, 240f, false, line)
                c.drawLine(x0 + 7 * d, cy, x0 + 11 * d, cy - 4 * d, line)
            }
            3 -> { // plus
                line.strokeWidth = 1.5f * d
                c.drawLine(x0 + 7 * d, cy - 7 * d, x0 + 7 * d, cy + 7 * d, line)
                c.drawLine(x0, cy, x0 + 14 * d, cy, line)
            }
            else -> for (gx in 0..2) for (gy in 0..2) c.drawRect(x0 + gx * 5 * d, cy - 7 * d + gy * 5 * d, x0 + 3 * d + gx * 5 * d, cy - 4 * d + gy * 5 * d, fill)
        }
        text.color = Color.argb((230 * a).toInt(), 220, 235, 255)
        c.drawText(label, x0 + gw + 8 * d, cy + 4 * d, text)
        text.textAlign = Paint.Align.CENTER
    }
    private val recentRect = RectF()
    private var swipedUp = false

    // Moving apps (hold, then drag)
    private var moveIndex = -1
    private var moveY = 0f
    /** Where the dragged tile is drawn right now (so dropping it glides smoothly into place). */
    private fun floatingRect(): RectF {
        val g = games.getOrNull(moveIndexForDrop) ?: return RectF()
        val stepX = cellW + 10 * d; val stepY = cellH + 10 * d
        val tw = min(g.tileW, layoutCols) * stepX - 10 * d; val th = rowsFor(g, layoutCols) * stepY - 10 * d
        return RectF(lastX - grabFx * tw, moveY - grabFy * th, lastX - grabFx * tw + tw, moveY - grabFy * th + th)
    }
    private var moveIndexForDrop = -1
    private var movedWhileHeld = false
    private var settling = false
    private var settleT = 0f
    private var lastTarget = -1

    /** Thin L-shaped corner mark. */
    private fun bracket(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
        val l = 14 * d
        c.drawLine(x, y, x + sx * l, y, line); c.drawLine(x, y, x, y + sy * l, line)
    }

    // ------------------------------------------------------------------ tiles

    /** Grid shape (columns × rows that fit on one screen), set from NEXUS settings. */
    var gridCols = 4
    var gridRows = 6
    private var cellW = 1f
    private var cellH = 1f
    private var anim = arrayOf<RectF>()
    private var lastX = 0f
    private var grabFx = 0.5f
    private var grabFy = 0.5f

    /** Pictures of each app's last screen, shown behind large tiles. */
    private val liveShots = HashMap<String, android.graphics.Bitmap>()

    /** When each tile's picture file was last read (so unchanged pictures aren't read again). */
    private val liveStamp = HashMap<String, Long>()

    // ------------------------------------------------------------------ status bar
    // Signal (Wi-Fi or mobile), battery and notification icons, read every few seconds (not every frame)
    private var statusAt = 0L
    private var batLevel = -1
    private var batCharging = false
    /** 0 no connection, 1 Wi-Fi, 2 mobile data. */
    private var netType = 0
    private var netLevel = 0
    private var netOk = true
    private val conn by lazy { context.getSystemService(android.net.ConnectivityManager::class.java) }
    private val tel by lazy { context.getSystemService(android.telephony.TelephonyManager::class.java) }
    private val statusIcon = RectF()
    private val boltPath = android.graphics.Path()

    /** The battery's level, and whether it's plugged in / charging (counts even if the phone pauses charging at 80%). */
    private fun readBattery(i: android.content.Intent) {
        val lvl = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        batLevel = if (lvl >= 0 && sc > 0) lvl * 100 / sc else -1
        val st = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        batCharging = plugged || st == android.os.BatteryManager.BATTERY_STATUS_CHARGING || st == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    /** Plugging in or unplugging shows at once (not on the next check a few seconds later). */
    private val batteryWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            try { if (i.action == android.content.Intent.ACTION_BATTERY_CHANGED) readBattery(i) else statusAt = 0L } catch (_: Exception) {}
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        try {
            val f = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED).apply {
                addAction(android.content.Intent.ACTION_POWER_CONNECTED); addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            }
            context.registerReceiver(batteryWatch, f)?.let { readBattery(it) }
        } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        try { context.unregisterReceiver(batteryWatch) } catch (_: Exception) {}
        super.onDetachedFromWindow()
    }

    private fun readStatus() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - statusAt < 3000L && statusAt != 0L) return
        statusAt = now
        try {
            context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }
        } catch (_: Exception) {}
        try {
            val caps = conn?.let { cm -> cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } }
            when {
                caps == null -> { netType = 0; netLevel = 0 }
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    netType = 1
                    val rssi = if (android.os.Build.VERSION.SDK_INT >= 29) caps.signalStrength else Int.MIN_VALUE
                    netLevel = when {
                        rssi == Int.MIN_VALUE || rssi == android.net.NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> 3
                        rssi >= -60 -> 3; rssi >= -70 -> 2; rssi >= -80 -> 1; else -> 0
                    }
                }
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    netType = 2
                    netLevel = try { if (android.os.Build.VERSION.SDK_INT >= 28) tel?.signalStrength?.level ?: 4 else 4 } catch (_: Exception) { 4 }
                }
                else -> { netType = 1; netLevel = 3 }
            }
            netOk = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
        } catch (_: Exception) {}
    }

    /** Draws the status bar between the date ([left]) and the clock ([right]) on the line at [base]. */
    private fun drawStatus(c: Canvas, left: Float, right: Float, base: Float, a: Float) {
        readStatus()
        val col = Color.argb((170 * a).toInt(), 190, 210, 245)
        val dim = Color.argb((60 * a).toInt(), 190, 210, 245)
        var x = right - 12 * d
        // Battery: percentage, then the battery itself
        if (batLevel >= 0) {
            text.textAlign = Paint.Align.RIGHT
            val pct = "$batLevel%"
            c.drawText(pct, x, base, text)
            x -= text.measureText(pct) + 5 * d
            val bw = 19 * d; val bh = 9.5f * d; val top = base - bh - 0.5f * d
            line.strokeWidth = 1.1f * d; line.color = col
            statusIcon.set(x - bw, top, x - 2 * d, top + bh)
            c.drawRoundRect(statusIcon, 2 * d, 2 * d, line)
            c.drawRect(x - 2 * d + 0.5f * d, top + bh * 0.3f, x, top + bh * 0.7f, line)
            val low = batLevel <= 15 && !batCharging
            fill.color = when {
                batCharging -> Color.argb((220 * a).toInt(), 110, 230, 150)
                low -> Color.argb((220 * a).toInt(), 255, 120, 110)
                else -> col
            }
            val inner = (bw - 2 * d - 3 * d) * batLevel / 100f
            c.drawRect(x - bw + 1.5f * d, top + 1.5f * d, x - bw + 1.5f * d + inner, top + bh - 1.5f * d, fill)
            if (batCharging) {
                // Charging: a lightning bolt in the battery
                val bx = x - bw / 2 - 1 * d; val by = top + bh / 2
                boltPath.reset()
                boltPath.moveTo(bx + 1.2f * d, by - 5.4f * d); boltPath.lineTo(bx - 2.6f * d, by + 0.6f * d); boltPath.lineTo(bx - 0.2f * d, by + 0.6f * d)
                boltPath.lineTo(bx - 1.2f * d, by + 5.4f * d); boltPath.lineTo(bx + 2.6f * d, by - 0.6f * d); boltPath.lineTo(bx + 0.2f * d, by - 0.6f * d); boltPath.close()
                fill.color = Color.argb((255 * a).toInt(), 255, 255, 255)
                line.color = Color.argb((230 * a).toInt(), 4, 10, 26); line.strokeWidth = 1.2f * d
                c.drawPath(boltPath, line); c.drawPath(boltPath, fill)
            }
            x -= bw + 9 * d
        }
        // Signal: Wi-Fi fan, or mobile bars; dimmed with a slash when there's no connection
        val sh = 11 * d
        if (netType == 1) {
            val cx = x - 7.5f * d; val by = base - 0.5f * d
            for (k in 1..3) {
                line.color = if (k <= netLevel) col else dim; line.strokeWidth = 1.6f * d
                val r = k * 3.6f * d
                statusIcon.set(cx - r, by - r, cx + r, by + r)
                c.drawArc(statusIcon, 225f, 90f, false, line)
            }
            fill.color = col; c.drawCircle(cx, by - 0.6f * d, 1.3f * d, fill)
            x -= 15 * d + 8 * d
        } else {
            val bw = 2.6f * d; val gap = 1.5f * d
            for (k in 0 until 4) {
                val bh = sh * (k + 1) / 4f
                fill.color = if (netType == 2 && k < netLevel) col else dim
                val bx = x - (4 - k) * (bw + gap)
                c.drawRect(bx, base - bh, bx + bw, base, fill)
            }
            if (netType == 0) { line.color = col; line.strokeWidth = 1.3f * d; c.drawLine(x - 4 * (bw + gap), base - sh, x - gap, base, line) }
            x -= 4 * (bw + gap) + 8 * d
        }
        if (netType != 0 && !netOk) {
            // Connected, but not to the internet
            text.textAlign = Paint.Align.RIGHT; c.drawText("!", x + 4 * d, base, text)
            x -= 6 * d
        }
        // Notification icons after the date (apps with notifications, newest first)
        val apps = NexusNotifications.homeApps
        if (apps.isNotEmpty()) {
            val isz = 14 * d
            var ix = left + 10 * d
            var shown = 0
            for (pkg in apps) {
                if (ix + isz > x - 8 * d || shown >= 4) break
                val icon = AppCache.get(context, pkg)?.icon ?: continue
                icon.setBounds(ix.toInt(), (base - isz + 1 * d).toInt(), (ix + isz).toInt(), (base + 1 * d).toInt())
                icon.alpha = (230 * a).toInt(); icon.draw(c); icon.alpha = 255
                ix += isz + 5 * d; shown++
            }
            if (apps.size > shown && shown > 0 && ix + 10 * d < x) { text.textAlign = Paint.Align.LEFT; c.drawText("+", ix, base, text) }
        }
        text.textAlign = Paint.Align.LEFT
    }

    /** Lets go of the tile pictures to free memory (they're read again next time NEXUS comes back). */
    fun releasePictures() { liveShots.clear(); liveStamp.clear(); nameGlow.clear() }

    // The menu's soft glows and dark outlines, blurred once and reused (see TextGlow)
    private val nameGlow by lazy { TextGlow(text.typeface ?: Typeface.DEFAULT, 24 * d, 0.14f) }

    fun loadLiveShots() {
        val pkgs = games.filter { it.tileH >= 2 }.map { it.pkg }
        if (pkgs.isEmpty()) return
        val known = HashMap(liveStamp)
        Bg.thread {
            for (p in pkgs) {
                val f = NexusAccessibility.snapFile(context, p)
                if (!f.exists()) { post { liveShots.remove(p); liveStamp.remove(p) }; continue }
                val stamp = f.lastModified()
                if (known[p] == stamp) continue
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                val b = try { android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts) } catch (_: Exception) { null } ?: continue
                post { liveShots[p] = b; liveStamp[p] = stamp }
            }
        }.start()
    }

    /** Tile positions for an order of apps (-1 = the ADD tile, always 1×1). */
    private fun pack(order: List<Int>, cols: Int): Array<IntArray> =
        TileLayout.pack(order.map { idx -> if (idx < 0 || ps2Menu) intArrayOf(1, 1) else intArrayOf(games[idx].tileW, rowsFor(games[idx], cols)) }, cols)

    // ------------------------------------------------------------------ tile text: always shown in full

    private var layoutCols = 4
    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val measure = TileText.Measure { str, size, spacing ->
        measurePaint.textSize = size; measurePaint.letterSpacing = spacing; measurePaint.measureText(str)
    }

    /** The small line under a tile's name: only shown for a NEXUS game that isn't installed yet. */
    private fun subOf(g: GameEntry) = if (!g.installed) "Not installed" else ""

    /**
     * How many grid rows a tile takes: the size the user picked, plus extra rows if its full name
     * (and "Not installed", if shown) need more room than that, so nothing is ever cut off.
     */
    private fun rowsFor(g: GameEntry, cols: Int): Int {
        if (ps2Menu) return 1
        val gap = 10 * d
        val stepX = cellW + gap; val stepY = cellH + gap
        val w = min(g.tileW, cols) * stepX - gap
        val sub = subOf(g)
        if (g.rowsW == w && g.rowsStep == stepY && g.rowsSub == sub && g.rowsBase == g.tileH) return g.rows
        val baseH = g.tileH * stepY - gap
        var rows = g.tileH
        while (rows < g.tileH + 5 && !TileText.plan(g.name, sub, w, rows * stepY - gap, baseH, cellW, d, measure).fits) rows++
        g.rowsW = w; g.rowsStep = stepY; g.rowsSub = sub; g.rowsBase = g.tileH; g.rows = rows
        return rows
    }

    private fun planFor(g: GameEntry, w: Float, h: Float, baseH: Float): TileText.Plan {
        val sub = subOf(g)
        val p = g.plan
        if (p != null && g.planW == w && g.planH == h && g.planBase == baseH && g.planSub == sub) return p
        val np = TileText.plan(g.name, sub, w, h, baseH, cellW, d, measure)
        g.plan = np; g.planW = w; g.planH = h; g.planBase = baseH; g.planSub = sub
        return np
    }

    /** Draws lines of text from [top] down; returns where the block ends. */
    private fun drawLines(c: Canvas, lines: List<String>, x: Float, top: Float, size: Float, spacing: Float, color: Int, lineH: Float): Float {
        text.textSize = size; text.letterSpacing = spacing; text.color = color
        var y = top
        for (ln in lines) { c.drawText(ln, x, y + lineH * 0.8f, text); y += lineH }
        return y
    }

    private var selIndex = -2
    private var selSince = 0f

    /** One app tile; the layout adapts to its size (small, wide, tall, large). */
    private fun drawTile(c: Canvas, g: GameEntry, r: RectF, sel: Boolean, a0: Float) {
        if (selected != selIndex) { selIndex = selected; selSince = t }
        val a = if (sel) a0 * (0.55f + 0.45f * min(1f, (t - selSince) / 0.18f)) else a0
        val pulse = 0.5f + 0.5f * sin(t * 3f)
        val rad = 8 * d
        // Shape comes from the size the user picked; the tile may be taller to fit its full name
        val baseH = min(g.tileH * (cellH + 10 * d) - 10 * d, r.height())
        val plan = planFor(g, r.width(), r.height(), baseH)
        val wide = plan.kind == TileText.Kind.WIDE
        val tall = plan.kind == TileText.Kind.TALL
        val large = plan.kind == TileText.Kind.LARGE
        // Glass panel (large tiles show the app's last screen behind it)
        fill.color = Color.argb(((if (sel) 80 else 34) * a).toInt(), 40, 80, 200)
        c.drawRoundRect(r, rad, rad, fill)
        val shot = if (large || tall) liveShots[g.pkg] else null
        if (shot != null) {
            c.save(); clipPath.reset(); clipPath.addRoundRect(r, rad, rad, Path.Direction.CW); c.clipPath(clipPath)
            val sc = max(r.width() / shot.width, r.height() / shot.height)
            val dw = shot.width * sc
            shotDst.set(r.centerX() - dw / 2, r.top, r.centerX() + dw / 2, r.top + shot.height * sc)
            shotPaint.alpha = (150 * a).toInt()
            c.drawBitmap(shot, null, shotDst, shotPaint)
            c.save(); c.translate(0f, r.top); c.scale(1f, max(1f, r.height()))
            fill.color = Color.WHITE; fill.alpha = (255 * a).toInt(); fill.shader = shotShade
            c.drawRect(r.left, 0f, r.right, 1f, fill); fill.shader = null
            c.restore()
            c.restore()
        }
        line.strokeWidth = 1f * d
        line.color = Color.argb(((if (sel) 150 + 90 * pulse else 60f) * a).toInt(), 140, 190, 255)
        c.drawRoundRect(r, rad, rad, line)
        if (sel) {
            // White sheen sweeping across the highlighted tile
            val k = (t * 0.7f) % 1.6f
            val sx = r.left + (r.width() + 120 * d) * k - 60 * d
            c.save(); c.clipRect(r)
            c.translate(sx, 0f); c.scale(50 * d, 1f)
            fill.color = Color.WHITE; fill.alpha = (70 * a).toInt(); fill.shader = hLine3
            c.drawRect(-1f, r.top, 1f, r.bottom, fill); fill.shader = null
            c.restore()
            line.strokeWidth = 2f * d; line.color = Color.argb((230 * a).toInt(), 200, 225, 255)
            val l = 10 * d
            c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d + l, r.top - 4 * d, line); c.drawLine(r.left - 4 * d, r.top - 4 * d, r.left - 4 * d, r.top - 4 * d + l, line)
            c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d - l, r.bottom + 4 * d, line); c.drawLine(r.right + 4 * d, r.bottom + 4 * d, r.right + 4 * d, r.bottom + 4 * d - l, line)
        }
        val accent = Color.argb(((if (sel) 255 else 140) * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        val subCol = if (g.installed) Color.argb((175 * a).toInt(), 170, 200, 240) else Color.argb((200 * a).toInt(), 255, 190, 110)
        val nameCol = Color.argb((255 * a).toInt(), 240, 245, 255)
        text.textAlign = Paint.Align.LEFT
        c.save(); c.clipRect(r)
        // The Countdown tile shows your countdown ticking instead of its name
        if (g.pkg == CountdownLink.PKG && g.installed && countdown != null) {
            drawCountdown(c, g, r, plan, a, accent)
            c.restore()
            return
        }
        when {
            wide -> {
                // Wide: icon on the left, the full name beside it
                fill.color = accent; c.drawRect(r.left, r.top + r.height() * 0.2f, r.left + 2.5f * d, r.bottom - r.height() * 0.2f, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 14 * d, r.centerY() - isz / 2, r.left + 14 * d + isz, r.centerY() + isz / 2)
                drawIcon(c, g, tmpRect, a)
                val tx = tmpRect.right + 14 * d
                var y = r.centerY() - TileText.blockHeight(plan, d) / 2
                y = drawLines(c, plan.name, tx, y, plan.nameSize, TileText.NAME_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                if (plan.sub.isNotEmpty()) drawLines(c, plan.sub, tx, y + 5 * d, plan.subSize, TileText.SUB_SPACING, subCol, TileText.subLineH(plan.subSize))
            }
            large || tall -> {
                // Large / tall: icon top-left, the full name along the bottom
                fill.color = accent; c.drawRect(r.left + 12 * d, r.top, r.left + 12 * d + r.width() * 0.25f, r.top + 2.5f * d, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 12 * d, r.top + 14 * d, r.left + 12 * d + isz, r.top + 14 * d + isz)
                drawIcon(c, g, tmpRect, a)
                var y = r.bottom - 14 * d - TileText.blockHeight(plan, d)
                y = drawLines(c, plan.name, r.left + 12 * d, y, plan.nameSize, TileText.NAME_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                if (plan.sub.isNotEmpty()) drawLines(c, plan.sub, r.left + 12 * d, y + 5 * d, plan.subSize, TileText.SUB_SPACING, subCol, TileText.subLineH(plan.subSize))
            }
            else -> {
                // Small: icon in the middle, the full name underneath (over more lines if it's long)
                val isz = plan.icon
                val block = TileText.blockHeight(plan, d)
                val iconTop = if (r.height() > baseH + 1f) r.top + (r.height() - (isz + 4 * d + block)) / 2 else r.top + plan.iconTop
                tmpRect.set(r.centerX() - isz / 2, iconTop, r.centerX() + isz / 2, iconTop + isz)
                drawIcon(c, g, tmpRect, a)
                text.textAlign = Paint.Align.CENTER
                val regionTop = tmpRect.bottom + 4 * d
                val regionH = r.bottom - 6 * d - regionTop
                val y = if (r.height() > baseH + 1f) regionTop else regionTop + max(0f, (regionH - block) / 2)
                drawLines(c, plan.name, r.centerX(), y, plan.nameSize, TileText.SMALL_SPACING, nameCol, TileText.nameLineH(plan.nameSize))
                text.textAlign = Paint.Align.LEFT
            }
        }
        c.restore()
    }

    /** Your Countdown app's countdown (set by NEXUS), shown ticking on the Countdown tile. */
    var countdown: CountdownLink.Info? = null

    // The time left, worked out once a second (the same HH:MM:SS as the Countdown app: hours past 24, no days)
    private var cdSec = Long.MIN_VALUE
    private var cdText = ""
    private fun countdownText(left: Long): String {
        val sec = if (left <= 0) -1L else left / 1000
        if (sec != cdSec) {
            cdSec = sec
            cdText = if (sec < 0) "TIME'S UP" else String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        }
        return cdText
    }
    /** Digits all the same width, so the time doesn't wobble as it ticks. */
    private val cdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); fontFeatureSettings = "tnum"
    }
    // The countdown's name, shortened to fit (worked out only when it changes)
    private var cdTitleSrc = ""; private var cdTitleW = -1f; private var cdTitleSize = -1f; private var cdTitle = ""
    private fun fitTitle(name: String, maxW: Float, size: Float): String {
        if (name == cdTitleSrc && maxW == cdTitleW && size == cdTitleSize) return cdTitle
        cdTitleSrc = name; cdTitleW = maxW; cdTitleSize = size
        cdPaint.textSize = size; cdPaint.letterSpacing = 0.06f
        var s = name
        if (cdPaint.measureText(s) > maxW) {
            while (s.isNotEmpty() && cdPaint.measureText("$s…") > maxW) s = s.dropLast(1)
            s = s.trimEnd() + "…"
        }
        cdTitle = s
        return s
    }
    /** The biggest text size (up to [max]) at which [s] fits in [w]. */
    private fun fitSize(s: String, w: Float, max: Float, spacing: Float): Float {
        cdPaint.textSize = 20 * d; cdPaint.letterSpacing = spacing
        val at20 = cdPaint.measureText(s)
        return if (at20 <= 0f) max else min(max, 20 * d * w / at20)
    }

    /** The Countdown tile: the countdown's name and the time left, ticking (the shape follows the tile size). */
    private fun drawCountdown(c: Canvas, g: GameEntry, r: RectF, plan: TileText.Plan, a: Float, accent: Int) {
        val cd = countdown ?: return
        val left = cd.target - System.currentTimeMillis()
        val time = countdownText(left)
        val done = left <= 0
        val titleCol = Color.argb((190 * a).toInt(), 170, 200, 240)
        val timeCol = if (done) Color.argb((255 * a).toInt(), 255, 200, 120) else Color.argb((255 * a).toInt(), 240, 245, 255)
        val glow = Color.argb((110 * a).toInt(), 90, 150, 255)
        cdPaint.textAlign = Paint.Align.LEFT
        when (plan.kind) {
            TileText.Kind.WIDE -> {
                fill.color = accent; c.drawRect(r.left, r.top + r.height() * 0.2f, r.left + 2.5f * d, r.bottom - r.height() * 0.2f, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 14 * d, r.centerY() - isz / 2, r.left + 14 * d + isz, r.centerY() + isz / 2)
                drawIcon(c, g, tmpRect, a)
                val tx = tmpRect.right + 14 * d
                val avail = r.right - 12 * d - tx
                val ts = min(12.5f * d, r.height() * 0.16f)
                val tz = fitSize(time, avail, min(r.height() * 0.4f, 34 * d), 0.04f)
                val block = ts * 1.25f + 4 * d + tz * 0.8f
                val top = r.centerY() - block / 2
                cdPaint.setShadowLayer(0f, 0f, 0f, 0)
                val title = fitTitle(cd.name, avail, ts)
                cdPaint.textSize = ts; cdPaint.letterSpacing = 0.06f; cdPaint.color = titleCol
                c.drawText(title, tx, top + ts, cdPaint)
                cdPaint.textSize = tz; cdPaint.letterSpacing = 0.04f; cdPaint.color = timeCol
                cdPaint.setShadowLayer(6 * d, 0f, 0f, glow)
                c.drawText(time, tx, top + ts * 1.25f + 4 * d + tz * 0.8f, cdPaint)
            }
            TileText.Kind.LARGE, TileText.Kind.TALL -> {
                fill.color = accent; c.drawRect(r.left + 12 * d, r.top, r.left + 12 * d + r.width() * 0.25f, r.top + 2.5f * d, fill)
                val isz = plan.icon
                tmpRect.set(r.left + 12 * d, r.top + 14 * d, r.left + 12 * d + isz, r.top + 14 * d + isz)
                drawIcon(c, g, tmpRect, a)
                val avail = r.width() - 24 * d
                val ts = min(13 * d, r.width() * 0.09f)
                val tz = fitSize(time, avail, min(r.height() * 0.26f, 46 * d), 0.04f)
                val timeBase = r.bottom - 16 * d
                cdPaint.setShadowLayer(0f, 0f, 0f, 0)
                val title = fitTitle(cd.name, avail, ts)
                cdPaint.textSize = ts; cdPaint.letterSpacing = 0.06f; cdPaint.color = titleCol
                c.drawText(title, r.left + 12 * d, timeBase - tz * 0.8f - 8 * d, cdPaint)
                cdPaint.textSize = tz; cdPaint.letterSpacing = 0.04f; cdPaint.color = timeCol
                cdPaint.setShadowLayer(8 * d, 0f, 0f, glow)
                c.drawText(time, r.left + 12 * d, timeBase, cdPaint)
            }
            else -> {
                // Small: the icon, and the time underneath
                val isz = plan.icon * 0.85f
                val tz = fitSize(time, r.width() - 12 * d, 15 * d, 0.02f)
                val block = isz + 6 * d + tz * 0.8f
                val top = r.centerY() - block / 2
                tmpRect.set(r.centerX() - isz / 2, top, r.centerX() + isz / 2, top + isz)
                drawIcon(c, g, tmpRect, a)
                cdPaint.textAlign = Paint.Align.CENTER
                cdPaint.textSize = tz; cdPaint.letterSpacing = 0.02f; cdPaint.color = timeCol
                cdPaint.setShadowLayer(5 * d, 0f, 0f, glow)
                c.drawText(time, r.centerX(), top + isz + 6 * d + tz * 0.8f, cdPaint)
                cdPaint.textAlign = Paint.Align.LEFT
            }
        }
        cdPaint.setShadowLayer(0f, 0f, 0f, 0)
    }

    // ------------------------------------------------------------------ the PS2-style menu

    /** How close a line is to the middle (1 = right in the middle), and how faded it is towards the edges. */
    private fun listFocus(r: RectF): Float =
        (1f - kotlin.math.abs(r.centerY() - (listTop + listBottom) / 2) / (r.height() + 4 * d)).coerceIn(0f, 1f)
    private fun listFade(r: RectF): Float =
        (1f - kotlin.math.abs(r.centerY() - (listTop + listBottom) / 2) / ((listBottom - listTop) * 0.62f)).coerceIn(0.12f, 1f)

    /** The name as shown on its line (shortened with … only if it's too long even at its biggest). */
    private fun listName(g: GameEntry, maxW: Float): String {
        val cached = g.listName
        if (cached != null && g.listNameW == maxW) return cached
        measurePaint.textSize = 24 * d; measurePaint.letterSpacing = 0.14f
        var s = g.name
        if (measurePaint.measureText(s) > maxW) {
            while (s.isNotEmpty() && measurePaint.measureText("$s…") > maxW) s = s.dropLast(1)
            s = s.trimEnd() + "…"
        }
        g.listName = s; g.listNameW = maxW
        return s
    }

    /**
     * One line of the PS2-style menu: the app's name in big soft letters with its icon. The line in the middle
     * (or the one being pressed) shines: brighter, a little bigger, a glow behind it and thin rules of light.
     */
    private fun drawListItem(c: Canvas, g: GameEntry, r: RectF, pressed: Boolean, a0: Float, launching: Float, chosen: Boolean) {
        val k = if (launching >= 0f || chosen) 1f else listFocus(r)
        val a = a0 * listFade(r)
        // Selected (tapped once - tap again to open): a frame of light that breathes around the line
        if (chosen && launching < 0f) {
            val p = 0.5f + 0.5f * sin(t * 4.5f)
            glow(c, r.centerX(), r.centerY(), r.width() * 0.42f, ((70 + 60 * p) * a0).toInt())
            line.strokeWidth = 1.4f * d
            line.color = Color.argb(((120 + 100 * p) * a0).toInt(), 170, 210, 255)
            tmpRect.set(r.left + r.width() * 0.08f, r.top + 1 * d, r.right - r.width() * 0.08f, r.bottom - 1 * d)
            c.drawRoundRect(tmpRect, r.height() / 2, r.height() / 2, line)
        }
        // Finger down: the line presses in a little. Chosen: it flashes white, grows, and a ring of light bursts out.
        val scale = when { launching >= 0f -> 1f + 0.08f * launching; pressed -> 0.95f; else -> 1f }
        c.save()
        if (scale != 1f) c.scale(scale, scale, r.centerX(), r.centerY())
        if (launching >= 0f) {
            val p = launching
            glow(c, r.centerX(), r.centerY(), r.width() * (0.35f + 0.4f * p), (200 * (1f - p * 0.6f) * a0).toInt())
            line.strokeWidth = 2f * d * (1f - p * 0.5f)
            line.color = Color.argb((210 * (1f - p) * a0).toInt(), 220, 235, 255)
            tmpRect.set(r.centerX() - r.width() * (0.3f + 0.25f * p), r.centerY() - r.height() * (0.45f + 0.6f * p),
                r.centerX() + r.width() * (0.3f + 0.25f * p), r.centerY() + r.height() * (0.45f + 0.6f * p))
            c.drawOval(tmpRect, line)
        }
        if (k > 0.02f) {
            c.save(); c.scale(3.2f, 0.55f, r.centerX(), r.centerY())
            glow(c, r.centerX(), r.centerY(), r.height() * 1.1f, (110 * k * a).toInt())
            c.restore()
            val half = r.width() * 0.34f * k
            c.save(); c.translate(r.centerX(), 0f); c.scale(max(1f, half), 1f)
            fill.color = Color.WHITE; fill.alpha = (150 * k * a).toInt(); fill.shader = ruleShader
            c.drawRect(-1f, r.top + 3 * d, 1f, r.top + 4 * d, fill)
            c.drawRect(-1f, r.bottom - 4 * d, 1f, r.bottom - 3 * d, fill)
            fill.shader = null
            c.restore()
            // Now and then a spark of light runs along those lines (along the top one, and back along the bottom)
            if (k > 0.6f && launching < 0f) {
                val run = (t * 0.32f) % 1.7f
                if (run < 1f) {
                    val e = run * run * (3f - 2f * run)
                    val ka = sin(run * Math.PI.toFloat()) * k * a
                    glow(c, r.centerX() - half + 2 * half * e, r.top + 3.5f * d, 10 * d, (190 * ka).toInt())
                    glow(c, r.centerX() + half - 2 * half * e, r.bottom - 3.5f * d, 10 * d, (190 * ka).toInt())
                }
            }
        }
        val cd = countdown
        val sub = when {
            !g.installed -> "NOT INSTALLED"
            g.pkg == CountdownLink.PKG && cd != null -> countdownText(cd.target - System.currentTimeMillis())
            else -> ""
        }
        // (In half steps, so the letters are drawn at a handful of sizes rather than a new one every frame)
        val size = Math.round((19f + 5f * k) * 2f) / 2f * d
        val iconS = 24 * d * (0.86f + 0.14f * k)
        val name = listName(g, r.width() - 24 * d - 12 * d - 24 * d)
        text.textAlign = Paint.Align.LEFT; text.textSize = size; text.letterSpacing = 0.14f
        val tw = text.measureText(name)
        val x0 = r.centerX() - (iconS + 12 * d + tw) / 2
        val cy = r.centerY() - (if (sub.isNotEmpty()) 6 * d else 0f)
        tmpRect.set(x0, cy - iconS / 2, x0 + iconS, cy + iconS / 2)
        drawIcon(c, g, tmpRect, a * (0.35f + 0.65f * k))
        val dim = if (launching >= 0f || pressed) 0f else 1f - k
        text.color = if (launching >= 0f) Color.argb((255 * a).toInt(), 255, 255, 255)
                     else Color.argb((255 * a).toInt(), (242 - 55 * dim).toInt(), (247 - 45 * dim).toInt(), (255 - 20 * dim).toInt())
        val tx = tmpRect.right + 12 * d; val ty = cy + size * 0.35f
        // A dark outline first, so the name reads clearly in front of the towers and lights; then its glow
        nameGlow.draw(c, name, tx, ty + 1 * d, size, 5 * d, Color.argb((230 * a).toInt(), 0, 2, 8))
        if (k > 0.05f) {
            if (launching >= 0f) nameGlow.draw(c, name, tx, ty, size, 14 * d, Color.argb((255 * a).toInt(), 150, 200, 255), grow = 1.12f)
            else nameGlow.draw(c, name, tx, ty, size, 14 * d, Color.argb((220 * k * a).toInt(), 70, 140, 255))
        }
        c.drawText(name, tx, ty, text)
        if (sub.isNotEmpty()) {
            cdPaint.textAlign = Paint.Align.CENTER; cdPaint.textSize = 11 * d; cdPaint.letterSpacing = 0.2f
            cdPaint.color = Color.argb(((150 + 80 * k) * a).toInt(), 175, 205, 245)
            cdPaint.setShadowLayer(4 * d, 0f, 1 * d, Color.argb((220 * a).toInt(), 0, 2, 8))
            c.drawText(sub, r.centerX(), cy + size * 0.35f + 17 * d, cdPaint)
            cdPaint.setShadowLayer(0f, 0f, 0f, 0)
        }
        text.textAlign = Paint.Align.CENTER
        c.restore()
    }

    private val tmpRect = RectF()
    private val shotDst = RectF()
    private val shotPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clipPath = Path()

    private fun drawIcon(c: Canvas, g: GameEntry, r: RectF, a: Float) {
        fill.color = Color.argb((255 * a).toInt(), 8, 12, 24)
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        line.strokeWidth = 1f * d
        line.color = Color.argb((120 * a).toInt(), Color.red(g.accent), Color.green(g.accent), Color.blue(g.accent))
        c.drawRoundRect(r, 8 * d, 8 * d, line)
        val cxm = r.centerX(); val cym = r.centerY(); val u = r.width() / 10f
        val al = (255 * a).toInt()
        if (g.drawable != null) {
            val pad = 4 * d
            g.drawable.setBounds((r.left + pad).toInt(), (r.top + pad).toInt(), (r.right - pad).toInt(), (r.bottom - pad).toInt())
            g.drawable.alpha = al
            g.drawable.draw(c)
            return
        }
        when (g.icon) {
            0 -> { // Zombie Dots: a gold leader, blue squad, red horde
                fill.color = Color.argb(al, 255, 59, 59)
                for ((dx, dy) in listOf(-3.2f to -2.6f, 3f to -3f, -3.6f to 2.4f, 3.4f to 2.8f, 0.4f to 3.8f)) c.drawCircle(cxm + dx * u, cym + dy * u, 0.75f * u, fill)
                fill.color = Color.argb(al, 61, 165, 255)
                for ((dx, dy) in listOf(-1.4f to 0.2f, 1.4f to 0.2f, 0f to 1.5f)) c.drawCircle(cxm + dx * u, cym + dy * u, 0.75f * u, fill)
                fill.color = Color.argb(al, 255, 224, 102); c.drawCircle(cxm, cym - 0.4f * u, 0.95f * u, fill)
            }
            1 -> { // Ashen Vow: an upright sword with an ember glow
                c.save(); c.translate(cxm, cym - 2f * u); c.scale(4f * u, 4f * u)
                fill.color = Color.WHITE; fill.alpha = (255 * a).toInt(); fill.shader = emberGlow
                c.drawCircle(0f, 0f, 1f, fill); fill.shader = null
                c.restore()
                fill.color = Color.argb(al, 214, 220, 230)
                path.reset(); path.moveTo(cxm, cym - 4.2f * u); path.lineTo(cxm + 0.55f * u, cym - 3.3f * u); path.lineTo(cxm + 0.55f * u, cym + 1.6f * u)
                path.lineTo(cxm - 0.55f * u, cym + 1.6f * u); path.lineTo(cxm - 0.55f * u, cym - 3.3f * u); path.close(); c.drawPath(path, fill)
                fill.color = Color.argb(al, 184, 134, 46); c.drawRect(cxm - 2.2f * u, cym + 1.6f * u, cxm + 2.2f * u, cym + 2.3f * u, fill)
                fill.color = Color.argb(al, 90, 58, 30); c.drawRect(cxm - 0.4f * u, cym + 2.3f * u, cxm + 0.4f * u, cym + 4f * u, fill)
            }
            2 -> { // SynthPad: keys under a glowing wave
                line.strokeWidth = 0.6f * u
                line.color = Color.argb(al, 120, 220, 255)
                path.reset()
                for (k in 0..30) {
                    val xx = r.left + u + k * (r.width() - 2 * u) / 30f
                    val yy = cym - 1.8f * u + sin(k / 30f * 12.56f) * 1.6f * u
                    if (k == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                }
                c.drawPath(path, line)
                fill.color = Color.argb(al, 230, 235, 245)
                val kw = (r.width() - 2 * u) / 6f
                for (k in 0 until 6) c.drawRect(r.left + u + k * kw + 0.08f * u, cym + 0.8f * u, r.left + u + (k + 1) * kw - 0.08f * u, cym + 4f * u, fill)
                fill.color = Color.argb(al, 20, 20, 28)
                for (k in intArrayOf(1, 2, 4, 5)) c.drawRect(r.left + u + k * kw - 0.32f * kw, cym + 0.8f * u, r.left + u + k * kw + 0.32f * kw, cym + 2.6f * u, fill)
            }
            else -> { // Torque Street: a speedometer swept into the red, over the road
                line.strokeWidth = 0.55f * u; line.strokeCap = Paint.Cap.ROUND
                val rr = RectF(cxm - 3.4f * u, cym - 3.6f * u, cxm + 3.4f * u, cym + 3.2f * u)
                line.color = Color.argb(al, 70, 84, 110); c.drawArc(rr, 150f, 240f, false, line)
                line.color = Color.argb(al, 255, 106, 26); c.drawArc(rr, 300f, 90f, false, line)
                fill.color = Color.argb(al, 255, 255, 255)
                path.reset(); path.moveTo(cxm - 0.35f * u, cym - 0.1f * u); path.lineTo(cxm + 2.6f * u, cym - 2.2f * u); path.lineTo(cxm + 0.25f * u, cym + 0.35f * u); path.close()
                c.drawPath(path, fill); c.drawCircle(cxm, cym, 0.55f * u, fill)
                fill.color = Color.argb(al, 255, 201, 60)
                c.drawRect(cxm - 0.35f * u, cym + 1.9f * u, cxm + 0.35f * u, cym + 2.8f * u, fill)
                c.drawRect(cxm - 0.45f * u, cym + 3.3f * u, cxm + 0.45f * u, cym + 4.3f * u, fill)
            }
        }
    }

    // ------------------------------------------------------------------ touch
    // Tap = highlight; tap the highlighted one again = open. Swiping scrolls and never highlights:
    //  - any finger movement past a few dp, or a quick flick (even a short one), counts as a swipe
    //  - the finger's position when it lifts counts too (a fast flick can jump a long way in that last step)
    //  - touching the list while it's still gliding just stops it, it doesn't pick what's under the thumb

    private var downRow = -1
    /** This touch landed on a list that was still moving: it only stops the list. */
    private var caught = false
    /** Furthest the finger has been from where it touched down. */
    private var maxTravel = 0f
    private fun travel(x: Float, y: Float) { maxTravel = max(maxTravel, kotlin.math.hypot(x - downX, y - downY)) }
    private var longFired = false
    private var dragging = false
    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var tracker: android.view.VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    /** Hold: the app lifts up and can be dragged to a new spot (or, if you let go without moving, options). */
    private val longPress = Runnable {
        val r = downRow
        if (!dragging && r in games.indices) {
            longFired = true
            moveIndex = r
            moveY = lastY
            val rr = rows.getOrNull(r)
            if (rr != null && rr.width() > 0f) { grabFx = ((lastX - rr.left) / rr.width()).coerceIn(0f, 1f); grabFy = ((lastY - rr.top) / rr.height()).coerceIn(0f, 1f) }
            movedWhileHeld = false
            lastTarget = r
            selected = -1
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            sfx.tick()
        }
    }

    private fun rowAt(x: Float, y: Float): Int {
        if (y < listTop - 6 * d || y > listBottom + 6 * d) return -1
        return rows.indexOfFirst { it.contains(x, y) }
    }

    @SuppressLint("ClickableViewAccessibility")
    // ------------------------------------------------------------------ TalkBack
    // The home screen is drawn by NEXUS itself, so it describes its tiles and buttons to TalkBack (Android's
    // screen reader): each one can be found by touch or by swiping, is read out, opens with a double tap, and
    // a tile's options open with a double tap and hold. The list can be scrolled with TalkBack's scroll gestures.

    private val A11Y_ADD = 100_000
    private val A11Y_RECENT = 100_001
    private val A11Y_ALL = 100_002
    private val A11Y_GEAR = 100_003
    private val A11Y_NONE = Int.MIN_VALUE
    private var a11yFocused = A11Y_NONE
    private var a11yHovered = A11Y_NONE
    private val a11yLoc = IntArray(2)
    private val a11yR = android.graphics.Rect()

    private fun a11yOn(): Boolean = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isEnabled == true

    private fun a11yIds(): List<Int> {
        val out = ArrayList<Int>()
        if (booting) return out
        for (i in games.indices) if (i < rows.size) out.add(i)
        out.add(A11Y_ADD); out.add(A11Y_ALL); out.add(A11Y_GEAR)
        return out
    }

    private fun a11yBounds(id: Int): RectF? = when (id) {
        A11Y_ADD -> addRect
        A11Y_RECENT -> recentRect
        A11Y_ALL -> allAppsRect
        A11Y_GEAR -> gearRect
        else -> rows.getOrNull(id)
    }?.takeIf { !it.isEmpty }

    /** Tiles are only on screen inside the list area (the buttons are always on screen). */
    private fun a11yShown(id: Int, r: RectF) = id >= A11Y_ADD || (r.bottom > listTop && r.top < listBottom)

    private fun a11yLabel(id: Int): String = when (id) {
        A11Y_ADD -> "Add an app to NEXUS"
        A11Y_RECENT -> "Recent apps"
        A11Y_ALL -> "All apps"
        A11Y_GEAR -> "NEXUS settings"
        else -> games.getOrNull(id)?.let { g ->
            val cd = countdown
            when {
                !g.installed -> "${g.name}, not installed"
                g.pkg == CountdownLink.PKG && cd != null -> {
                    val left = cd.target - System.currentTimeMillis()
                    if (left <= 0) "${cd.name}: time's up" else "${cd.name}: " + countdownText(left) + " left"
                }
                else -> g.name
            }
        } ?: ""
    }

    private fun a11yClick(id: Int): Boolean {
        when (id) {
            A11Y_ADD -> { sfx.tick(); onAdd() }
            A11Y_RECENT -> { sfx.tick(); onRecent() }
            A11Y_ALL -> { sfx.tick(); onDrawer() }
            A11Y_GEAR -> { sfx.tick(); onSettings() }
            else -> { val g = games.getOrNull(id) ?: return false; sfx.confirm(); onPick(g) }
        }
        return true
    }

    private fun a11yAt(x: Float, y: Float): Int {
        for (id in a11yIds()) { val r = a11yBounds(id) ?: continue; if (r.contains(x, y) && a11yShown(id, r) && (id >= A11Y_ADD || y in listTop..listBottom)) return id }
        return A11Y_NONE
    }

    private fun sendA11y(id: Int, type: Int) {
        if (!a11yOn()) return
        @Suppress("DEPRECATION") val ev = android.view.accessibility.AccessibilityEvent.obtain(type)
        ev.packageName = context.packageName
        ev.className = android.widget.Button::class.java.name
        ev.setSource(this, id)
        ev.contentDescription = a11yLabel(id)
        parent?.requestSendAccessibilityEvent(this, ev)
    }

    private val a11yProvider = object : android.view.accessibility.AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(id: Int): android.view.accessibility.AccessibilityNodeInfo? {
            if (id == View.NO_ID) {
                @Suppress("DEPRECATION") val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@HubView)
                onInitializeAccessibilityNodeInfo(info)
                info.contentDescription = "NEXUS home screen"
                for (v in a11yIds()) info.addChild(this@HubView, v)
                if (maxScroll > 0f && !booting) {
                    info.isScrollable = true
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
                    info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
                }
                return info
            }
            val r = a11yBounds(id) ?: return null
            @Suppress("DEPRECATION") val info = android.view.accessibility.AccessibilityNodeInfo.obtain(this@HubView, id)
            info.packageName = context.packageName
            info.className = android.widget.Button::class.java.name
            info.setParent(this@HubView)
            info.contentDescription = a11yLabel(id)
            r.roundOut(a11yR)
            @Suppress("DEPRECATION") info.setBoundsInParent(a11yR)
            getLocationOnScreen(a11yLoc)
            a11yR.offset(a11yLoc[0], a11yLoc[1])
            info.setBoundsInScreen(a11yR)
            info.isVisibleToUser = a11yShown(id, r)
            info.isEnabled = true; info.isFocusable = true; info.isClickable = true
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            if (id in games.indices) {
                info.isLongClickable = true
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, "Options"))
            }
            if (a11yFocused == id) {
                info.isAccessibilityFocused = true
                info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
            } else info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
            return info
        }

        override fun performAction(id: Int, action: Int, args: android.os.Bundle?): Boolean {
            if (id == View.NO_ID) {
                val page = (listBottom - listTop) * 0.8f
                return when (action) {
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                        if (scroll >= maxScroll) false else { scrollVel = 0f; scroll = min(maxScroll, scroll + page); invalidate()
                            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED); true }
                    }
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                        if (scroll <= 0f) false else { scrollVel = 0f; scroll = max(0f, scroll - page); invalidate()
                            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED); true }
                    }
                    else -> performAccessibilityAction(action, args)
                }
            }
            return when (action) {
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK -> a11yClick(id)
                android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                    val g = games.getOrNull(id) ?: return false
                    sfx.tick(); onRemove(g); true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocused != id) {
                        val old = a11yFocused
                        a11yFocused = id
                        if (old != A11Y_NONE) sendA11y(old, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                        sendA11y(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    }
                    true
                }
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                    if (a11yFocused == id) { a11yFocused = A11Y_NONE; sendA11y(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED) }
                    true
                }
                else -> false
            }
        }
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11yProvider

    /** TalkBack's touch exploration: say what's under the finger. */
    override fun dispatchHoverEvent(e: MotionEvent): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (am == null || !am.isEnabled || !am.isTouchExplorationEnabled) return super.dispatchHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val id = a11yAt(e.x, e.y)
                if (id != a11yHovered) {
                    val old = a11yHovered
                    a11yHovered = id
                    if (id != A11Y_NONE) sendA11y(id, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
                    if (old != A11Y_NONE) sendA11y(old, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                }
                return id != A11Y_NONE
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                if (a11yHovered != A11Y_NONE) sendA11y(a11yHovered, android.view.accessibility.AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
                a11yHovered = A11Y_NONE
                return true
            }
        }
        return super.dispatchHoverEvent(e)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        touchedAt = android.os.SystemClock.uptimeMillis()
        if (tracker == null) tracker = android.view.VelocityTracker.obtain()
        tracker?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (booting) return true // the intro always plays in full
                downX = e.x; downY = e.y; lastY = e.y; lastX = e.x
                dragging = false; longFired = false
                maxTravel = 0f
                caught = kotlin.math.abs(scrollVel) > 40 * d || scroll < -2 * d || scroll > maxScroll + 2 * d
                homing = false
                snapTo = Float.NaN
                // Already at the top: a swipe down from here pulls down the notifications
                pullArmed = !caught && scroll <= 1f * d
                scrollVel = 0f
                downRow = rowAt(e.x, e.y)
                swipedUp = false
                if (downRow in games.indices && !caught) postDelayed(longPress, 600)
            }
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                for (k in 0 until e.historySize) travel(e.getHistoricalX(k), e.getHistoricalY(k))
                travel(e.x, e.y)
                if (moveIndex >= 0) {
                    moveY = e.y
                    if (kotlin.math.abs(e.y - downY) > slop) movedWhileHeld = true
                    lastY = e.y
                    return true
                }
                if (!dragging && maxTravel > slop) {
                    dragging = true
                    removeCallbacks(longPress)
                    if (ps2Menu) selected = -1
                }
                if (dragging && !swipedUp) {
                    var dy = lastY - e.y
                    // Resist past the ends
                    if (scroll < 0f || scroll > maxScroll) dy *= 0.4f
                    scroll += dy
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                travel(e.x, e.y)
                tracker?.computeCurrentVelocity(1000)
                val vy = tracker?.yVelocity ?: 0f
                val vx = tracker?.xVelocity ?: 0f
                // A flick, however short, is a swipe, not a tap
                val flick = kotlin.math.hypot(vx, vy) > 350 * d
                val tap = !dragging && !flick && maxTravel <= slop * 0.75f
                if (moveIndex >= 0) {
                    val from = moveIndex
                    moveIndexForDrop = from
                    val to = lastTarget.coerceIn(0, games.size - 1)
                    moveIndex = -1
                    if (!movedWhileHeld) onRemove(games[from])
                    else if (to != from) {
                        // Keep the slide smooth: carry the animated positions over to the new order
                        val list = anim.toMutableList(); val v = list.removeAt(from)
                        v.set(floatingRect()); list.add(to, v)
                        anim = list.toTypedArray()
                        settling = true; settleT = 0.4f
                        onReorder(from, to)
                        sfx.confirm()
                    } else { settling = true; settleT = 0.4f; anim.getOrNull(from)?.set(floatingRect()) }
                    downRow = -1; dragging = false
                    tracker?.recycle(); tracker = null
                    return true
                }
                val pullDy = e.y - downY
                if (!tap && pullArmed && !longFired && t > readyT && pullDy > 64 * d && kotlin.math.abs(e.x - downX) < pullDy * 0.6f && vy > -200 * d) {
                    // Swipe down at the top of the list: notifications, like other launchers
                    onPullDown()
                } else if (!tap) {
                    // Swipe: let the list glide on (a short flick that never got going still moves it)
                    val inList = downY >= listTop - 6 * d && downY <= listBottom + 6 * d
                    if (!swipedUp && (dragging || (flick && inList))) scrollVel = -vy
                } else if (!longFired && launchFlash <= 0f && t > readyT && systemRect.contains(e.x, e.y)) {
                    sfx.tick(); onSystem()
                } else if (!longFired && launchFlash <= 0f && t > readyT && recentRect.contains(e.x, e.y)) {
                    sfx.tick(); onRecent()
                } else if (!longFired && launchFlash <= 0f && t > readyT && addRect.contains(e.x, e.y)) {
                    sfx.tick(); onAdd()
                } else if (!longFired && launchFlash <= 0f && t > readyT && allAppsRect.contains(e.x, e.y)) {
                    sfx.tick(); onDrawer()
                } else if (!longFired && launchFlash <= 0f && t > readyT && gearRect.contains(e.x, e.y)) {
                    sfx.tick(); onSettings()
                } else if (!longFired && launchFlash <= 0f && t > readyT && e.y < 44 * d && downY < 44 * d) {
                    // A tap on the status bar: the notifications, like other phones
                    onPullDown()
                } else if (!longFired && !caught && launchFlash <= 0f && t > readyT) {
                    val r = rowAt(e.x, e.y)
                    if (r >= 0 && r == downRow && ps2Menu) {
                        // PS2 menu: tap once to select (it comes to the middle), tap it again to open
                        val target = (r * listStep).coerceIn(0f, maxScroll)
                        if (r != selected) {
                            selected = r
                            snapTo = target // (it ticks as each app passes the middle on the way)
                            if (kotlin.math.abs(target - scroll) <= listStep * 0.45f) {
                                sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            }
                        } else {
                            snapTo = target
                            sfx.confirm()
                            performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            launchIdx = r; pending = games[r]; launchFlash = LAUNCH_LEN; rows.getOrNull(r)?.let { launchRect.set(it) }
                        }
                        performClick()
                    } else if (r >= 0 && r == downRow) {
                        if (r != selected) { selected = r; sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
                        else {
                            sfx.confirm()
                            performHapticFeedback(if (android.os.Build.VERSION.SDK_INT >= 30) android.view.HapticFeedbackConstants.CONFIRM else android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            pending = games[r]; launchFlash = LAUNCH_LEN; rows.getOrNull(r)?.let { launchRect.set(it) }
                        }
                        performClick()
                    } else if (r < 0) selected = -1
                }
                dragging = false
                downRow = -1
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(longPress); moveIndex = -1; dragging = false; downRow = -1; tracker?.recycle(); tracker = null }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun clearSelection() { selected = -1 }

    /** Home pressed while already on NEXUS: glide back to the top of the list. */
    fun toTop() { selected = -1; if (scroll > 0.5f) homing = true }

    /** Called when the screen comes back (e.g. after closing a game). */
    /** A still picture of the home screen as it stands (no launch glow or fade), for the cover while apps close. */
    fun still(): android.graphics.Bitmap? {
        if (width <= 0 || height <= 0 || booting) return null
        // (Back from an app, nothing is left chosen: the picture shows it the same way)
        launchFlash = 0f; pending = null; enterFade = 0f; settle = 0f; selected = -1
        return try {
            val b = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            lastNs = System.nanoTime() // no time passes for the picture
            draw(Canvas(b))
            lastNs = 0L
            b
        } catch (_: Throwable) { null }
    }

    fun resumed(fromApp: Boolean) {
        lastNs = 0L; launchFlash = 0f; pending = null; launchIdx = -1; snapTo = Float.NaN
        if (fromApp) { enterFade = ENTER_LEN; settle = 1f }
    }

    /** Coming back from an app: the tiles start a touch larger and settle into place (1 → 0). */
    private var settle = 0f
    /** Where the tile that was just opened is (for the app to grow out of it). */
    val launchRect = RectF()

    /** Tile tap → the screen settles into the dark (then the app rises out of it). */
    private val LAUNCH_LEN = 0.34f
    /** How far into the launch glow the app is asked to open. */
    /** How far into the confirm flash the app starts opening (long enough to see it was chosen; then NEXUS's fade). */
    private val LAUNCH_START = 0.35f
    /** Coming back from an app: a short fade up from the dark. */
    private val ENTER_LEN = 0.32f
}
