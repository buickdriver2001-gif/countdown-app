package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** NEXUS Recent Apps: swipe through cards of recent apps (their last screen), tap to jump back in. */
class SwitcherActivity : Activity() {
    companion object {
        /** Start options with a plain colour splash (no app icon) and no opening animation. */
        fun noIconSplash(c: Context): Bundle {
            val o = android.app.ActivityOptions.makeCustomAnimation(c, 0, 0)
            if (android.os.Build.VERSION.SDK_INT >= 33) o.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
            return o.toBundle()
        }
    }

    private lateinit var view: SwitcherView
    private val sfx by lazy { Sfx() }

    /** Opened from the square button: the helper's cover is up until this screen is ready. */
    var coverPending = false
    /** The cover is shrinking the app you were in into its card: start with that card already in place. */
    var zoomHandover = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coverPending = intent.getBooleanExtra("fromSquare", false)
        zoomHandover = coverPending && intent.getBooleanExtra("zoom", false)
        // Edge to edge over the whole screen (like the helper's cover), so cards line up exactly
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        view = SwitcherView(this, sfx)
        setContentView(view)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.post {
            try {
                window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (_: Exception) {}
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("fromSquare", false)) { coverPending = true; zoomHandover = intent.getBooleanExtra("zoom", false) }
    }

    override fun onResume() { super.onResume(); view.load() }

    /** Back: return to the app you were just using (not the system screen underneath). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val last = Recents.list(this).firstOrNull()?.first
        if (last != null && packageManager.getLaunchIntentForPackage(last) != null) view.openWithZoom(last) else goNexus()
    }
    override fun onDestroy() { super.onDestroy(); sfx.release() }
    override fun onPause() { super.onPause(); if (!isFinishing) finish() }

    /** Opens an app. [revealed]: its card has already grown to fill the screen, so it just comes alive. */
    fun openApp(pkg: String, revealed: Boolean = false): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            startActivity(i, if (revealed) Transitions.reveal(this) else Transitions.rise(this))
            finish()
            true
        } catch (_: Exception) { false }
    }

    fun goNexus() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.putExtra("fromApp", true)
        startActivity(i, Transitions.rise(this))
        finish()
    }

    fun openSetting(action: String) { try { startActivity(Intent(action)) } catch (_: Exception) {} }

    fun openAppInfo() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    /** Closes an app's background process (the app itself stays installed). */
    fun closeApp(pkg: String) {
        // Our own apps close completely; for other apps Android only lets NEXUS stop them in the background
        if (pkg in NexusAccessibility.OWN_APPS) NexusAccessibility.closeOwnApp(this, pkg)
        try { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) } catch (_: Exception) {}
        Recents.remove(this, pkg)
    }
}

@SuppressLint("ViewConstructor")
class SwitcherView(private val a: SwitcherActivity, private val sfx: Sfx) : View(a) {
    private val d = resources.displayMetrics.density

    private class Card(val pkg: String, val label: String, val icon: Drawable?, var shot: Bitmap?, val used: Long) {
        var shotFade = 1f
        var lift = 0f      // swipe-up offset (removing)
        var gone = false
        var flying = false // swiped away: flying off the top
        var flyV = 0f
        var ax = Float.NaN // animated position (in card steps from the centre)
        val rect = RectF() // where it was last drawn
    }
    private var cards = ArrayList<Card>()

    private var pos = 0f          // carousel position (card index, fractional)
    private var vel = 0f
    private var dragging = false
    private var t = 0f
    private var lastNs = 0L
    private var enter = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val r = RectF()
    private val clearRect = RectF()
    private val homeRect = RectF()
    private val helperRect = RectF()
    private val restrictRect = RectF()

    private var bg: Bitmap? = null

    private fun backdrop(): Bitmap {
        val cur = bg
        if (cur != null && cur.width == width && cur.height == height) return cur
        cur?.recycle()
        // Shared with the helper's cover, so the hand-over from the square button is seamless
        return CoverView.backdrop(width, height, d).also { bg = it }
    }

