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
import android.graphics.RectF
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
    /** The swipe up asked Android to unlock, and it hasn't answered yet. */
    private var unlockAsked = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Unlocked another way (fingerprint, face, or opening a notification): go straight on
            // (If the swipe up asked for the unlock, it counts as that, whichever arrives first)
            if (i.action == Intent.ACTION_USER_PRESENT) afterUnlock(byNexus = unlockAsked)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        LockTrace.log(this, "  lock screen created")
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        // Full screen from the very first frame: the lock screen always fills the whole screen (camera cut-out
        // included) and the phone's status bar and buttons are hidden before it's ever drawn. So nothing shows
        // and then disappears, and nothing moves (it used to start smaller, under the bars, then jump down
        // when they went away).
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        view = LockView(this)
        // A black layer on top, see-through until the lock screen is left alone (then it fades it out)
        shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        setContentView(android.widget.FrameLayout(this).apply { addView(view); addView(shade) })
        // The lock screen paints the whole screen itself: no need to paint the window under it too
        window.setBackgroundDrawable(null)
        hideBars()
        val f = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(unlocked, f)
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        LockTrace.log(this, "  lock screen in front: phoneLocked=" + getSystemService(KeyguardManager::class.java)?.isKeyguardLocked +
            " screenOn=" + getSystemService(android.os.PowerManager::class.java)?.isInteractive)
        // While the screen is off the phone may take a moment to lock: stay ready rather than closing early
        if (!stillNeeded()) return
        showing = true
        // Left alone, it fades out and the screen turns off (a fresh count each time it comes on)
        resetShade()
        lastUse = android.os.SystemClock.uptimeMillis(); seenItems = NexusNotifications.items; sleepFailed = false
        view.removeCallbacks(idleCheck); view.postDelayed(idleCheck, 250)
        NexusNotifications.refresh()
        view.restart()
        // The screen is on: hand the wake-up cover a picture of this lock screen right away, so it shows
        // the moment the screen lights up instead of black while Android finishes handing over the screen
        if (getSystemService(android.os.PowerManager::class.java)?.isInteractive == true) {
            val pic = view.renderPreview()
            if (pic != null && NexusAccessibility.instance?.lockPreview(pic) == true) view.previewShown()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        LockTrace.log(this, "  lock screen focus=" + hasFocus)
        if (hasFocus) { hideBars(); stillNeeded() }
    }

    /** No status bar and no phone buttons on the lock screen (a swipe from the edge still peeks at them). */
    private fun hideBars() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                window.insetsController?.let {
                    it.hide(android.view.WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        } catch (_: Exception) {}
    }

    /**
     * Steps aside only if the screen is ON and the phone is NOT locked (e.g. woken again within
     * a few seconds of turning it off). Returns true if the lock screen should stay.
     */
    private fun stillNeeded(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (km?.isKeyguardLocked != true && pm?.isInteractive == true) {
            LockTrace.log(this, "  lock screen closed itself: the phone isn't locked")
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return false
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        view.removeCallbacks(idleCheck)
    }

    override fun onStop() {
        super.onStop()
        resetShade()
        showing = false
        LockTrace.log(this, "  lock screen hidden")
    }

    override fun onDestroy() {
        alive = false
        LockTrace.log(this, "  lock screen closed")
        try { unregisterReceiver(unlocked) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ---- Left alone: fade out and turn the screen off, like the phone's own lock screen does. (Shown over the
    // phone's lock screen, the NEXUS lock screen would otherwise stay on for the phone's full screen timeout.)
    // Only while it's really being looked at: lit, in front, and not in the middle of unlocking or opening a
    // notification. Any touch or button press, or a new notification, starts the count again.

    private lateinit var shade: View
    private var lastUse = 0L
    private var fading = false
    /** Turning the screen off didn't work (helper not running): don't keep trying until it's touched again. */
    private var sleepFailed = false
    private var seenItems: Any? = null
    private val idleCheck = object : Runnable {
        override fun run() { try { checkIdle() } catch (_: Exception) {}; view.postDelayed(this, 250) }
    }

    private fun checkIdle() {
        val now = android.os.SystemClock.uptimeMillis()
        val items = NexusNotifications.items
        if (items !== seenItems) { seenItems = items; lastUse = now; if (fading) unfade() }
        val timeout = offAfterSec(this) * 1000L
        if (timeout <= 0 || sleepFailed || !watching()) { lastUse = now; if (fading) unfade(); return }
        if (!fading && now - lastUse >= timeout - FADE_MS) {
            fading = true
            shade.animate().cancel()
            shade.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction { if (fading) sleepNow() }.start()
        }
    }

    /** Is the lock screen lit, in front and simply being looked at? */
    private fun watching(): Boolean =
        showing && !isFinishing && !unlockAsked && pendingOpen == null && hasWindowFocus() &&
            getSystemService(android.os.PowerManager::class.java)?.isInteractive == true &&
            window.decorView.display?.state == android.view.Display.STATE_ON

    private fun sleepNow() {
        if (!watching()) { unfade(); return }
        if (NexusAccessibility.instance?.sleepScreen() == true) {
            LockTrace.log(this, "  left alone for ${offAfterSec(this)}s: faded out and turned the screen off")
        } else {
            LockTrace.log(this, "  left alone, but the screen couldn't be turned off (the helper isn't running): it stays on")
            sleepFailed = true; unfade()
        }
    }

    private fun unfade() {
        fading = false
        shade.animate().cancel()
        shade.animate().alpha(0f).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    private fun resetShade() {
        if (!::shade.isInitialized) return
        fading = false; shade.animate().cancel(); shade.alpha = 0f
    }

    private fun noteUse() { lastUse = android.os.SystemClock.uptimeMillis(); sleepFailed = false; if (fading) unfade() }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { noteUse(); return super.dispatchTouchEvent(ev) }
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean { noteUse(); return super.dispatchKeyEvent(event) }

    /** Back does nothing here: this is a lock screen. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    /** A notification tapped on the lock screen: it opens once the phone has been unlocked. */
    private var pendingOpen: NexusNotifications.Item? = null

    fun openNotification(item: NexusNotifications.Item) {
        pendingOpen = item
        requestUnlock()
    }

    /** Opens what a notification leads to (as if it had been tapped in the notification shade). */
    private fun sendNotification(item: NexusNotifications.Item) {
        val pi = item.intent
        var ok = false
        if (pi != null) {
            ok = try {
                val opts = if (android.os.Build.VERSION.SDK_INT >= 34)
                    @Suppress("DEPRECATION") android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle()
                else null
                pi.send(this, 0, null, null, null, null, opts); true
            } catch (_: Exception) { false }
        }
        if (!ok) {
            // No way in from the notification itself: just open the app
            try { packageManager.getLaunchIntentForPackage(item.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (_: Exception) {}
        }
        if (item.autoCancel) NexusNotifications.dismiss(item.key)
    }

    /** Swipe up: ask the phone to unlock (its own secure PIN / pattern / fingerprint screen). */
    fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java) ?: return finish()
        if (!km.isKeyguardLocked) { afterUnlock(byNexus = true); return }
        unlockAsked = true
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { afterUnlock(byNexus = true) }
            override fun onDismissCancelled() { unlockAsked = false; pendingOpen = null; view.reset() }
            override fun onDismissError() { unlockAsked = false; pendingOpen = null; view.reset() }
        })
    }

    /** [byNexus]: unlocked by swiping up on the NEXUS lock screen (rather than from the notification shade etc.). */
    private fun afterUnlock(byNexus: Boolean) {
        if (isFinishing) return
        // A notification was tapped here: open it (not the NEXUS home screen)
        pendingOpen?.let { item ->
            pendingOpen = null
            LockTrace.log(this, "  unlocked: opening a notification from ${item.app}")
            sendNotification(item)
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        // Unlocked while the lock screen wasn't the window in use (the notification shade was down, or a
        // notification was tapped): an app is opening, so NEXUS gets out of the way instead of landing on top of it
        val appOpening = !byNexus && !hasWindowFocus()
        LockTrace.log(this, "  unlocked" + if (appOpening) " from the notification shade: letting the app open" else "")
        if (appOpening) {
            finish()
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
            return
        }
        // Back to where you were, as on the phone's own lock screen: the lock screen just steps away, showing
        // the app you were using (or the NEXUS home screen, if that's where you were). Or, if set that way,
        // always the NEXUS home screen.
        if (unlockToHome(this) && HomeMode.isOn(this) && HomeMode.isDefault(this)) {
            val i = packageManager.getLaunchIntentForPackage(packageName)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("fromApp", true)
                startActivity(i, Transitions.rise(this))
            }
        }
        LockTrace.log(this, "  unlocked: " + if (unlockToHome(this)) "to the NEXUS home screen" else "back to where you were")
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

        /** How long the lock screen takes to fade up once it's on screen (seconds). */
        const val INTRO_LEN = 0.42f

        /** The fade at the end, before the screen turns off (milliseconds). */
        const val FADE_MS = 800L

        /** After unlocking: always the NEXUS home screen (true), or back to where you were (false, the default). */
        fun unlockToHome(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("unlockToHome", false)

        /** Seconds left alone before it fades out and the screen turns off (0 = the phone's own screen timeout). */
        fun offAfterSec(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getInt("lockOffSec", 8)

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
    private val thin: Typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    // Clock and date text, worked out once a second rather than every frame
    private var clockSec = -1L
    private var clockText = ""
    private var dateText = ""
    private fun updateClock() {
        val now = System.currentTimeMillis()
        if (now / 1000 == clockSec) return
        clockSec = now / 1000
        val date = Date(now)
        clockText = timeFmt.format(date); dateText = dateFmt.format(date).uppercase()
    }
    // Shaders made once and placed by moving the canvas (no new objects while drawing)
    // ---- Notifications (cards under the clock; the cube moves down and shrinks a little to make room)
    private class Card(val item: NexusNotifications.Item, val head: String, val title: String, val body: String, val icon: android.graphics.drawable.Drawable?)
    private var cards: List<Card> = emptyList()
    private var cardsFrom: List<NexusNotifications.Item>? = null
    private var cardsW = -1f
    private var cardsMinute = -1L
    private val cardRects = ArrayList<RectF>()
    private val cardPool = List(4) { RectF() }
    private val cardR = RectF()
    private val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
    private var cubeY = -1f
    private var cubeK = 1f
    private var downX = 0f
    private var downAt = 0L
    private var pressed = -1

    /** Builds the cards' text (shortened to fit) when the notifications, the width or the minute change. */
    private fun buildCards(w: Float) {
        val items = NexusNotifications.items
        val minute = System.currentTimeMillis() / 60_000
        if (items === cardsFrom && w == cardsW && minute == cardsMinute) return
        cardsFrom = items; cardsW = w; cardsMinute = minute
        val textW = w - 32 * d - 16 * d - 30 * d - 12 * d - 14 * d
        cards = items.map { it2 ->
            val ago = ((System.currentTimeMillis() - it2.time) / 60_000).coerceAtLeast(0)
            val whenTxt = when { ago < 1 -> "now"; ago < 60 -> "${ago}m"; ago < 24 * 60 -> "${ago / 60}h"; else -> "${ago / (24 * 60)}d" }
            tp.typeface = light; tp.letterSpacing = 0.08f; tp.textSize = 11 * d
            val head = android.text.TextUtils.ellipsize(it2.app.uppercase() + (if (it2.count > 1) "  ·  ${it2.count}" else "") + "  ·  " + whenTxt, tp, textW, android.text.TextUtils.TruncateAt.END).toString()
            tp.letterSpacing = 0.01f; tp.textSize = 14.5f * d
            val title = android.text.TextUtils.ellipsize(it2.title, tp, textW, android.text.TextUtils.TruncateAt.END).toString()
            tp.textSize = 13 * d
            val body = android.text.TextUtils.ellipsize(it2.text, tp, textW, android.text.TextUtils.TruncateAt.END).toString()
            Card(it2, head, title, body, AppCache.get(context, it2.pkg)?.icon)
        }
    }

    /** Draws the notification cards from [top]; returns where they end (or [top] if there are none). */
    private fun drawCards(c: Canvas, w: Float, top: Float, maxBottom: Float, fade: Float): Float {
        cardRects.clear()
        buildCards(w)
        if (cards.isEmpty()) return top
        val ch = 64 * d; val gap = 8 * d
        val fit = (((maxBottom - top) + gap) / (ch + gap)).toInt().coerceIn(1, 4)
        val shown = min(fit, cards.size)
        var y = top
        for (i in 0 until shown) {
            val cd = cards[i]
            cardR.set(16 * d, y, w - 16 * d, y + ch)
            cardRects.add(cardPool[i].apply { set(cardR) })
            val press = if (pressed == i) 1.6f else 1f
            fill.color = Color.argb((min(255f, 64 * press) * fade).toInt(), 30, 60, 140)
            c.drawRoundRect(cardR, 16 * d, 16 * d, fill)
            line.strokeWidth = 1f * d; line.color = Color.argb((90 * fade).toInt(), 140, 190, 255)
            c.drawRoundRect(cardR, 16 * d, 16 * d, line)
            val ix = cardR.left + 14 * d; val isz = 30 * d; val iy = cardR.centerY() - isz / 2
            cd.icon?.let { ic -> ic.setBounds(ix.toInt(), iy.toInt(), (ix + isz).toInt(), (iy + isz).toInt()); ic.alpha = (255 * fade).toInt(); ic.draw(c); ic.alpha = 255 }
            val tx = ix + isz + 12 * d
            text.textAlign = Paint.Align.LEFT; text.typeface = light
            text.textSize = 11 * d; text.letterSpacing = 0.08f; text.color = Color.argb((150 * fade).toInt(), 170, 200, 245)
            val hasTitle = cd.title.isNotEmpty()
            val hy = if (hasTitle) cardR.top + 19 * d else cardR.top + 25 * d
            c.drawText(cd.head, tx, hy, text)
            text.letterSpacing = 0.01f
            if (hasTitle) {
                text.textSize = 14.5f * d; text.color = Color.argb((240 * fade).toInt(), 240, 245, 255)
                c.drawText(cd.title, tx, cardR.top + 38 * d, text)
                text.textSize = 13 * d; text.color = Color.argb((175 * fade).toInt(), 205, 220, 245)
                c.drawText(cd.body, tx, cardR.top + 55 * d, text)
            } else {
                text.textSize = 14 * d; text.color = Color.argb((220 * fade).toInt(), 225, 235, 255)
                c.drawText(cd.body, tx, cardR.top + 46 * d, text)
            }
            y += ch + gap
        }
        if (cards.size > shown) {
            text.textAlign = Paint.Align.CENTER; text.textSize = 11.5f * d; text.letterSpacing = 0.12f
            text.color = Color.argb((140 * fade).toInt(), 170, 200, 245)
            c.drawText("+ ${cards.size - shown} more", w / 2, y + 10 * d, text)
            y += 18 * d
        }
        text.textAlign = Paint.Align.CENTER
        return y
    }

    private val glowShader = RadialGradient(0f, 0f, 1f, Color.rgb(60, 120, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val ruleShader = LinearGradient(-1f, 0f, 1f, 0f, intArrayOf(Color.TRANSPARENT, Color.rgb(140, 190, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    private val edges = intArrayOf(0, 1, 1, 3, 3, 2, 2, 0, 4, 5, 5, 7, 7, 6, 6, 4, 0, 4, 1, 5, 2, 6, 3, 7)
    private val corners = arrayOf(floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, -1f))
    private val sortedFaces = arrayOf(0, 1, 2, 3, 4, 5)
    private var bg: Bitmap? = null

    fun restart() { t = 0f; lastNs = 0L; reset(); loadFaces(); notified = false; previewed = false; invalidate() }
    private var notified = false

    /**
     * The wake-up cover is already showing this lock screen (a picture of it, made the moment the screen came
     * on), so there's no fade-in here: the same frame stays put until the cover lifts, then it carries on.
     */
    private var previewed = false
    private var holdUntil = 0L
    /** Making that picture right now (no time passes, nothing else happens). */
    private var previewing = false

    /** Draws the lock screen once into a picture for the wake-up cover. */
    fun renderPreview(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        return try {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            previewing = true
            drawFrame(Canvas(b))
            b
        } catch (_: Throwable) { null } finally { previewing = false }
    }

    /** The cover took the picture: skip the fade-in, and hold the picture's frame until the cover lifts. */
    fun previewShown() { previewed = true; holdUntil = android.os.SystemClock.uptimeMillis() + 1500; lastNs = 0L }
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
        Bg.thread {
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
        // Time stands still while making the cover's picture, and while the cover still shows that picture
        val hold = previewing || (previewed && !notified && android.os.SystemClock.uptimeMillis() < holdUntil)
        val dt = if (lastNs == 0L || hold) 0f else min(0.05f, (now - lastNs) / 1e9f)
        if (!previewing) lastNs = now
        t += dt
        val w = width.toFloat(); val h = height.toFloat()
        // Quick, eased reveal once it's on screen (ease-out: most of it shows in the first moment);
        // none when the cover already showed it
        val introK = if (previewing || previewed) 1f else min(1f, t / LockActivity.INTRO_LEN)
        val intro = 1f - (1f - introK) * (1f - introK) * (1f - introK)
        val lift = -dragY // content follows the finger up
        c.drawBitmap(backdrop(), 0f, 0f, null)
        // Lift the black wake-up cover only once the NEXUS lock screen is really what's on screen:
        // the screen is lit, and NEXUS has the focus (it doesn't while the phone's own lock screen is
        // still drawn on top). The fade-in then starts from black, so the switch is seamless.
        if (!previewing && !notified && litAndInFront()) {
            notified = true
            if (!previewed) t = 0f
            lastNs = now
            NexusAccessibility.instance?.lockReady()
        }

        val fade = (1f - dragY / (h * 0.35f)).coerceIn(0f, 1f) * intro

        updateClock()
        // NEXUS wordmark at the very top
        text.typeface = light
        text.textSize = 12 * d; text.letterSpacing = 0.6f
        text.color = Color.argb((110 * fade).toInt(), 170, 200, 255)
        c.drawText("NEXUS", w / 2 + 4 * d, 56 * d + lift * 0.2f, text)

        // Clock, date and battery: top third, clear of everything else
        val clockY = h * 0.24f + lift * 0.5f
        text.typeface = thin
        text.textSize = 88 * d; text.letterSpacing = 0.02f
        text.color = Color.argb((255 * fade).toInt(), 240, 246, 255)
        text.setShadowLayer(16 * d, 0f, 0f, Color.argb((150 * fade).toInt(), 60, 130, 255))
        c.drawText(clockText, w / 2, clockY, text)
        text.setShadowLayer(0f, 0f, 0f, 0)
        text.typeface = light
        text.textSize = 15 * d; text.letterSpacing = 0.14f
        text.color = Color.argb((210 * fade).toInt(), 190, 210, 245)
        c.drawText(dateText, w / 2, clockY + 40 * d, text)
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

        // Notifications, under the clock (only if NEXUS has notification access and you've allowed them here)
        val handleY = h - 70 * d
        val cardsTop = clockY + 92 * d + lift * 0.4f
        val cardsEnd = drawCards(c, w, cardsTop, handleY - 170 * d, fade)
        val hasCards = cardsEnd > cardsTop
        // The cube: in its usual place, or lower and a little smaller when there are notifications
        val cubeTarget = if (hasCards) (cardsEnd + handleY) / 2f - 6 * d else h * 0.6f
        val kTarget = if (hasCards) 0.72f else 1f
        if (cubeY < 0f) { cubeY = cubeTarget; cubeK = kTarget }
        val ease = min(1f, dt * 6f)
        cubeY += (cubeTarget - cubeY) * ease; cubeK += (kTarget - cubeK) * ease

        // A smaller glass cube turning slowly in its own space below the text
        val cx = w / 2; val cy = cubeY + lift * 0.3f
        val s = min(w, h) * 0.16f * cubeK * (0.85f + 0.15f * intro)
        c.save(); c.translate(cx, cy); c.scale(s * 3.2f, s * 3.2f)
        fill.color = Color.WHITE; fill.alpha = (80 * intro).toInt(); fill.shader = glowShader
        c.drawCircle(0f, 0f, 1f, fill); fill.shader = null
        c.restore()
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
        c.save(); c.translate(w / 2, 0f); c.scale(w * 0.2f, 1f)
        fill.color = Color.WHITE; fill.alpha = (150 * fade).toInt(); fill.shader = ruleShader
        c.drawRect(-1f, by + 22 * d, 1f, by + 23.2f * d, fill); fill.shader = null
        c.restore()

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


    private val faceDepth = FloatArray(6)

    private fun cube(c: Canvas, cx: Float, cy: Float, s: Float, ang: Float, alpha: Float) {
        // Pictures on the faces that face you (far ones first), drawn in perspective
        val pics = faces
        if (pics.isNotEmpty()) {
            for (f in 0 until 6) { val fd = faceDefs[f]; turn(fd[0], fd[1], fd[2], ang); faceDepth[f] = rot[2] }
            // Far faces first (sorted in place: no new lists while drawing)
            for (x in 0 until 6) sortedFaces[x] = x
            for (x in 1 until 6) { val v = sortedFaces[x]; var y = x - 1; while (y >= 0 && faceDepth[sortedFaces[y]] < faceDepth[v]) { sortedFaces[y + 1] = sortedFaces[y]; y-- }; sortedFaces[y + 1] = v }
            for (f in sortedFaces) {
                if (faceDepth[f] > -0.05f) continue // facing away
                val fd = faceDefs[f]
                val nx = fd[0]; val ny = fd[1]; val nz = fd[2]; val ux = fd[3]; val uy = fd[4]; val uz = fd[5]
                // right = n × u
                val rx = ny * uz - nz * uy; val ry = nz * ux - nx * uz; val rz = nx * uy - ny * ux
                for (k in 0 until 4) {
                    val cr = corners[k]
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
        val e = edges
        var k = 0
        while (k < e.size) { c.drawLine(px[e[k]], py[e[k]], px[e[k + 1]], py[e[k + 1]], line); k += 2 }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = e.y; downX = e.x; downAt = e.eventTime; dragging = true
                pressed = cardRects.indexOfFirst { it.contains(e.x, e.y) }
            }
            MotionEvent.ACTION_MOVE -> {
                dragY = max(0f, downY - e.y)
                if (kotlin.math.abs(e.y - downY) > 10 * d || kotlin.math.abs(e.x - downX) > 10 * d) pressed = -1
                if (!asked && dragY > height * 0.18f) { asked = true; performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY); a.requestUnlock() }
            }
            MotionEvent.ACTION_UP -> {
                dragging = false
                val tapped = pressed
                pressed = -1
                // A tap on a notification: unlock with the phone's own PIN / fingerprint, then it opens
                if (!asked && tapped >= 0 && e.eventTime - downAt < 600) {
                    cards.getOrNull(tapped)?.let { cd ->
                        asked = true
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        a.openNotification(cd.item)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; pressed = -1 }
        }
        return true
    }
}
