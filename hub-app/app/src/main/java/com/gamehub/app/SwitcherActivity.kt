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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = SwitcherView(this, sfx)
        setContentView(view)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.post {
            try {
                window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (_: Exception) {}
        }
    }

    override fun onResume() { super.onResume(); view.load() }

    /** Back: return to the app you were just using (not the system screen underneath). */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val last = Recents.list(this).firstOrNull()?.first
        if (last != null && packageManager.getLaunchIntentForPackage(last) != null) openApp(last) else goNexus()
    }
    override fun onDestroy() { super.onDestroy(); sfx.release() }
    override fun onPause() { super.onPause(); if (!isFinishing) finish() }

    fun openApp(pkg: String) {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        startActivity(i, android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
        finish()
    }

    fun goNexus() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.putExtra("fromApp", true)
        startActivity(i, android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
        finish()
    }

    fun openSetting(action: String) { try { startActivity(Intent(action)) } catch (_: Exception) {} }

    fun openAppInfo() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    /** Closes an app's background process (the app itself stays installed). */
    fun closeApp(pkg: String) {
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
        val w = width.toFloat(); val h = height.toFloat()
        val b = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(3, 6, 18), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        p.strokeWidth = 1f; p.color = Color.argb(14, 140, 190, 255)
        var gx = 0f; while (gx < w) { c.drawLine(gx, 0f, gx, h, p); gx += 40 * d }
        var gy = 0f; while (gy < h) { c.drawLine(0f, gy, w, gy, p); gy += 40 * d }
        p.shader = RadialGradient(w / 2, h * 0.46f, w * 0.8f, intArrayOf(Color.argb(70, 50, 100, 230), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        bg = b
        return b
    }

    /** Reads the recent apps and their pictures off the main thread, then fades the cards in. */
    fun load() {
        val pm = a.packageManager
        // Names and icons first (quick), so the screen appears straight away
        val list = ArrayList<Card>()
        for ((pkg, used) in Recents.list(a)) {
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { continue }
            val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
            list.add(Card(pkg, label, icon, null, used))
        }
        cards = list
        pos = 0f; vel = 0f; enter = 0f
        invalidate()
        // Then the screenshots, decoded in the background
        Thread {
            for (cd in list) {
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

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now; t += dt; enter = min(1f, enter + dt * 3.5f)
        val w = width.toFloat(); val h = height.toFloat()

        // Backdrop: deep blue with a faint grid and a glow behind the cards (drawn once, reused)
        c.drawBitmap(backdrop(), 0f, 0f, null)

        // Title
        text.textAlign = Paint.Align.CENTER
        text.textSize = 20 * d; text.letterSpacing = 0.4f
        text.color = Color.argb((255 * enter).toInt(), 235, 242, 255)
        c.drawText("RECENT APPS", w / 2, 64 * d, text)
        fill.shader = LinearGradient(w * 0.3f, 0f, w * 0.7f, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(180, 130, 180, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(w * 0.3f, 76 * d, w * 0.7f, 77.2f * d, fill); fill.shader = null

        val live = cards.filter { !it.gone }
        if (live.isEmpty()) {
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
        } else {
            // Carousel physics: glide, then settle on the nearest card
            if (!dragging) {
                pos += vel * dt
                vel *= exp(-dt * 5f)
                val target = pos.roundToInt().coerceIn(0, live.size - 1).toFloat()
                if (abs(vel) < 2.5f) { pos += (target - pos) * min(1f, dt * 10f); vel = 0f }
                pos = pos.coerceIn(-0.4f, live.size - 0.6f)
            }
            val cw = w * 0.62f; val ch = min(cw * 1.9f, h * 0.6f)
            val cy = h * 0.47f
            // Draw back-to-front so the centre card is on top
            val order = live.indices.sortedByDescending { abs(it - pos) }
            for (i in order) {
                val card = live[i]
                val off = i - pos
                val sc = (1f - min(1f, abs(off)) * 0.16f) * (0.9f + 0.1f * enter)
                val x = w / 2 + off * cw * 0.78f
                if (x < -cw || x > w + cw) continue
                val cwS = cw * sc; val chS = ch * sc
                val top = cy - chS / 2 - card.lift
                r.set(x - cwS / 2, top, x + cwS / 2, top + chS)
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
        postInvalidateOnAnimation()
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
            fill.shader = RadialGradient(rr.centerX(), rr.centerY(), rr.width(), intArrayOf(Color.argb((90 * fade).toInt(), 90, 150, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(rr.left - rr.width() * 0.5f, rr.top - 60 * d, rr.right + rr.width() * 0.5f, rr.bottom + 60 * d, fill); fill.shader = null
        }
        clip.reset(); clip.addRoundRect(rr, rad, rad, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        fill.color = Color.rgb(10, 16, 34); c.drawRect(rr, fill)
        val shot = card.shot
        if (shot != null) {
            // The app's last screen, filling the card from the top
            val scale = rr.width() / shot.width
            val src = android.graphics.Rect(0, 0, shot.width, min(shot.height, (rr.height() / scale).toInt()))
            val dst = RectF(rr.left, rr.top, rr.right, rr.top + src.height() * scale)
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
            fill.shader = LinearGradient(sx - 60 * d, rr.top, sx + 60 * d, rr.bottom, intArrayOf(Color.TRANSPARENT, Color.argb(55, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(rr, fill); fill.shader = null
        }
        c.restore()
        line.strokeWidth = (if (centre) 1.8f else 1f) * d
        line.color = Color.argb(((if (centre) 210 else 90) * fade).toInt(), 150, 195, 255)
        c.drawRoundRect(rr, rad, rad, line)
        // App icon badge on the top edge
        card.icon?.let {
            val s = 34 * d * (rr.width() / (width * 0.62f))
            it.setBounds((rr.centerX() - s / 2).toInt(), (rr.top - s / 2).toInt(), (rr.centerX() + s / 2).toInt(), (rr.top + s / 2).toInt())
            it.alpha = (255 * fade).toInt(); it.draw(c)
        }
    }

    // ------------------------------------------------------------------ touch

    private var downX = 0f; private var downY = 0f; private var lastX = 0f
    private var mode = 0 // 0 = undecided, 1 = sliding the carousel, 2 = swiping a card up
    private var tracker: VelocityTracker? = null
    private val slop = android.view.ViewConfiguration.get(a).scaledTouchSlop.toFloat()

    private fun live() = cards.filter { !it.gone }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (tracker == null) tracker = VelocityTracker.obtain()
        tracker?.addMovement(e)
        val l = live()
        val cw = width * 0.62f
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
                    1 -> vel = -vx / (cw * 0.78f)
                    2 -> {
                        val card = l.getOrNull(pos.roundToInt())
                        if (card != null) {
                            if (card.lift > height * 0.18f || vy < -1500f) {
                                // Swiped away: close it
                                card.gone = true; sfx.tick(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); a.closeApp(card.pkg)
                                pos = pos.coerceAtMost((live().size - 1).coerceAtLeast(0).toFloat())
                            } else card.lift = 0f
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
        val cw = width * 0.62f
        val off = (x - width / 2f) / (cw * 0.78f)
        val idx = (pos + off).roundToInt()
        val centre = pos.roundToInt()
        if (abs(off) < 0.45f && y > height * 0.15f && y < height * 0.8f) {
            l.getOrNull(centre)?.let { sfx.confirm(); performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); a.openApp(it.pkg) }
        } else if (idx in l.indices && idx != centre && y > height * 0.15f && y < height * 0.8f) {
            sfx.tick(); vel = 0f; pos = pos + (idx - pos) * 0.001f; animateTo(idx)
        }
    }

    private var targetIdx = -1
    private fun animateTo(i: Int) { vel = (i - pos) * 5f; targetIdx = i }
}
