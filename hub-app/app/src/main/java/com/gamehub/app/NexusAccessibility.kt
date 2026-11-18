package com.gamehub.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream

/**
 * NEXUS's helper service (turned on by the user in Accessibility settings). It:
 *  - keeps a small screenshot of the last screen of each app you use (stored only inside NEXUS)
 *  - notices when the phone's recent-apps screen opens (the square button) and shows NEXUS Recent Apps instead
 */
class NexusAccessibility : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var lastSwitcher = 0L
    private val homePkgs = HashSet<String>()

    companion object {
        /** Our own apps, which close completely when you leave them for NEXUS (others keep Android's usual behaviour). */
        val OWN_APPS = setOf("com.zombiedots.app", "com.ashenvow.app", "com.synthpad.app")

        /** Asks one of our own apps to close itself completely, and takes it off Recent Apps. */
        fun closeOwnApp(c: Context, pkg: String) {
            if (pkg !in OWN_APPS) return
            try { c.sendBroadcast(Intent("com.gamehub.action.CLOSE_APP").setPackage(pkg)) } catch (_: Exception) {}
            Recents.remove(c, pkg)
        }

        @Volatile var running = false
        /** The running helper (so the NEXUS lock screen can say when it's ready). */
        @Volatile var instance: NexusAccessibility? = null
        fun snapDir(c: Context) = File(c.filesDir, "snaps").apply { mkdirs() }
        fun snapFile(c: Context, pkg: String) = File(snapDir(c), "$pkg.jpg")
    }

    /**
     * The NEXUS lock screen (if switched on) is put in place while the screen turns OFF, so it's already
     * showing the moment the screen turns on: no flash of the phone's own lock screen.
     * If it somehow isn't there when the screen turns on, it's shown then as a fallback.
     */
    private val screenOn = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = safe { handleScreen(c, i) }
    }

    /**
     * Runs a piece of the helper so that if something ever goes wrong in it, only that piece is skipped:
     * the problem is noted for "Report a problem" in NEXUS settings, and NEXUS (your home screen) keeps running.
     */
    private fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { App.saveHelperError(this, t) }
    }

    private fun handleScreen(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_SCREEN_OFF) leftByScreenOff = true
        if (!LockActivity.enabled(c)) { if (!coverWanted()) dropCover(); return }
        // Never cover a phone call or a ringing call/alarm (the screen also turns off during calls)
        val am = getSystemService(android.media.AudioManager::class.java)
        val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
        if (mode == android.media.AudioManager.MODE_RINGTONE || mode == android.media.AudioManager.MODE_IN_CALL ||
            mode == android.media.AudioManager.MODE_IN_COMMUNICATION) { removeCover(); return }
        when (i.action) {
            Intent.ACTION_SCREEN_OFF -> { otherAppSinceOff = false; leftByScreenOff = true; coverToken++; h.removeCallbacks(safety); cover?.showBlack(); addCover(); showLock(c) }
            Intent.ACTION_SCREEN_ON -> {
                ensureCover()
                val km = getSystemService(KeyguardManager::class.java)
                if (otherAppSinceOff) {
                    // Something else came up while the screen was off (an alarm, a timer, the camera...):
                    // never hide it or push NEXUS over it
                    removeCover()
                } else if (km?.isKeyguardLocked == true) {
                    // Fallback only: normally the lock screen is already in place from screen-off
                    if (!LockActivity.alive) showLock(c)
                    // Safety: the cover never stays more than a moment, whatever happens
                    armSafety(1300)
                } else armSafety(120) // not locked: nothing to hide
            }
        }
    }

    // ------------------------------------------------------------------ wake-up cover
    // A black layer that is created in advance (fully see-through) while the screen is on, and switched
    // to solid when the screen turns off. Switching see-through/solid needs no drawing, so it is already
    // in place the instant the screen wakes: you see black (what the NEXUS lock screen fades in from)
    // instead of a flash of the phone's own lock screen. It ignores touches and is cleared within a second.
    // The same cover hides the phone's own recent-apps screen while NEXUS Recent Apps opens (square button).
    // Once the NEXUS screen underneath is ready, the cover fades away, so it hands over smoothly.

    private var cover: CoverView? = null
    private var coverLp: android.view.WindowManager.LayoutParams? = null

    /** The cover is only kept when something uses it: the NEXUS lock screen, or NEXUS Recent Apps on the square button. */
    private fun coverWanted(): Boolean =
        LockActivity.enabled(this) || (takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this))

    /** Makes sure the (see-through) cover exists. Only call while the screen is on. */
    private fun ensureCover(force: Boolean = false) {
        if (cover != null || (!force && !coverWanted())) return
        try {
            val wm = getSystemService(android.view.WindowManager::class.java) ?: return
            val v = CoverView(this)
            val lp = android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.alpha = 0f
            wm.addView(v, lp)
            cover = v; coverLp = lp
        } catch (_: Throwable) { cover = null; coverLp = null }
    }

    private fun setCover(solid: Boolean) {
        val v = cover ?: return
        val lp = coverLp ?: return
        // Stop any fade in progress
        fadeToken++
        v.animate().cancel()
        if (v.alpha != 1f) v.alpha = 1f
        if ((lp.alpha > 0.5f) == solid) return
        lp.alpha = if (solid) 1f else 0f
        try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    private fun addCover() = setCover(true)
    /** Takes the cover away at once (calls, alarms and other apps never wait). */
    fun removeCover() { coverToken++; h.removeCallbacks(safety); setCover(false); cover?.showBlack() }

    /** Cancels a cover that was about to be switched on. */
    private var coverToken = 0

    /** Lifts the cover with a short fade, so the NEXUS screen underneath appears smoothly. */
    private fun fadeCover() {
        coverToken++
        h.removeCallbacks(safety)
        val v = cover ?: return
        val lp = coverLp ?: return
        if (lp.alpha < 0.5f) { v.showBlack(); return }
        val token = ++fadeToken
        // The fade itself runs on the render thread (no listeners), so it stays smooth even if NEXUS is busy
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        h.postDelayed({
            if (token != fadeToken) return@postDelayed
            lp.alpha = 0f
            try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
            // Back to plain black a moment later, once the window is surely hidden
            h.postDelayed({
                if (token == fadeToken && lp.alpha < 0.5f) { v.animate().cancel(); v.alpha = 1f; v.showBlack() }
            }, 120)
        }, FADE_MS + 20)
    }
    private var fadeToken = 0
    private val FADE_MS = 150L

    /** Whatever happens, the cover is lifted after [ms]. */
    private val safety = Runnable { safe { fadeCover() } }
    private fun armSafety(ms: Long) { h.removeCallbacks(safety); h.postDelayed(safety, ms) }

    /** Called when the lock screen or Recent Apps settings change (main thread). */
    fun refreshCover() { h.post { if (coverWanted()) ensureCover() else dropCover() } }

    /** Takes the cover away completely (lock screen switched off, or the helper stopping). */
    private fun dropCover() {
        val v = cover ?: return
        cover = null; coverLp = null
        try { getSystemService(android.view.WindowManager::class.java)?.removeView(v) } catch (_: Throwable) {}
    }

    /** Called by the NEXUS lock screen once it is lit and in front (the phone's own lock screen is no longer drawn). */
    fun lockReady() { armSafety(50) }

    /** Called by NEXUS Recent Apps once it is drawn and in front: the cover fades away to reveal it
     *  (after the app has finished shrinking into its card). */
    fun switcherReady() {
        val end = cover?.zoomEnd() ?: 0L
        armSafety(maxOf(30L, end - SystemClock.uptimeMillis() + 30L))
    }

    /** The last picture of each app as it was taken, kept in memory for the square-button opening. */
    @Volatile private var lastShot: Pair<String, Bitmap>? = null

    /** The in-memory picture of [pkg], if it's the latest one taken. */
    fun memShot(pkg: String): Bitmap? = lastShot?.let { if (it.first == pkg) it.second else null }

    private fun shotFor(pkg: String): Bitmap? {
        lastShot?.let { if (it.first == pkg) return it.second }
        val f = snapFile(this, pkg)
        if (!f.exists()) return null
        return try { android.graphics.BitmapFactory.decodeFile(f.absolutePath) } catch (_: Throwable) { null }
    }

    /** Another app's screen appeared after the screen went off (alarm, timer, camera, call...). */
    @Volatile private var otherAppSinceOff = false

    private fun showLock(c: Context) {
        try {
            // NO_USER_ACTION: the app that was open isn't told "the user left" (so it doesn't react, e.g. by
            // jumping into picture-in-picture) - the screen just turned off
            startActivity(Intent(c, LockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                SwitcherActivity.noIconSplash(c))
        } catch (_: Exception) {}
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        refreshHomes()
        // Usage counts were kept by an earlier version for the lock screen cube; it no longer needs them
        getSharedPreferences("hub", MODE_PRIVATE).edit().remove("usecount").apply()
        h.postDelayed(tick, 2000)
        ensureCover()
        // Have names and icons ready for Recent Apps
        AppCache.warm(this, Recents.list(this).map { it.first })
        val f = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOn, f)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOn) } catch (_: Exception) {}
        dropCover()
        instance = null
        running = false
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun refreshHomes() {
        homePkgs.clear()
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (r in packageManager.queryIntentActivities(i, 0)) homePkgs.add(r.activityInfo.packageName)
    }

    /** A "real" app worth remembering (not the keyboard, system bars, home screens or NEXUS). */
    private fun isRealApp(pkg: String): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return false
        if (pkg in homePkgs) return false
        if ("inputmethod" in pkg || "keyboard" in pkg) return false
        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) = safe { handleEvent(e) }

    private fun handleEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        // Another app put up a screen (alarm, timer, camera, call...): the wake-up cover must never hide it
        if (pkg != packageName && pkg != "com.android.systemui" && pkg != "android" && pkg !in homePkgs &&
            "inputmethod" !in pkg && "keyboard" !in pkg) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            if (pm?.isInteractive == true) removeCover() else otherAppSinceOff = true
        }
        // The phone's own recent-apps screen opened (square button): swap in NEXUS Recent Apps
        // Only the system's dedicated recent-apps screen counts (e.g. ...quickstep.RecentsActivity or
        // ...systemui.recents.RecentsActivity). Never a launcher's home screen, never another app.
        val systemScreen = pkg in homePkgs || pkg == "com.android.systemui"
        val isRecents = systemScreen && pkg != packageName &&
            cls.contains("Recents", ignoreCase = true) && !cls.contains("Launcher", ignoreCase = true)
        if (isRecents && takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSwitcher > 800) {
                lastSwitcher = now
                // Cover the phone's own screen straight away. If you were in an app, the cover starts with that
                // app's last picture filling the screen and shrinks it into its card over the NEXUS backdrop;
                // NEXUS Recent Apps opens underneath with that card in the same spot, and the cover fades away.
                // Never longer than a moment, and it never takes touches.
                val from = current
                val shot = if (from != null && from == Recents.list(this).firstOrNull()?.first) shotFor(from) else null
                // That app is no longer on screen: don't let a scheduled picture catch this transition instead
                current = null
                ensureCover(force = true)
                val v = cover
                if (v != null) {
                    v.showRecents(shot)
                    // Switch the cover on once that first picture is drawn (so it never shows a stale frame)
                    val token = ++coverToken
                    v.postOnAnimation { v.postOnAnimation { if (token == coverToken) { addCover(); v.beginZoom() } } }
                }
                armSafety(1100)
                try {
                    startActivity(Intent(this, SwitcherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("fromSquare", true).putExtra("zoom", shot != null), SwitcherActivity.noIconSplash(this))
                } catch (_: Exception) { removeCover() }
            }
            return
        }
        if (isRealApp(pkg)) {
            // Only an app you actually opened counts as open: one of its full screens (not a pop-up or a dialog),
            // shown while the phone is unlocked (not an alarm or a call ringing over the lock screen),
            // and still in front a moment later (not something that flashed up and went).
            val pm = getSystemService(PowerManager::class.java)
            val km = getSystemService(KeyguardManager::class.java)
            if (pm?.isInteractive != true || km?.isKeyguardLocked == true || !isAppScreen(pkg, cls)) return
            lastApp = pkg; leftByScreenOff = false
            if (pkg == pendingClose) pendingClose = null // went straight back into it: keep it
            if (pkg != current) {
                current = pkg
                val token = ++openToken
                h.postDelayed({
                    safe {
                        if (token == openToken && current == pkg && pm?.isInteractive == true && km?.isKeyguardLocked != true) {
                            Recents.touch(this, pkg)
                            AppCache.warm(this, listOf(pkg))
                            snap()
                        }
                    }
                }, 1500)
            }
        } else if (pkg in homePkgs || pkg == packageName) {
            current = null; openToken++
            // Back on the NEXUS home screen straight from one of our own apps: that app closes completely
            // (not when the screen just went off and came back, and not via the square button's Recent Apps)
            if (pkg == packageName && cls == MainActivity::class.java.name) {
                val app = lastApp
                lastApp = null
                if (app != null && app in OWN_APPS && !leftByScreenOff) {
                    // A moment later, once it has fully left the screen
                    pendingClose = app
                    h.postDelayed({ safe { if (pendingClose == app) { pendingClose = null; closeOwnApp(this, app) } } }, 800)
                }
            }
        }
    }

    /** One of our own apps about to be closed (cancelled if you go straight back into it). */
    private var pendingClose: String? = null
    /** The last app that was really in front (our own screens don't count). */
    private var lastApp: String? = null
    /** The screen went off since then (unlocking can bring you to NEXUS, which isn't "leaving" the app). */
    @Volatile private var leftByScreenOff = false

    /** Invalidates a pending "this app was opened" when something else comes to the front first. */
    private var openToken = 0
    private val screenCache = HashMap<String, Boolean>()

    /**
     * Is this one of [pkg]'s own screens, rather than a dialog, pop-up, menu or notification?
     * Most apps report the screen's name; some only report a general view type (for example a FrameLayout),
     * so anything that isn't clearly a pop-up counts as the app's screen.
     */
    private fun isAppScreen(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        screenCache[key]?.let { return it }
        val activity = cls.isNotEmpty() && try { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0); true } catch (_: Exception) { false }
        val popup = POPUP_WORDS.any { cls.contains(it, ignoreCase = true) }
        val ok = activity || !popup
        if (screenCache.size > 400) screenCache.clear()
        screenCache[key] = ok
        return ok
    }
    private val POPUP_WORDS = listOf("Dialog", "Popup", "PopUp", "Toast", "Menu", "Notification", "Tooltip", "Snackbar", "BottomSheet")

    private fun takeOverRecents() = getSharedPreferences("hub", MODE_PRIVATE).getBoolean("replaceRecents", true)

    /** Every couple of seconds, refresh the current app's screenshot. */
    private val tick = object : Runnable {
        override fun run() {
            safe { snap() }
            h.postDelayed(this, 3000)
        }
    }

    private fun snap() {
        val pkg = current ?: return
        if (Build.VERSION.SDK_INT < 30) return
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm?.isInteractive != true || km?.isKeyguardLocked == true) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val buf = r.hardwareBuffer
                    Thread {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buf, r.colorSpace)
                            if (hw != null && current == pkg) {
                                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                val w = 400; val hh = (soft.height * (w.toFloat() / soft.width)).toInt()
                                val small = Bitmap.createScaledBitmap(soft, w, hh, true)
                                val tmp = File(snapDir(this@NexusAccessibility), "$pkg.tmp")
                                FileOutputStream(tmp).use { small.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                                tmp.renameTo(snapFile(this@NexusAccessibility, pkg))
                                soft.recycle()
                                lastShot = pkg to small // kept for the square-button opening (not recycled)
                            }
                        } catch (_: Throwable) {
                        } finally { buf.close() }
                    }.start()
                }
                override fun onFailure(code: Int) {}
            })
        } catch (_: Throwable) {}
    }
}

/** The order apps were last used in (most recent first), kept in NEXUS's settings. */
object Recents {
    fun list(c: Context): List<Pair<String, Long>> =
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("recents", "")!!
            .split(",").mapNotNull { e -> val p = e.split("|"); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null }

    fun touch(c: Context, pkg: String) {
        val l = list(c).filter { it.first != pkg }.toMutableList()
        l.add(0, pkg to System.currentTimeMillis())
        save(c, l.take(20))
    }

    fun remove(c: Context, pkg: String) {
        save(c, list(c).filter { it.first != pkg })
        NexusAccessibility.snapFile(c, pkg).delete()
    }

    fun clear(c: Context) {
        for ((p, _) in list(c)) NexusAccessibility.snapFile(c, p).delete()
        save(c, emptyList())
    }

    private fun save(c: Context, l: List<Pair<String, Long>>) =
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("recents", l.joinToString(",") { "${it.first}|${it.second}" }).apply()

    /** Is NEXUS's helper switched on in Accessibility settings? */
    fun serviceOn(c: Context): Boolean {
        val s = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("${c.packageName}/") && s.contains("NexusAccessibility")
    }
}
