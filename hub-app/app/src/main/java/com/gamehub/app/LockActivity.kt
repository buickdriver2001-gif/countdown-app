package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The NEXUS lock screen. It sits ON TOP of the phone's real lock screen (which still protects the phone):
 * swipe up to bring up the phone's own PIN / pattern / fingerprint unlock, then you land in NEXUS.
 */
class LockActivity : Activity() {

    private lateinit var view: LockView

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Unlocked another way (fingerprint, face): go straight on
            if (i.action == Intent.ACTION_USER_PRESENT) afterUnlock()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        view = LockView(this)
        setContentView(view)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.decorView.post {
            try {
                window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (_: Exception) {}
        }
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(unlocked, f)
    }

    override fun onResume() {
        super.onResume()
        // While the screen is off the phone may take a moment to lock: stay ready rather than closing early
        if (!stillNeeded()) return
        showing = true
        view.restart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) stillNeeded()
    }

    /**
     * Steps aside only if the screen is ON and the phone is NOT locked (e.g. woken again within
     * a few seconds of turning it off). Returns true if the lock screen should stay.
     */
    private fun stillNeeded(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (km?.isKeyguardLocked != true && pm?.isInteractive == true) {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return false
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        showing = false
    }

    override fun onDestroy() {
        alive = false
        try { unregisterReceiver(unlocked) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Back does nothing here: this is a lock screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    /** Swipe up: ask the phone to unlock (its own secure PIN / pattern / fingerprint screen). */
    fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return finish()
        if (!km.isKeyguardLocked) { afterUnlock(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { afterUnlock() }
            override fun onDismissCancelled() { view.reset() }
            override fun onDismissError() { view.reset() }
        })
    }

    private fun afterUnlock() {
        if (isFinishing) return
        // Land in NEXUS when it's the home screen; otherwise just get out of the way
        if (HomeMode.isOn(this) && HomeMode.isDefault(this)) {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromApp", true)
                startActivity(i, Transitions.rise(this))
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, R.anim.nexus_exit)
    }

    companion object {
        /** True while the NEXUS lock screen is on screen. */
        @Volatile var showing = false

        /**
         * True while the NEXUS lock screen exists at all (even if something else, such as an alarm,
         * the camera or a call, is in front of it). Used so NEXUS never pushes itself over those.
         */
        @Volatile var alive = false

        /** Whether the NEXUS lock screen is switched on (off unless the user turns it on). */
        fun enabled(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("lockScreen", false)
    }
}

@SuppressLint("ViewConstructor")
class LockView(private val a: LockActivity) : View(a) {
    private val d = resources.displayMetrics.density
    private var t = 0f
    private var lastNs = 0L
    private var dragY = 0f
    private var downY = 0f
    private var dragging = false
    private var asked = false

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) }
    private val timeFmt = SimpleDateFormat("h:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
    private var bg: Bitmap? = null

    fun restart() { t = 0f; lastNs = 0L; reset(); loadFaces(); notified = false; invalidate() }
    private var notified = false
    private val power = a.getSystemService(android.os.PowerManager::class.java)

    private fun litAndInFront(): Boolean {
        if (power?.isInteractive != true) return false
        if (display?.state != android.view.Display.STATE_ON) return false
        return a.hasWindowFocus()
    }
    fun reset() { dragY = 0f; dragging = false; asked = false; invalidate() }

    /**
     * Icons for the cube's faces: only the apps that are open right now (the ones in NEXUS Recent Apps,
     * newest first). Close an app there and it leaves the cube. Icons only, never what's on screen.
     */
    @Volatile private var faces: List<Bitmap> = emptyList()

    private fun loadFaces() {
        Thread {
            val pm = a.packageManager
            // The apps that are open right now, newest first (the same list as Recent Apps). Each app appears once.
            val pkgs = Recents.list(a).map { it.first }.distinct()
                .filter { it != a.packageName && pm.getLaunchIntentForPackage(it) != null }
            val out = ArrayList<Bitmap>()
            val size = 256
            fun glass(): Bitmap {
                val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(0f, 0f, 0f, size.toFloat(), Color.rgb(16, 30, 70), Color.rgb(6, 10, 26), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
                return b
            }
            for (pkg in pkgs) {
                if (out.size >= 6) break
                val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null } ?: continue
                // Dark glass face with the app's icon in the middle
                val b = glass()
                icon.setBounds(size / 5, size / 5, size * 4 / 5, size * 4 / 5)
                icon.draw(Canvas(b))
                out.add(b)
            }
            // Faces without an app are plain glass (never a repeat of an app)
            val empty = glass()
            while (out.size < 6) out.add(empty)
            post { faces = out }
        }.start()
    }

    private fun backdrop(): Bitmap {
        val cur = bg
        if (cur != null && cur.width == width && cur.height == height) return cur
        cur?.recycle()
        val w = width.toFloat(); val h = height.toFloat()
        val b = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(Color.rgb(2, 4, 14), Color.rgb(7, 14, 38), Color.rgb(1, 2, 8)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        p.strokeWidth = 1f; p.color = Color.argb(12, 140, 190, 255)
        var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h, p); x += 40 * d }
        var y = 0f; while (y < h) { c.drawLine(0f, y, w, y, p); y += 40 * d }
        bg = b
        return b
    }

    // Battery info, refreshed every couple of seconds (not every frame)
    private var batLevel = -1
    private var batCharging = false
    private var batFullIn = -1L
    private var batReadAt = 0L

    private fun readBattery() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - batReadAt < 2000 && batReadAt != 0L) return
        batReadAt = now
        try {
            val bm = a.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = a.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = (st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
            batCharging = plugged && (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
        } catch (_: Exception) {}
    }

    private fun fullInText(ms: Long): String? {
        if (ms <= 0L) return null
        val m = (ms / 60000L).toInt()
        return if (m >= 60) "Full in ${m / 60}h ${m % 60}m" else "Full in ${max(1, m)}m"
    }

    /** Draws a frame; if anything ever goes wrong while drawing, that frame is skipped and noted, never a crash. */
    override fun onDraw(c: Canvas) {
        val saved = c.saveCount
        try { drawFrame(c) } catch (t: Throwable) {
            c.restoreToCount(saved)
            if (!drawProblemNoted) { drawProblemNoted = true; App.noteProblem(context, "the lock screen", t) }
            postInvalidateOnAnimation()
        }
    }
    private var drawProblemNoted = false

    private fun drawFrame(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else min(0.05f, (now - lastNs) / 1e9f)
        lastNs = now; t += dt
        val w = width.toFloat(); val h = height.toFloat()
        val intro = min(1f, t / 1.1f)
        val lift = -dragY // content follows the finger up
        c.drawBitmap(backdrop(), 0f, 0f, null)
        // Lift the black wake-up cover only once the NEXUS lock screen is really what's on screen:
        // the screen is lit, and NEXUS has the focus (it doesn't while the phone's own lock screen is
        // still drawn on top). The fade-in then starts from black, so the switch is seamless.
        if (!notified && litAndInFront()) {
            notified = true
            t = 0f
            NexusAccessibility.instance?.lockReady()
        }

        val fade = (1f - dragY / (h * 0.35f)).coerceIn(0f, 1f) * intro

        // NEXUS wordmark at the very top
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = 12 * d; text.letterSpacing = 0.6f
        text.color = Color.argb((110 * fade).toInt(), 170, 200, 255)
        c.drawText("NEXUS", w / 2 + 4 * d, 56 * d + lift * 0.2f, text)

        // Clock, date and battery: top third, clear of everything else
        val clockY = h * 0.24f + lift * 0.5f
        text.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        text.textSize = 88 * d; text.letterSpacing = 0.02f
        text.color = Color.argb((255 * fade).toInt(), 240, 246, 255)
        text.setShadowLayer(16 * d, 0f, 0f, Color.argb((150 * fade).toInt(), 60, 130, 255))
        c.drawText(timeFmt.format(Date()), w / 2, clockY, text)
        text.setShadowLayer(0f, 0f, 0f, 0)
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = 15 * d; text.letterSpacing = 0.14f
        text.color = Color.argb((210 * fade).toInt(), 190, 210, 245)
        c.drawText(dateFmt.format(Date()).uppercase(), w / 2, clockY + 40 * d, text)
        readBattery()
        val bat = batLevel
        if (bat >= 0) {
            val charging = batCharging
            val label = when {
                charging && bat >= 100 -> "Charged"
                charging -> "Charging  ·  $bat%"
                else -> "$bat%"
            }
            text.textSize = 12 * d; text.letterSpacing = 0.15f; text.textAlign = Paint.Align.LEFT
            val labelW = text.measureText(label)
            // Battery outline + label, centred together
            val bw = 22 * d; val bh = 11 * d
            val total = bw + 3 * d + 8 * d + labelW
            val bx = w / 2 - total / 2; val byy = clockY + 62 * d
            line.strokeWidth = 1.2f * d; line.color = Color.argb((170 * fade).toInt(), 170, 200, 245)
            c.drawRoundRect(bx, byy, bx + bw, byy + bh, 2 * d, 2 * d, line)
            c.drawRect(bx + bw + 1 * d, byy + bh * 0.3f, bx + bw + 3 * d, byy + bh * 0.7f, line)
            val low = bat <= 15 && !charging
            val pulse = if (charging) 0.65f + 0.35f * sin(t * 3f) else 1f
            fill.color = when {
                charging -> Color.argb((220 * fade * pulse).toInt(), 110, 230, 150)
                low -> Color.argb((200 * fade).toInt(), 255, 120, 110)
                else -> Color.argb((190 * fade).toInt(), 170, 210, 255)
            }
            c.drawRect(bx + 2 * d, byy + 2 * d, bx + 2 * d + (bw - 4 * d) * bat / 100f, byy + bh - 2 * d, fill)
            if (charging) {
                // Lightning bolt over the battery
                bolt.reset()
                val mx = bx + bw / 2; val top = byy - 2 * d; val bot = byy + bh + 2 * d
                bolt.moveTo(mx + 2 * d, top); bolt.lineTo(mx - 3.5f * d, byy + bh * 0.55f); bolt.lineTo(mx, byy + bh * 0.55f)
                bolt.lineTo(mx - 2 * d, bot); bolt.lineTo(mx + 3.5f * d, byy + bh * 0.42f); bolt.lineTo(mx, byy + bh * 0.42f); bolt.close()
                fill.color = Color.argb((255 * fade).toInt(), 255, 255, 255)
                c.drawPath(bolt, fill)
            }
            text.color = if (charging) Color.argb((220 * fade).toInt(), 150, 235, 175) else Color.argb((170 * fade).toInt(), 170, 200, 245)
            c.drawText(label, bx + bw + 11 * d, byy + bh - 1 * d, text)
            text.textAlign = Paint.Align.CENTER
        }

        // A smaller glass cube turning slowly in its own space below the text
        val cx = w / 2; val cy = h * 0.6f + lift * 0.3f
        val s = min(w, h) * 0.16f * (0.85f + 0.15f * intro)
        fill.shader = RadialGradient(cx, cy, s * 3.2f, intArrayOf(Color.argb((80 * intro).toInt(), 60, 120, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 3.2f, fill); fill.shader = null
        cube(c, cx, cy, s, t * 0.35f, 0.9f * fade)

        // Swipe-up handle: chevrons that breathe, and a glow line
        val by = h - 70 * d + lift
        for (k in 0 until 3) {
            val ph = ((t * 1.2f - k * 0.25f) % 1f + 1f) % 1f
            val alpha = (sin(ph * Math.PI).toFloat() * 200 * fade).toInt()
            line.color = Color.argb(alpha, 200, 225, 255); line.strokeWidth = 2f * d
            val yy = by - k * 10 * d
            c.drawLine(w / 2 - 12 * d, yy + 6 * d, w / 2, yy, line); c.drawLine(w / 2, yy, w / 2 + 12 * d, yy + 6 * d, line)
        }
        fill.shader = LinearGradient(w * 0.3f, 0f, w * 0.7f, 0f, intArrayOf(Color.TRANSPARENT, Color.argb((150 * fade).toInt(), 140, 190, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(w * 0.3f, by + 22 * d, w * 0.7f, by + 23.2f * d, fill); fill.shader = null

        // Spring back when let go
        if (!dragging && dragY > 0f) dragY = max(0f, dragY - dt * h * 2.5f)
        // Fade in from black
        if (intro < 1f) { fill.color = Color.argb(((1f - intro) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, fill) }
        postInvalidateOnAnimation()
    }

    private val bolt = android.graphics.Path()
    private val px = FloatArray(8); private val py = FloatArray(8)
    private val mtx = android.graphics.Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rot = FloatArray(3)

    /** Turns a cube point (±1 coordinates) the same way the cube is turned. */
    private fun turn(x: Float, y: Float, z: Float, ang: Float) {
        val ca = cos(ang); val sa = sin(ang); val cb = cos(0.5f); val sb = sin(0.5f)
        val x1 = x * ca + z * sa; val z1 = -x * sa + z * ca
        rot[0] = x1; rot[1] = y * cb - z1 * sb; rot[2] = y * sb + z1 * cb
    }

    // Each face: outward direction (n) and its "up" direction (u), so pictures sit the right way round
    private val faceDefs = arrayOf(
        floatArrayOf(0f, 0f, -1f, 0f, 1f, 0f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f, 0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f, 0f, 1f, 0f),
        floatArrayOf(0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(0f, -1f, 0f, 0f, 0f, -1f)
    )
    private val faceOrder = arrayOf(0, 1, 2, 3, 4, 5)


    private val faceDepth = FloatArray(6)

    private fun cube(c: Canvas, cx: Float, cy: Float, s: Float, ang: Float, alpha: Float) {
        // Pictures on the faces that face you (far ones first), drawn in perspective
        val pics = faces
        if (pics.isNotEmpty()) {
            for (f in 0 until 6) { val fd = faceDefs[f]; turn(fd[0], fd[1], fd[2], ang); faceDepth[f] = rot[2] }
            val sorted = faceOrder.sortedByDescending { faceDepth[it] }
            for (f in sorted) {
                if (faceDepth[f] > -0.05f) continue // facing away
                val fd = faceDefs[f]
                val nx = fd[0]; val ny = fd[1]; val nz = fd[2]; val ux = fd[3]; val uy = fd[4]; val uz = fd[5]
                // right = n × u
                val rx = ny * uz - nz * uy; val ry = nz * ux - nx * uz; val rz = nx * uy - ny * ux
                val corners = arrayOf(floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f))
                for ((k, cr) in corners.withIndex()) {
                    turn(nx + rx * cr[0] + ux * cr[1], ny + ry * cr[0] + uy * cr[1], nz + rz * cr[0] + uz * cr[1], ang)
                    val pr = 3.2f / (3.2f + rot[2])
                    dst[k * 2] = cx + rot[0] * s * pr; dst[k * 2 + 1] = cy - rot[1] * s * pr
                }
                val bmp = pics.getOrNull(f) ?: continue
                src[0] = 0f; src[1] = 0f; src[2] = bmp.width.toFloat(); src[3] = 0f
                src[4] = bmp.width.toFloat(); src[5] = bmp.height.toFloat(); src[6] = 0f; src[7] = bmp.height.toFloat()
                mtx.setPolyToPoly(src, 0, dst, 0, 4)
                // Faces turned further away are dimmer
                facePaint.alpha = (255 * alpha * (0.45f + 0.55f * (-faceDepth[f]).coerceIn(0f, 1f))).toInt().coerceIn(0, 255)
                c.drawBitmap(bmp, mtx, facePaint)
            }
        }
        // Glowing edges
        var i = 0
        for (yy in 0..1) for (zz in 0..1) for (xx in 0..1) {
            turn((xx * 2 - 1).toFloat(), (yy * 2 - 1).toFloat(), (zz * 2 - 1).toFloat(), ang)
            val pr = 3.2f / (3.2f + rot[2])
            px[i] = cx + rot[0] * s * pr; py[i] = cy - rot[1] * s * pr
            i++
        }
        line.strokeWidth = 1.4f * d; line.color = Color.argb((255 * min(1f, alpha * 2.4f)).toInt(), 170, 210, 255)
        val e = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
        var k = 0
        while (k < e.size) { c.drawLine(px[e[k]], py[e[k]], px[e[k + 1]], py[e[k + 1]], line); k += 2 }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = e.y; dragging = true }
            MotionEvent.ACTION_MOVE -> {
                dragY = max(0f, downY - e.y)
                if (!asked && dragY > height * 0.18f) { asked = true; performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); a.requestUnlock() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