    /** Builds the cards (names and icons are cached, so this is quick), then decodes pictures in the background. */
    fun load() {
        val list = ArrayList<Card>()
        for ((pkg, used) in Recents.list(a)) {
            val info = AppCache.get(a, pkg) ?: continue
            list.add(Card(pkg, info.label, info.icon, null, used))
        }
        cards = list
        pos = 0f; vel = 0f; targetIdx = 0; enter = if (a.zoomHandover) 1f else 0f
        opening = null; opened = false
        // The first card's picture straight away (it's the one in the middle, and the one the cover hands over to)
        list.firstOrNull()?.let { cd ->
            cd.shot = NexusAccessibility.instance?.memShot(cd.pkg) ?: run {
                val f = NexusAccessibility.snapFile(a, cd.pkg)
                if (f.exists()) try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } else null
            }
            cd.shotFade = 1f
        }
        invalidate()
        // Then, in the background: drop any app that was uninstalled since, and decode the other pictures
        val pm = a.packageManager
        Thread {
            for (cd in list) {
                if (pm.getLaunchIntentForPackage(cd.pkg) == null) {
                    // Can't be opened right now: hide it. Only forget it for good if it's really uninstalled
                    // (an app being updated can't be opened for a moment, and should come back)
                    val uninstalled = try { pm.getApplicationInfo(cd.pkg, 0); false } catch (_: android.content.pm.PackageManager.NameNotFoundException) { true } catch (_: Exception) { false }
                    AppCache.forget(cd.pkg)
                    post { if (!cd.flying) { cd.gone = true; if (uninstalled) Recents.remove(a, cd.pkg) } }
                    continue
                }
                if (cd.shot != null) continue
                val f = NexusAccessibility.snapFile(a, cd.pkg)
                if (!f.exists()) continue
                val b = try { BitmapFactory.decodeFile(f.absolutePath) } catch (_: Exception) { null } ?: continue
                post { cd.shot = b; cd.shotFade = 0f }
            }
        }.start()
    }

    private fun ago(ms: Long): String {
        val s = (System.currentTimeMillis() - ms) / 1000
        return when { s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
    }

    /** Draws a frame; if anything ever goes wrong while drawing, that frame is skipped and noted, never a crash. */
    override fun onDraw(c: Canvas) {
        val saved = c.saveCount
        try { drawFrame(c) } catch (t: Throwable) {
            c.restoreToCount(saved)
            if (!drawProblemNoted) { drawProblemNoted = true; App.noteProblem(context, "Recent Apps", t) }
            postInvalidateOnAnimation()
        }
    }
    private var drawProblemNoted = false

    private fun drawFrame(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now; t += dt; enter = min(1f, enter + dt * 3.5f)
        val w = width.toFloat(); val h = height.toFloat()
        // Drawn and in front: let the helper fade its cover away. When the cover was shrinking the app
        // into its card, that card is already here in the same spot; otherwise the cards fade in now.
        if (a.coverPending && a.hasWindowFocus()) {
            a.coverPending = false
            if (!a.zoomHandover) enter = 0f
            a.zoomHandover = false
            NexusAccessibility.instance?.switcherReady()
        }

        // Backdrop: deep blue with a faint grid and a glow behind the cards (drawn once, reused)
        c.drawBitmap(backdrop(), 0f, 0f, null)

        // Title
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20 * d; text.letterSpacing = 0.4f
        text.color = Color.argb((255 * enter).toInt(), 235, 242, 255)
        c.drawText("RECENT APPS", w / 2, 64 * d, text)
        if (ruleW != width) {
            ruleW = width
            ruleShader = LinearGradient(w * 0.3f, 0f, w * 0.7f, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(180, 130, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        fill.shader = ruleShader
        c.drawRect(w * 0.3f, 76 * d, w * 0.7f, 77.2f * d, fill); fill.shader = null

        val live = live()
        CoverView.cardRect(w, h, cardBox)
        val cwF = cardBox.width(); val chF = cardBox.height(); val cyF = cardBox.centerY()
        // Cards that were swiped away keep flying up, speeding up, shrinking and fading
        for (card in cards) {
            if (!card.flying || card.gone) continue
            card.lift += card.flyV * dt
            card.flyV *= 1f + dt * 4f
            val k = (card.lift / (h * 0.75f)).coerceIn(0f, 1f)
            val sc = 1f - 0.18f * k
            val x = w / 2 + (if (card.ax.isNaN()) 0f else card.ax) * cwF * 0.78f
            val cwS = cwF * sc; val chS = chF * sc
            val top = cyF - chS / 2 - card.lift
            r.set(x - cwS / 2, top, x + cwS / 2, top + chS)
            drawCard(c, card, r, (1f - k).coerceIn(0f, 1f), false)
            if (card.lift > h) card.gone = true
        }
        if (live.isEmpty() && cards.none { it.flying && !it.gone }) {
            val on = Recents.serviceOn(a)
            text.textSize = 16 * d; text.letterSpacing = 0.05f; text.color = Color.argb(220, 200, 220, 250)
            c.drawText(if (on) "No recent apps yet" else "The NEXUS helper is off", w / 2, h * 0.36f, text)
            text.textSize = 12.5f * d; text.color = Color.argb(170, 170, 195, 235)
            val lines = if (on) listOf("Open a few apps for a few seconds each,", "then come back here.")
                else listOf("NEXUS needs its helper switched on to see", "which apps you've used.", "",
                    "1. Tap TURN ON HELPER below.", "2. Choose NEXUS Recent Apps and switch it on.",
                    "3. If it says \"Restricted setting\", tap the second button,", "    then ⋮ (top right) → Allow restricted settings,", "    and switch the helper on again.")
            lines.forEachIndexed { i, l -> c.drawText(l, w / 2, h * 0.36f + (30 + i * 20) * d, text) }
            if (!on) {
                helperRect.set(w / 2 - 130 * d, h * 0.62f, w / 2 + 130 * d, h * 0.62f + 48 * d)
                restrictRect.set(w / 2 - 130 * d, h * 0.62f + 60 * d, w / 2 + 130 * d, h * 0.62f + 104 * d)
                fill.shader = LinearGradient(0f, helperRect.top, 0f, helperRect.bottom, intArrayOf(Color.rgb(70, 130, 245), Color.rgb(40, 85, 200)), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(helperRect, 24 * d, 24 * d, fill); fill.shader = null
                text.textSize = 14 * d; text.letterSpacing = 0.22f; text.color = Color.WHITE
                c.drawText("TURN ON HELPER", helperRect.centerX(), helperRect.centerY() + 5 * d, text)
                pill(c, restrictRect, "ALLOW RESTRICTED SETTINGS", true)
            } else { helperRect.setEmpty(); restrictRect.setEmpty() }
        } else if (live.isNotEmpty()) {
            // Carousel physics: glide, then settle on the nearest card
            if (!dragging) {
                // A spring that glides into the chosen card and settles there (no sudden snap at the end)
                if (targetIdx < 0) targetIdx = pos.roundToInt()
                targetIdx = targetIdx.coerceIn(0, live.size - 1)
                var left = dt
                while (left > 0f) {
                    val st = min(left, 1f / 120f)
                    val acc = SPRING * (targetIdx - pos) - 2f * kotlin.math.sqrt(SPRING) * vel
                    vel += acc * st; pos += vel * st
                    left -= st
                }
                if (abs(targetIdx - pos) < 0.0005f && abs(vel) < 0.01f) { pos = targetIdx.toFloat(); vel = 0f }
            }
            val cw = cwF; val ch = chF
            val cy = cyF
            // Draw back-to-front so the centre card is on top
            val order = live.indices.sortedByDescending { abs(it - pos) }
            val glide = min(1f, dt * 12f)
            for (i in order) {
                val card = live[i]
                // Each card glides to its slot (so closing one makes the others slide over smoothly)
                val want = i - pos
                card.ax = if (card.ax.isNaN() || (dragging && mode == 1)) want else card.ax + (want - card.ax) * glide
                // Springs back down if it wasn't swiped far enough
                if (!(dragging && mode == 2)) card.lift *= 1f - min(1f, dt * 12f)
                val off = card.ax
                val sc = (1f - min(1f, abs(off)) * 0.16f) * (0.9f + 0.1f * enter)
                val x = w / 2 + off * cw * 0.78f
                if (x < -cw || x > w + cw) continue
                val cwS = cw * sc; val chS = ch * sc
                val top = cy - chS / 2 - card.lift
                r.set(x - cwS / 2, top, x + cwS / 2, top + chS)
                card.rect.set(r)
                val fade = ((1f - min(1f, abs(off)) * 0.45f) * (1f - min(1f, card.lift / (h * 0.5f)))).coerceIn(0f, 1f)
                drawCard(c, card, r, fade, abs(off) < 0.5f)
            }
            // Name and time under the centre card
            val ci = pos.roundToInt().coerceIn(0, live.size - 1)
            val cc = live[ci]
            text.textAlign = Paint.Align.CENTER
            text.textSize = 18 * d; text.letterSpacing = 0.1f; text.color = Color.argb(240, 240, 245, 255)
            c.drawText(cc.label, w / 2, cy + ch / 2 + 40 * d, text)
            text.textSize = 12 * d; text.letterSpacing = 0.08f; text.color = Color.argb(170, 170, 200, 240)
            c.drawText(ago(cc.used), w / 2, cy + ch / 2 + 62 * d, text)
        }

        // Bottom: Clear all / NEXUS
        clearRect.set(w / 2 - 150 * d, h - 70 * d, w / 2 - 10 * d, h - 30 * d)
        homeRect.set(w / 2 + 10 * d, h - 70 * d, w / 2 + 150 * d, h - 30 * d)
        pill(c, clearRect, "CLEAR ALL", live.isNotEmpty())
        pill(c, homeRect, "NEXUS", true)
        drawOpening(c, dt)
        postInvalidateOnAnimation()
    }

    // ------------------------------------------------------------------ opening an app: its card grows to fill the screen
    private var opening: Card? = null
    private var openT = 0f
    private var opened = false
    private val openFrom = RectF()
    private val openRect = RectF()
    private val OPEN_LEN = 0.38f

    /** Opens [pkg]: if its card is the one in the middle, the card grows into the app first. */
    fun openWithZoom(pkg: String) {
        if (opening != null) return
        val l = live()
        val card = l.getOrNull(pos.roundToInt().coerceIn(0, (l.size - 1).coerceAtLeast(0)))
        if (card == null || card.pkg != pkg || card.rect.isEmpty) { a.openApp(pkg); return }
        opening = card; openT = 0f; opened = false
        openFrom.set(card.rect)
        invalidate()
    }

    private fun drawOpening(c: Canvas, dt: Float) {
        val card = opening ?: return
        openT += dt
        val k = (openT / OPEN_LEN).coerceIn(0f, 1f)
        // Gentle start, gentle landing
        val e = if (k < 0.5f) 4f * k * k * k else 1f - (-2f * k + 2f).let { it * it * it } / 2f
        val w = width.toFloat(); val h = height.toFloat()
        // Everything else softly sinks into the dark as the card comes forward
        fill.color = Color.argb((205 * e).toInt(), 2, 4, 12); c.drawRect(0f, 0f, w, h, fill)
        openRect.set(openFrom.left * (1f - e), openFrom.top * (1f - e), openFrom.right + (w - openFrom.right) * e, openFrom.bottom + (h - openFrom.bottom) * e)
        val rad = 16 * d * (1f - e)
        clip.reset(); clip.addRoundRect(openRect, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.color = Color.rgb(10, 16, 34); c.drawRect(openRect, fill)
        val shot = card.shot
        if (shot != null) {
            val scale = openRect.width() / shot.width
            val src = android.graphics.Rect(0, 0, shot.width, min(shot.height, (openRect.height() / scale).toInt()))
            bmpPaint.alpha = 255
            c.drawBitmap(shot, src, RectF(openRect.left, openRect.top, openRect.right, openRect.top + src.height() * scale), bmpPaint)
        } else card.icon?.let {
            val s = min(openRect.width(), openRect.height()) * 0.3f
            it.setBounds((openRect.centerX() - s / 2).toInt(), (openRect.centerY() - s / 2).toInt(), (openRect.centerX() + s / 2).toInt(), (openRect.centerY() + s / 2).toInt())
            it.alpha = 255; it.draw(c)
        }
        c.restore()
        if (k >= 1f && !opened) {
            opened = true
            // With its last picture filling the screen the app just comes alive; without one it rises in.
            // If it can't be opened (e.g. it was just uninstalled), put the cards back.
            if (!a.openApp(card.pkg, revealed = shot != null)) { opening = null; opened = false }
        }
    }

    private fun pill(c: Canvas, rr: RectF, label: String, on: Boolean) {
        fill.color = Color.argb(if (on) 70 else 25, 40, 80, 200); c.drawRoundRect(rr, 20 * d, 20 * d, fill)
        line.strokeWidth = 1f * d; line.color = Color.argb(if (on) 140 else 50, 140, 190, 255); c.drawRoundRect(rr, 20 * d, 20 * d, line)
        text.textAlign = Paint.Align.CENTER; text.textSize = 12.5f * d; text.letterSpacing = 0.25f
        text.color = Color.argb(if (on) 230 else 90, 220, 235, 255)
        c.drawText(label, rr.centerX(), rr.centerY() + 4.5f * d, text)
    }

    private fun drawCard(c: Canvas, card: Card, rr: RectF, fade: Float, centre: Boolean) {
        val rad = 16 * d
        // Glow behind the centre card
        if (centre) {
            // One glow shader, moved and sized with a matrix (nothing new made every frame)
            glowMatrix.setScale(rr.width(), rr.width()); glowMatrix.postTranslate(rr.centerX(), rr.centerY())
            glowShader.setLocalMatrix(glowMatrix)
            fill.shader = glowShader; fill.alpha = (255 * fade).toInt()
            c.drawRect(rr.left - rr.width() * 0.5f, rr.top - 60 * d, rr.right + rr.width() * 0.5f, rr.bottom + 60 * d, fill); fill.shader = null; fill.alpha = 255
        }
        clip.reset(); clip.addRoundRect(rr, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.color = Color.rgb(10, 16, 34); c.drawRect(rr, fill)
        val shot = card.shot
        if (shot != null) {
            // The app's last screen, filling the card from the top
            val scale = rr.width() / shot.width
            srcR.set(0, 0, shot.width, min(shot.height, (rr.height() / scale).toInt()))
            val src = srcR
            val dst = dstR.apply { set(rr.left, rr.top, rr.right, rr.top + src.height() * scale) }
            card.shotFade = min(1f, card.shotFade + 0.12f)
            bmpPaint.alpha = (255 * fade * card.shotFade).toInt()
            c.drawBitmap(shot, src, dst, bmpPaint)
        } else {
            // No screenshot (secure app, or not seen yet): big icon on glass
            card.icon?.let {
                val s = rr.width() * 0.34f
                it.setBounds((rr.centerX() - s / 2).toInt(), (rr.centerY() - s / 2).toInt(), (rr.centerX() + s / 2).toInt(), (rr.centerY() + s / 2).toInt())
                it.alpha = (255 * fade).toInt(); it.draw(c)
            }
        }
        // Dim side cards; white light pass across the centre card
        if (fade < 1f) { fill.color = Color.argb(((1f - fade) * 170).toInt(), 2, 4, 14); c.drawRect(rr, fill) }
        if (centre) {
            val k = (t * 0.55f) % 1.8f
            val sx = rr.left + (rr.width() + 200 * d) * k - 100 * d
            // Same diagonal band as before: from (sx - 60dp, top) to (sx + 60dp, bottom)
            val vx = 120 * d; val vy = rr.height()
            val len = kotlin.math.hypot(vx, vy)
            sweepMatrix.setScale(len / 2f, len / 2f)
            sweepMatrix.postRotate(Math.toDegrees(kotlin.math.atan2(vy, vx).toDouble()).toFloat())
            sweepMatrix.postTranslate(sx, rr.centerY())
            sweepShader.setLocalMatrix(sweepMatrix)
            fill.shader = sweepShader
            c.drawRect(rr, fill); fill.shader = null
        }
        c.restore()
        line.strokeWidth = (if (centre) 1.8f else 1f) * d
        line.color = Color.argb(((if (centre) 210 else 90) * fade).toInt(), 150, 195, 255)
        c.drawRoundRect(rr, rad, rad, line)
        // App icon badge on the top edge
        card.icon?.let {
            val s = 34 * d * (rr.width() / (width * CoverView.CARD_SCALE))
            it.setBounds((rr.centerX() - s / 2).toInt(), (rr.top - s / 2).toInt(), (rr.centerX() + s / 2).toInt(), (rr.top + s / 2).toInt())
            it.alpha = (255 * fade).toInt(); it.draw(c)
        }
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var mode = 0 // 0 = undecided, 1 = sliding the carousel, 2 = swiping a card up
    private var tracker: VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop.toFloat()

    private val liveList = ArrayList<Card>()
    /** Cards still in the carousel (reuses one list rather than making a new one every frame). */
    private fun live(): List<Card> { liveList.clear(); for (cd in cards) if (!cd.gone && !cd.flying) liveList.add(cd); return liveList }

    private val cardBox = RectF()
    private var ruleW = -1
    private var ruleShader: Shader? = null
    private val srcR = android.graphics.Rect()
    private val dstR = RectF()
    private val glowShader = RadialGradient(0f, 0f, 1f, intArrayOf(Color.argb(90, 90, 150, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val glowMatrix = android.graphics.Matrix()
    private val sweepShader = LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(55, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val sweepMatrix = android.graphics.Matrix()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (opening != null) return true // an app is opening
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        val l = live().toList()
        val cw = width * CoverView.CARD_SCALE
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; mode = 0; dragging = true; vel = 0f }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 0) {
                    if (abs(e.x - downX) > slop) mode = 1
                    else if (downY - e.y > slop && l.isNotEmpty()) mode = 2
                }
                if (mode == 1) pos -= (e.x - lastX) / (cw * 0.78f)
                if (mode == 2) l.getOrNull(pos.roundToInt())?.lift = max(0f, downY - e.y)
                lastX = e.x
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f; val vy = tracker?.yVelocity ?: 0f
                when (mode) {
                    1 -> {
                        // Fling: aim for where the swipe would carry it, one or more cards along
                        vel = -vx / (cw * 0.78f)
                        targetIdx = (pos + vel * 0.22f).roundToInt().coerceIn(0, (l.size - 1).coerceAtLeast(0))
                    }
                    2 -> {
                        val card = l.getOrNull(pos.roundToInt())
                        if (card != null) {
                            if (card.lift > height * 0.18f || vy < -1500f) {
                                // Swiped away: it flies off the top (keeping your swipe's speed) and closes
                                card.flying = true
                                card.flyV = max(1600f * d, -vy)
                                sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); a.closeApp(card.pkg)
                                pos = pos.coerceAtMost((live().size - 1).coerceAtLeast(0).toFloat())
                            }
                        }
                    }
                    else -> tap(e.x, e.y, l)
                }
                tracker?.recycle(); tracker = null
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; l.forEach { it.lift = 0f }; tracker?.recycle(); tracker = null }
        }
        return true
    }

    private fun tap(x: Float, y: Float, l: List<Card>) {
        if (clearRect.contains(x, y) && l.isNotEmpty()) {
            sfx.confirm()
            for (c in l) a.closeApp(c.pkg)
            Recents.clear(a)
            a.goNexus(); return
        }
        if (homeRect.contains(x, y)) { sfx.confirm(); a.goNexus(); return }
        if (l.isEmpty()) {
            if (helperRect.contains(x, y)) { sfx.confirm(); a.openSetting(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            else if (restrictRect.contains(x, y)) { sfx.confirm(); a.openAppInfo() }
            return
        }
        val cw = width * CoverView.CARD_SCALE
        val off = (x - width / 2f) / (cw * 0.78f)
        val idx = (pos + off).roundToInt()
        val centre = pos.roundToInt()
        if (abs(off) < 0.45f && y > height * 0.15f && y < height * 0.8f) {
            l.getOrNull(centre)?.let { sfx.confirm(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); openWithZoom(it.pkg) }
        } else if (idx in l.indices && idx != centre && y > height * 0.15f && y < height * 0.8f) {
            sfx.tick(); animateTo(idx)
        }
    }

    /** The card the carousel is gliding to (-1: the nearest one). */
    private var targetIdx = -1
    private val SPRING = 110f
    private fun animateTo(i: Int) { targetIdx = i }
}
