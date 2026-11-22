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
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/**
 * NEXUS's helper service (turned on by the user in Accessibility settings). It:
 *  - keeps a small screenshot of the last screen of each app you use (stored only inside NEXUS)
 *  - notices when the phone's recent-apps screen opens (the square button) and shows NEXUS Recent Apps instead
 *  - if switched on, shows NEXUS's Back / Home / Recent buttons in place of the phone's own while you use apps
 */
class NexusAccessibility : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    /** The screen (activity) of [current] that's showing. */
    private var currentCls: String? = null
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

        /**
         * From NEXUS Recent Apps: the phone's own recent apps screen, on purpose (NEXUS doesn't swap it out
         * that time). NEXUS Recent Apps goes back to the NEXUS home screen first and the phone's screen opens
         * from there. (Opened straight over NEXUS Recent Apps, the phone's recent-apps app, Quickstep, could
         * crash: NEXUS Recent Apps closes itself as it's left, in the middle of Quickstep's opening animation.)
         * Returns false if the helper isn't running.
         */
        fun openPhoneRecentsViaHome(): Boolean = instance?.let { it.preparePhoneRecents(); true } ?: false

        /**
         * Apps swiped away in NEXUS Recent Apps, to be closed fully (Android only lets the phone's own recent-apps
         * screen do that, so NEXUS presses Close there for you, next time the NEXUS home screen shows).
         * [all] = Clear all. Returns false if that can't happen (helper not running, or switched off).
         */
        fun closeFully(pkgs: List<String>, all: Boolean): Boolean = instance?.enqueueClose(pkgs, all) ?: false

        /**
         * You're heading to the NEXUS home screen from Recent Apps or with the Home button: apps waiting to be
         * closed fully are closed when it shows. (Not when NEXUS appears after unlocking, or for any other reason,
         * so it never gets in the way of an app you're opening, e.g. from a notification.)
         */
        fun closeWhenHome() { instance?.beginClose(returnToSwitcher = false) }

        /**
         * Close the waiting apps right now: the closing cube comes up at once (nothing of NEXUS shows first);
         * afterwards it's back to NEXUS Recent Apps ([returnToSwitcher], if apps are left there) or the NEXUS
         * home screen. Returns true if it started (the caller then heads to the NEXUS home screen underneath).
         */
        fun closeNow(returnToSwitcher: Boolean): Boolean = instance?.beginClose(returnToSwitcher) ?: false
        /** NEXUS's opening fade is up right now (the home screen skips its own fade-in meanwhile). */
        val veiling: Boolean get() = (instance?.veilKind ?: 0) != 0
        /**
         * NEXUS's cover is hiding the screen right now (a screen change, or apps being closed): screens underneath
         * pause their animation meanwhile, so the phone puts everything into the app opening or closing.
         */
        val coverBusy: Boolean get() = instance?.let { it.veilKind != 0 || it.closeJob != null } ?: false
        /** Recent Apps (NEXUS's layer) is up. */
        val recentsShowing: Boolean get() = instance?.recents?.showing == true
        /** The home screen is hidden behind NEXUS's cover or Recent Apps right now (it pauses its animation). */
        val homeHidden: Boolean get() = coverBusy || recentsShowing
        /** Opens Recent Apps in NEXUS's layer (false: not possible now, e.g. with TalkBack). */
        fun showRecents(): Boolean = instance?.let { !it.talkBackOn() && it.recents.show() } ?: false
        /** The phone's own Home was pressed while Recent Apps was up: it fades away. */
        fun hideRecents() { instance?.recents?.let { if (it.showing && !it.gone()) it.hide(true) } }

        /** The colour of the lock screen's bottom edge (where the phone's buttons would be). */
        val LOCK_BOTTOM = android.graphics.Color.rgb(1, 2, 8)
        /** How tall the strip over the phone's buttons is on the lock screen. */
        fun lockStripPx(c: Context): Int {
            val id = c.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            val sys = if (id != 0) try { c.resources.getDimensionPixelSize(id) } catch (_: Exception) { 0 } else 0
            return maxOf(NavBar.heightPx(c), sys)
        }

        /** Kinds of screen change NEXUS's quick fade covers. */
        const val FADE_APP = 1
        const val FADE_HOME = 2
        const val FADE_RECENTS = 3
        const val FADE_BACK = 4
        const val FADE_NEXT = 5
        /** Never longer than this before the fade lifts, however long an app takes to open. */
        const val VEIL_MAX = 900L
        /** NEXUS is closing apps under its cover right now (the home screen skips its fade-in meanwhile). */
        val busyClosing: Boolean get() = instance?.closeJob != null

        /** NEXUS's buttons in place of the phone's own while you use other apps (Settings; on unless switched off). */
        fun appButtonsOn(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("appButtons", true)
        fun setAppButtons(c: Context, on: Boolean) {
            c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("appButtons", on).apply()
            instance?.refreshAppButtons()
        }
        /** Is the phone set to 3-button navigation (not gestures)? */
        fun threeButtonNav(c: Context): Boolean =
            try { android.provider.Settings.Secure.getInt(c.contentResolver, "navigation_mode", 0) == 0 } catch (_: Exception) { true }

        /** Is closing apps fully switched on (NEXUS settings → Recent apps)? */
        fun fullCloseOn(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("fullClose", true)

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
    /** NEXUS Recent Apps in NEXUS's own layer, over the app you're in. */
    val recents by lazy { RecentsOverlay(this) }
    /** For NEXUS's own parts (Recent Apps layer): runs [block], noting a problem instead of stopping the helper. */
    fun safeRun(block: () -> Unit) = safe(block)
    /** The app window in front (its id), and its app. */
    fun topAppWindowId(): Int = topAppWindow()
    fun topAppPackage(): String? = try {
        windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }
            ?.root?.packageName?.toString()
    } catch (_: Throwable) { null }

    private fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { App.saveHelperError(this, t) }
    }

    private fun handleScreen(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_USER_PRESENT) { h.postDelayed({ safe { recheckFront() } }, 600); return }
        if (i.action == Intent.ACTION_SCREEN_OFF) { hideButtons("screen off"); veilKind = 0; h.removeCallbacks(veilRun); recents.hide(false) } else queueButtons(0)
        if (i.action == Intent.ACTION_SCREEN_OFF) leftByScreenOff = true
        closeJob?.let { endClose(it, "the screen turned off", goHome = false) }
        val kmT = getSystemService(KeyguardManager::class.java)
        LockTrace.log(this, "screen " + (if (i.action == Intent.ACTION_SCREEN_OFF) "OFF" else "ON") +
            ": lockScreen=" + LockActivity.enabled(c) + " phoneLocked=" + kmT?.isKeyguardLocked +
            " lockAlive=" + LockActivity.alive + " lockShowing=" + LockActivity.showing + " otherApp=" + otherAppSinceOff +
            " front=" + (current ?: "-") + " audioMode=" + (getSystemService(android.media.AudioManager::class.java)?.mode ?: -1))
        if (!LockActivity.enabled(c)) { if (!coverWanted()) dropCover(); return }
        // With TalkBack on, the phone's own lock screen is used: it's the one that works with TalkBack
        if (talkBackOn()) { LockTrace.log(this, "  TalkBack is on: NEXUS lock screen left out (the phone's own lock screen works with TalkBack)"); removeCover(); return }
        // Never cover a phone call or a ringing call/alarm (the screen also turns off during calls).
        // An internet call (Discord, WhatsApp...) only counts when its call screen is what's showing (that kind
        // of screen comes up over the lock screen). Otherwise, e.g. sitting in a Discord voice channel, the NEXUS
        // lock screen works as usual, just as the phone's own lock screen does.
        val am = getSystemService(android.media.AudioManager::class.java)
        val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
        val internetCallOnScreen = mode == android.media.AudioManager.MODE_IN_COMMUNICATION && callScreenShowing()
        if (mode == android.media.AudioManager.MODE_RINGTONE || mode == android.media.AudioManager.MODE_IN_CALL || internetCallOnScreen) {
            LockTrace.log(this, "  " + when {
                mode == android.media.AudioManager.MODE_RINGTONE -> "phone ringing"
                mode == android.media.AudioManager.MODE_IN_CALL -> "phone call in progress"
                else -> "internet call screen showing ($current)"
            } + ": NEXUS lock screen left out (the phone's own lock screen shows)")
            removeCover(); return
        }
        when (i.action) {
            Intent.ACTION_SCREEN_OFF -> {
                otherAppSinceOff = false; leftByScreenOff = true; coverToken++; h.removeCallbacks(safety); cover?.showBlack(); addCover(); showLock(c)
                h.postDelayed({ LockTrace.log(this, "  0.8s after screen off: lockAlive=" + LockActivity.alive + " lockShowing=" + LockActivity.showing) }, 800)
            }
            Intent.ACTION_SCREEN_ON -> {
                ensureCover()
                val km = getSystemService(KeyguardManager::class.java)
                if (otherAppSinceOff) {
                    // Something else came up while the screen was off (an alarm, a timer, the camera...):
                    // never hide it or push NEXUS over it
                    LockTrace.log(this, "  another app came up while off: cover removed")
                    removeCover()
                } else if (km?.isKeyguardLocked == true) {
                    // Fallback only: normally the lock screen is already in place from screen-off
                    // (Alarms, timers, the camera and calls are handled above, so this never covers them)
                    if (!LockActivity.showing) { LockTrace.log(this, "  lock screen not in front: opening it now"); showLock(c) }
                    // Safety: the cover never stays more than a moment, whatever happens
                    screenOnAt = SystemClock.uptimeMillis()
                    armSafety(1300, "cover lifted by the 1.3s fallback: the lock screen wasn't ready in time")
                    h.postDelayed({ LockTrace.log(this, "  0.6s after screen on: lockAlive=" + LockActivity.alive + " lockShowing=" + LockActivity.showing) }, 600)
                } else { LockTrace.log(this, "  phone not locked: nothing to cover"); armSafety(120, "cover lifted (phone not locked)") } // not locked: nothing to hide
            }
        }
    }

    /** Is TalkBack (or another screen reader that explores by touch) on? */
    private fun talkBackOn(): Boolean =
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /**
     * Is the app in front showing a call screen? Going by the screen's name (WhatsApp's VoipActivity, Signal's
     * WebRtcCallActivity, Meet's CallActivity, Zoom's ConfActivity...). Discord's voice channels, for example,
     * are in its main screen, which doesn't come up over the lock screen, so that doesn't count. If it's not
     * known which screen is showing, it counts (the safe side).
     */
    private fun callScreenShowing(): Boolean {
        if (current == null) return false
        val name = currentCls?.substringAfterLast('.')?.lowercase() ?: return true
        return CALL_WORDS.any { it in name }
    }
    private val CALL_WORDS = listOf("call", "voip", "rtc", "ringing", "incoming", "conf", "meeting", "dial", "phone")

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
        // Stop any fade in progress, and make sure the cover is fully opaque again (set twice so the new value
        // always reaches the render thread, even if the fade ran there)
        fadeToken++
        v.animate().cancel()
        v.alpha = 0f; v.alpha = 1f
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
                if (token == fadeToken && lp.alpha < 0.5f) { v.animate().cancel(); v.alpha = 0f; v.alpha = 1f; v.showBlack() }
            }, 120)
        }, FADE_MS + 20)
    }
    private var fadeToken = 0
    private val FADE_MS = 150L

    /** Whatever happens, the cover is lifted after [ms]. */
    private val safety = Runnable { safe { if ((coverLp?.alpha ?: 0f) > 0.5f) LockTrace.log(this, "  $safetyWhy"); fadeCover() } }
    /** What the next cover lift will be noted as in the lock screen log (on time, or the fallback timer). */
    private var safetyWhy = ""
    private fun armSafety(ms: Long, why: String) { safetyWhy = why; h.removeCallbacks(safety); h.postDelayed(safety, ms) }
    /** When the screen last came on (for the log). */
    private var screenOnAt = 0L

    /** Called when the lock screen or Recent Apps settings change (main thread). */
    fun refreshCover() { h.post { if (coverWanted()) ensureCover() else dropCover() } }

    /** Takes the cover away completely (lock screen switched off, or the helper stopping). */
    private fun dropCover() {
        val v = cover ?: return
        cover = null; coverLp = null
        try { getSystemService(android.view.WindowManager::class.java)?.removeView(v) } catch (_: Throwable) {}
    }

    /**
     * Called by the NEXUS lock screen as soon as it's in front with the screen on: a picture of it to show on
     * the cover while Android finishes handing the screen over. Returns false if the cover isn't up.
     */
    fun lockPreview(b: Bitmap): Boolean {
        val v = cover ?: return false
        if ((coverLp?.alpha ?: 0f) < 0.5f || v.closing) return false
        v.showPicture(b)
        LockTrace.log(this, "  lock screen shown on the cover at once")
        return true
    }

    /**
     * Turns the screen off, as the power button does: only for the NEXUS lock screen when it's been left alone,
     * so only while the phone is locked and the NEXUS lock screen is showing (never while you're using the phone).
     * Android 9 and later.
     */
    fun sleepScreen(): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true || !LockActivity.showing) return false
        return try { performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) } catch (_: Exception) { false }
    }

    /** Called by the NEXUS lock screen once it is lit and in front (the phone's own lock screen is no longer drawn). */
    fun lockReady() {
        val ms = SystemClock.uptimeMillis() - screenOnAt
        LockTrace.log(this, "  lock screen lit and in front" + (if (screenOnAt > 0 && ms in 0..5000) " (${ms}ms after screen on)" else ""))
        armSafety(0, "cover lifted on time")
    }

    /** Called by NEXUS Recent Apps once it is drawn and in front: the cover fades away to reveal it
     *  (after the app has finished shrinking into its card). */
    fun switcherReady() {
        // Recent Apps is drawn and in front: the fade lifts shortly (once the phone's slide underneath is all but done)
        if (veilKind == FADE_RECENTS && veilSeenAt == 0L) {
            veilSeenAt = SystemClock.uptimeMillis(); h.removeCallbacks(veilRun); h.postDelayed(veilRun, 50L)
        }
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
            LockTrace.log(this, "  asked Android to open the lock screen")
        } catch (t: Exception) { LockTrace.log(this, "  couldn't open the lock screen: " + t) }
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        LockTrace.log(this, "helper started")
        safe { loadQueue() }
        // The screen on/off listener first: the NEXUS lock screen depends on it
        val f = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOn, f)
        } catch (t: Exception) { App.noteProblem(this, "starting the helper", t) }
        // Everything else: each part on its own, so one problem can't stop the rest
        safe { refreshHomes() }
        // Usage counts were kept by an earlier version for the lock screen cube; it no longer needs them
        safe { getSharedPreferences("hub", MODE_PRIVATE).edit().remove("usecount").apply() }
        safe { ensureCover() }
        safe { setupAppButtons() }
        // NEXUS no longer takes pictures of your screen: remove any it saved before
        Bg.thread { try { snapDir(this).listFiles()?.forEach { it.delete() } } catch (_: Throwable) {} }.start()
        // Have names and icons ready for Recent Apps
        safe { AppCache.warm(this, Recents.list(this).map { it.first }) }
    }

    override fun onDestroy() {
        LockTrace.log(this, "helper stopped")
        try { unregisterReceiver(screenOn) } catch (_: Exception) {}
        dropCover()
        dropButtons()
        try { recents.destroy() } catch (_: Throwable) {}
        lockStrip?.let { s -> lockStrip = null; lockStripLp = null; try { getSystemService(android.view.WindowManager::class.java)?.removeView(s) } catch (_: Throwable) {} }
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
        // (The phone's launchers aren't apps you've opened; but Settings, which also counts as a "home" on many
        // phones, is: it's listed in Recent Apps, and closed fully, like any other app)
        if (pkg in homePkgs && pkg != "com.android.settings") return false
        if ("inputmethod" in pkg || "keyboard" in pkg) return false
        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) = safe { handleEvent(e) }

    private var phoneRecentsAskedUntil = 0L
    private var phoneRecentsOpen = false

    // ------------------------------------------------------------------ closing apps fully
    // Android only lets the phone's own recent-apps screen close other apps. So for apps swiped away in NEXUS
    // Recent Apps, NEXUS (next time its home screen shows) puts up its cover ("CLOSING APPS"), opens the phone's
    // recent apps underneath, presses Close on those apps' cards (the same button TalkBack users have), goes
    // back home and lowers the cover. It only looks at the phone's recent-apps screen, and only while doing this.

    /** Apps waiting to be closed fully. */
    private val toClose = LinkedHashSet<String>()
    private var closeAllPending = false
    private class CloseJob(val pkgs: List<String>, val all: Boolean, val toSwitcher: Boolean, val clearRest: Boolean = false) {
        /** 1 opening the phone's recent apps, 2 closing, 3 going home. */
        var stage = 0; var tries = 0; var closed = 0; var recentsPkg = ""; var missed = 0
        /** Where it's going back to: true NEXUS Recent Apps, false the home screen. */
        var backToSwitcher = false
        val startedAt = SystemClock.uptimeMillis()
        /** Cards already pressed Close on (so one that won't go away can't keep it going). */
        val tried = HashSet<String>()
        /** Closing stops after this long, so the whole thing stays about 2 seconds. */
        fun outOfTime() = SystemClock.uptimeMillis() - startedAt > (if (all) 3500 else 5500)
        /** Cards still there after closing got a second press already. */
        var retried = false
        /** How many times the phone's recent apps were scrolled to find a card. */
        var scrolls = 0
    }
    private var closeJob: CloseJob? = null

    fun enqueueClose(pkgs: List<String>, all: Boolean): Boolean {
        if (!fullCloseOn(this) || Build.VERSION.SDK_INT < 26) return false
        for (p in pkgs) if (p !in OWN_APPS && p != packageName) toClose.add(p)
        if (all) closeAllPending = true
        saveQueue()
        return true
    }

    /** Apps waiting to be closed fully are kept on the phone (so they're still closed if Android stops NEXUS meanwhile). */
    private fun saveQueue() {
        getSharedPreferences("hub", Context.MODE_PRIVATE).edit()
            .putString("closeQueue", toClose.joinToString(",")).putBoolean("closeAllPending", closeAllPending).apply()
    }
    private fun loadQueue() {
        val p = getSharedPreferences("hub", Context.MODE_PRIVATE)
        p.getString("closeQueue", "")?.split(",")?.filter { it.isNotBlank() }?.let { toClose.addAll(it) }
        closeAllPending = closeAllPending || p.getBoolean("closeAllPending", false)
    }

    /** Step 0: the cube comes up at once (the caller is heading to the NEXUS home screen underneath it). */
    fun beginClose(returnToSwitcher: Boolean): Boolean {
        if (closeJob != null || (toClose.isEmpty() && !closeAllPending)) return false
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm?.isInteractive != true || km?.isKeyguardLocked == true) return false
        // If no other apps are left in NEXUS Recent Apps, nothing should be left open in the phone either: its
        // whole list is cleared too (quietly), which also closes any app NEXUS never got to see
        val clearRest = !closeAllPending && Recents.list(this).none { it.first !in OWN_APPS && it.first != packageName && it.first !in toClose }
        val j = CloseJob(toClose.toList(), closeAllPending, returnToSwitcher, clearRest)
        toClose.clear(); closeAllPending = false; saveQueue()
        veilKind = 0; h.removeCallbacks(veilRun) // the closing cover takes over from any fade
        closeJob = j; j.stage = 0
        LockTrace.log(this, "closing apps fully" + (if (j.all) ": all" else " (quietly): " + j.pkgs.joinToString() + if (j.clearRest) " and anything else left open" else ""))
        // The cover comes up (fading in), and takes touches meanwhile so nothing underneath gets tapped by accident
        ensureCover(force = true)
        val v = cover; val lp = coverLp
        if (v == null || lp == null) { closeJob = null; return false }
        coverToken++; fadeToken++; h.removeCallbacks(safety)
        // Clear all: the closing cube. Apps swiped away one by one: no animation at all, just a still picture
        // of the NEXUS home screen you're heading to, while they close underneath
        if (j.all) v.showClosing(j.pkgs.map { it to AppCache.get(this, it)?.icon })
        else v.showStill(MainActivity.homeStill())
        lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        lp.alpha = 1f
        v.animate().cancel(); v.alpha = 1f; v.alpha = 0f
        try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        v.animate().alpha(1f).setDuration(140).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // If the home screen was already in front (so no sign of it arriving), carry on anyway
        h.postDelayed({ safe { if (closeJob === j && j.stage == 0) proceedToRecents(j) } }, 500)
        // Whatever happens, it never takes more than a few seconds
        h.postDelayed({ safe { if (closeJob === j) endClose(j, "took too long", goHome = j.stage == 2) } }, 7500)
        return true
    }

    /** Step 1: from the NEXUS home screen (under the cube), open the phone's own recent apps. */
    private fun proceedToRecents(j: CloseJob) {
        if (closeJob !== j || j.stage != 0) return
        j.stage = 1
        h.postDelayed({ safe { if (closeJob === j && !askPhoneRecents()) endClose(j, "couldn't open the phone's recent apps", goHome = false) } }, 60)
    }

    /** In the phone's recent apps: Clear all, or Close on each app's card in turn. */
    /** The phone's recent-apps screen, to look at (the active window, or found among the windows on screen). */
    private fun recentsRoot(j: CloseJob): AccessibilityNodeInfo? {
        val a = try { rootInActiveWindow } catch (_: Throwable) { null }
        if (a != null && a.packageName?.toString() == j.recentsPkg) return a
        return try { windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == j.recentsPkg } } } catch (_: Throwable) { null }
    }

    private fun runCloses(j: CloseJob) {
        if (closeJob !== j) return
        val root = recentsRoot(j)
        if (root == null) {
            if (++j.tries < 16) { h.postDelayed({ safe { runCloses(j) } }, 120); return }
            // Most likely the helper needs switching off and on once, to get the access it now needs
            App.noteProblem(this, "closing apps fully", IllegalStateException("Android didn't let the helper look at the phone's recent apps. In NEXUS settings > Recent apps, turn the helper off and on again."))
            goHomeAfterClose(j, "couldn't look at the phone's recent apps (helper may need turning off and on)")
            return
        }
        LockTrace.log(this, "  phone's recent apps: " + cardNames(root).let { if (it.isEmpty()) "no app cards found" else "cards " + it.joinToString() })
        if (j.all || j.clearRest) {
            // Clear all if the button can be pressed; then Close on any cards still there, one by one
            val clear = findByWords(root, setOf("clear all"))
            val pressed = clear != null && clear.isEnabled && clear.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (pressed) { LockTrace.log(this, "  pressed Clear all"); cover?.peelAll() }
            // (The phone clears them once its animation has finished: wait for that before looking again)
            h.postDelayed({ safe { closeEveryCard(j, 0) } }, if (pressed) 650L else 0L)
            return
        }
        closeNext(j, ArrayDeque(j.pkgs))
    }

    /** Clear all: Close on every card left, one at a time, until none are left. */
    private fun closeEveryCard(j: CloseJob, round: Int) {
        if (closeJob !== j) return
        val root = recentsRoot(j)
        // Each card once (a card that doesn't go away isn't pressed again), and never past the time limit
        val card = if (round < 20 && root != null && !j.outOfTime()) findAnyCard(root, j.tried) else null
        if (card == null || !card.first.performAction(card.second)) {
            val left = if (root != null) cardNames(root) else emptyList()
            val why = (if (j.outOfTime() && card != null) "time's up, closed $round" else if (round == 0) "nothing left to close" else "closed $round") +
                (if (left.isNotEmpty() && round > 0) ", still showing: " + left.joinToString() else "")
            h.postDelayed({ safe { goHomeAfterClose(j, why) } }, 150)
            return
        }
        cover?.peel(null)
        h.postDelayed({ safe { closeEveryCard(j, round + 1) } }, 420)
    }

    /** The names on the app cards that have a Close button (for the log, to see what NEXUS found). */
    private fun cardNames(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000 && out.size < 20) {
            val n = q.removeFirst()
            if (closeAction(n) != null) out.add(namesOf(n).firstOrNull() ?: "?")
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    /**
     * Any app card with a Close button (for Clear all): only real app cards (they carry the app's name), and
     * not one already pressed (if it's still there, it won't close, so it's left alone).
     */
    private fun findAnyCard(root: AccessibilityNodeInfo, tried: MutableSet<String>): Pair<AccessibilityNodeInfo, Int>? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        val r = android.graphics.Rect()
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            val act = closeAction(n)
            if (act != null) {
                val name = namesOf(n).firstOrNull()
                if (name != null) {
                    n.getBoundsInScreen(r)
                    if (tried.add(name + "|" + r.flattenToString())) return n to act
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun closeNext(j: CloseJob, left: ArrayDeque<String>) {
        if (closeJob !== j) return
        if (j.outOfTime() && left.isNotEmpty()) {
            toClose.addAll(left); saveQueue()
            goHomeAfterClose(j, "time's up, closed ${j.closed} (the rest next time)"); return
        }
        val pkg = left.removeFirstOrNull()
        if (pkg == null) {
            h.postDelayed({ safe { verifyThenHome(j) } }, 150)
            return
        }
        val name = label(pkg)
        val root = recentsRoot(j)
        val card = root?.let { findCard(it, namesFor(pkg)) }
        if (card == null && root != null && j.scrolls < 8) {
            // Not in view: scroll the phone's recent apps along (further, then back the other way) and look again
            val forward = j.scrolls < 4
            val list = findScrollable(root)
            val moved = list?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
            if (moved) {
                j.scrolls++
                left.addFirst(pkg)
                h.postDelayed({ safe { closeNext(j, left) } }, 260)
                return
            }
            if (forward) { j.scrolls = 4; left.addFirst(pkg); h.post { safe { closeNext(j, left) } }; return }
        }
        if (card != null && card.first.performAction(card.second)) { j.closed++; LockTrace.log(this, "  closed $name") }
        else { j.missed++; LockTrace.log(this, "  $name wasn't in the phone's recent apps (already closed?)") }
        cover?.peel(pkg) // either way it's no longer open: its icon falls off the cube
        // The phone only really closes the app once the card has finished flying away: wait for that (leaving its
        // recent apps any sooner can cancel it, and the app stays open)
        h.postDelayed({ safe { closeNext(j, left) } }, 420)
    }

    /** A last look at the phone's recent apps: any app still there gets one more press, then home. */
    private fun verifyThenHome(j: CloseJob) {
        if (closeJob !== j) return
        val root = recentsRoot(j)
        val names = if (root != null) cardNames(root) else emptyList()
        val stuck = j.pkgs.filter { p -> val ns = namesFor(p); names.any { n -> n in ns || ns.any { n.startsWith("$it,") } } }
        if (stuck.isNotEmpty() && !j.retried && root != null && !j.outOfTime()) {
            j.retried = true
            LockTrace.log(this, "  still open after closing: " + stuck.joinToString { label(it) } + " (pressing Close again)")
            for (p in stuck) findCard(root, namesFor(p))?.let { it.first.performAction(it.second) }
            h.postDelayed({ safe { verifyThenHome(j) } }, 450)
            return
        }
        val note = "closed ${j.closed}" + (if (j.missed > 0) ", ${j.missed} not found" else "") +
            (if (stuck.isNotEmpty()) ", still open: " + stuck.joinToString { label(it) } else "")
        goHomeAfterClose(j, note)
    }

    private fun goHomeAfterClose(j: CloseJob, why: String) {
        if (closeJob !== j) return
        cover?.peelAll()
        j.stage = 3
        val back = j.toSwitcher && Recents.list(this).isNotEmpty()
        j.backToSwitcher = back
        LockTrace.log(this, "  $why: back to NEXUS" + if (back) " Recent Apps" else "")
        if (back) {
            // Straight back into NEXUS Recent Apps (still under the cube)
            try {
                startActivity(Intent(this, SwitcherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            } catch (_: Throwable) { try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {} }
        } else try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        h.postDelayed({ safe { if (closeJob === j) endClose(j, null, goHome = false) } }, 500)
    }

    /** Done (or given up): the cover stops taking touches and fades away. */
    private fun endClose(j: CloseJob, problem: String?, goHome: Boolean) {
        if (closeJob !== j) return
        closeJob = null
        if (problem != null) LockTrace.log(this, "  closing apps stopped: $problem")
        if (goHome) try { performGlobalAction(GLOBAL_ACTION_HOME) } catch (_: Throwable) {}
        val v = cover; val lp = coverLp
        if (v != null && lp != null) {
            lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
        if (problem == "the screen turned off") return
        // Let the last icons finish falling, then the cover fades away (never more than a moment's wait)
        cover?.peelAll()
        val wait = ((cover?.fallsEnd() ?: 0L) - SystemClock.uptimeMillis()).coerceIn(0L, 300L)
        h.postDelayed({ safe { if (closeJob == null && cover?.closing == true) fadeCover() } }, wait)
    }

    private fun label(pkg: String): String = AppCache.get(this, pkg)?.label
        ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }

    /** The words on a node and just inside it (lower case). */
    private fun namesOf(n: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>(4)
        fun add(x: CharSequence?) { x?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { out.add(it) } }
        add(n.contentDescription); add(n.text)
        for (i in 0 until minOf(n.childCount, 8)) n.getChild(i)?.let { c ->
            add(c.contentDescription); add(c.text)
            for (k in 0 until minOf(c.childCount, 8)) c.getChild(k)?.let { g -> add(g.contentDescription); add(g.text) }
        }
        return out
    }

    /** The Close (dismiss) button an app card offers, if this node is one. */
    private fun closeAction(n: AccessibilityNodeInfo): Int? {
        for (a in n.actionList) {
            val l = a.label?.toString()?.trim()?.lowercase() ?: continue
            if (l == "close" || l == "dismiss" || l == "remove" || l == "close app") return a.id
        }
        if (n.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }) return AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
        return null
    }

    /** The card for the app called [name] (its exact name first), with its Close button. */
    /** Every name an app's card may show: the app's name, and the name of the screen it opens on. */
    private fun namesFor(pkg: String): List<String> {
        val out = LinkedHashSet<String>()
        out.add(label(pkg).trim().lowercase())
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let { packageManager.resolveActivity(it, 0) }
                ?.loadLabel(packageManager)?.toString()?.trim()?.lowercase()?.let { out.add(it) }
        } catch (_: Exception) {}
        return out.filter { it.isNotEmpty() }
    }

    /** The phone's scrolling list of app cards. */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            if (n.isScrollable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun findCard(root: AccessibilityNodeInfo, wants: List<String>): Pair<AccessibilityNodeInfo, Int>? {
        var loose: Pair<AccessibilityNodeInfo, Int>? = null
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            val act = closeAction(n)
            if (act != null) {
                val names = namesOf(n)
                if (names.any { it in wants }) return n to act
                if (loose == null && names.any { nm -> wants.any { nm.startsWith("$it,") } }) loose = n to act
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return loose
    }

    /** A button showing exactly one of [words] (or the clickable thing it sits in). */
    private fun findByWords(root: AccessibilityNodeInfo, words: Set<String>): AccessibilityNodeInfo? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            val w = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            if (w != null && w in words) {
                return if (n.isClickable) n else null
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    /** Waiting for the NEXUS home screen to appear, to open the phone's own recent apps from there. */
    private var phoneRecentsViaHome = false
    private var viaHomeToken = 0

    fun preparePhoneRecents() {
        phoneRecentsViaHome = true
        phoneRecentsAskedUntil = SystemClock.uptimeMillis() + 4000
        val token = ++viaHomeToken
        // Never left waiting: if the home screen doesn't appear, forget it
        h.postDelayed({ if (token == viaHomeToken && phoneRecentsViaHome) { phoneRecentsViaHome = false; phoneRecentsAskedUntil = 0L } }, 3000)
    }

    fun askPhoneRecents(): Boolean {
        phoneRecentsAskedUntil = SystemClock.uptimeMillis() + 3000
        val ok = try { performGlobalAction(GLOBAL_ACTION_RECENTS) } catch (_: Exception) { false }
        if (!ok) phoneRecentsAskedUntil = 0L
        return ok
    }

    /** You've left the phone's own recent apps: back to NEXUS (home or lock screen) or into an app. */
    private fun leftPhoneRecents(pkg: String, cls: String) =
        if (pkg == packageName) cls.contains("MainActivity") || cls.contains("LockActivity")
        else pkg != "com.android.systemui" && pkg != "android" && pkg !in homePkgs && "inputmethod" !in pkg && "keyboard" !in pkg

    private fun handleEvent(e: AccessibilityEvent?) {
        if (e == null) return
        // Windows changed (e.g. the phone's own button bar showed or hid): NEXUS's buttons follow it
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) { queueButtons(); return }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        // NEXUS's own pop-ups and overlays (its buttons, its cover) aren't a screen you've gone to: only its
        // screens (activities) count, so showing the buttons never looks like you've left the app you're in
        if (pkg == packageName && !cls.startsWith("$packageName.")) { queueButtons(); return }
        if (pkg != "com.android.systemui" && pkg != "android" && "inputmethod" !in pkg && "keyboard" !in pkg) { frontPkg = pkg; frontCls = cls }
        queueButtons()
        // Recent Apps (NEXUS's layer) steps aside for a call, an alarm or any app that comes in front
        recents.onWindowEvent(pkg, cls, pkg in homePkgs)
        // Under NEXUS's fade: the new screen has announced itself (the home screen, or the app being opened)
        if (veilKind != 0 && veilSeenAt == 0L && pkg != "com.android.systemui" && "inputmethod" !in pkg && "keyboard" !in pkg &&
            ((veilKind == FADE_HOME && cls == MainActivity::class.java.name) ||
                (veilKind != FADE_HOME && veilKind != FADE_RECENTS && pkg != packageName))) {
            veilSeenAt = SystemClock.uptimeMillis(); h.removeCallbacks(veilRun); h.postDelayed(veilRun, VEIL_HOLD)
        }
        // Another app put up a screen (alarm, timer, camera, call...): the wake-up cover must never hide it
        if (pkg != packageName && pkg != "com.android.systemui" && pkg != "android" && pkg !in homePkgs &&
            "inputmethod" !in pkg && "keyboard" !in pkg) {
            val pm = getSystemService(android.os.PowerManager::class.java)
            // (Except while an app you just opened is appearing under NEXUS's opening fade: that fades away itself)
            if (veilKind != 0) { /* NEXUS's fade lifts by itself once the new screen is in place */ }
            else if (pm?.isInteractive == true) removeCover() else otherAppSinceOff = true
        }
        // The phone's own recent-apps screen opened (square button): swap in NEXUS Recent Apps
        // Only the system's dedicated recent-apps screen counts (e.g. ...quickstep.RecentsActivity or
        // ...systemui.recents.RecentsActivity). Never a launcher's home screen, never another app.
        val systemScreen = pkg in homePkgs || pkg == "com.android.systemui"
        val isRecents = systemScreen && pkg != packageName &&
            cls.contains("Recents", ignoreCase = true) && !cls.contains("Launcher", ignoreCase = true)
        // The phone's own recent apps, asked for on purpose (to fully close apps): leave it alone while it's open,
        // until you go back to NEXUS or into an app
        if (phoneRecentsOpen && !isRecents && leftPhoneRecents(pkg, cls)) phoneRecentsOpen = false
        if (isRecents && (phoneRecentsOpen || SystemClock.uptimeMillis() < phoneRecentsAskedUntil)) {
            if (!phoneRecentsOpen) LockTrace.log(this, "phone's own recent apps open (asked for)")
            phoneRecentsOpen = true; phoneRecentsAskedUntil = 0L
            // NEXUS opened it to close apps: let it settle, then press Close on their cards
            closeJob?.let { j -> if (j.stage == 1) { j.stage = 2; j.recentsPkg = pkg; h.postDelayed({ safe { runCloses(j) } }, 220) } }
            return
        }
        if (isRecents && takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this) && !talkBackOn()) {
            openNexusRecents("square button")
            return
        }
        if (isRealApp(pkg)) {
            // Only an app you actually opened counts as open: one of its full screens (not a pop-up or a dialog),
            // shown while the phone is unlocked (not an alarm or a call ringing over the lock screen),
            // and still in front a moment later (not something that flashed up and went).
            val pm = getSystemService(PowerManager::class.java)
            val km = getSystemService(KeyguardManager::class.java)
            if (pm?.isInteractive != true || km?.isKeyguardLocked == true || !isAppScreen(pkg, cls)) return
            // Shrunk into a little floating video window (YouTube does that when you leave a video): you've left it
            if (inPictureInPicture(pkg)) return
            lastApp = pkg; leftByScreenOff = false
            if (pkg == pendingClose) pendingClose = null // went straight back into it: keep it
            // Which screen of the app is showing (only real screens: some apps report a view's name instead)
            val screen = if (isActivity(pkg, cls)) cls else null
            if (pkg != current) currentCls = screen else if (screen != null) currentCls = screen
            if (toClose.remove(pkg)) saveQueue() // opened again before it was closed: leave it open
            if (pkg != current) {
                current = pkg
                val token = ++openToken
                h.postDelayed({
                    safe {
                        if (token == openToken && current == pkg && pm?.isInteractive == true && km?.isKeyguardLocked != true) {
                            Recents.touch(this, pkg)
                            AppCache.warm(this, listOf(pkg))
                        }
                    }
                }, 1500)
            }
        } else if (pkg in homePkgs || pkg == packageName) {
            current = null; currentCls = null; openToken++
            // Back on the NEXUS home screen straight from one of our own apps: that app closes completely
            // (not when the screen just went off and came back, and not via the square button's Recent Apps)
            // Back home at the end of closing apps fully: lower the cover, nothing else to do
            closeJob?.let { j ->
                if (pkg == packageName && cls == MainActivity::class.java.name) {
                    // Under the cube: arrived at the NEXUS home screen (step 0: now open the phone's recent apps),
                    // or back at the end (step 3: let the cube finish and fade)
                    if (j.stage == 0) proceedToRecents(j)
                    else if (j.stage == 3 && !j.backToSwitcher) h.postDelayed({ safe { endClose(j, null, goHome = false) } }, 100)
                    return
                }
                if (pkg == packageName && cls == SwitcherActivity::class.java.name && j.stage == 3 && j.backToSwitcher) {
                    h.postDelayed({ safe { endClose(j, null, goHome = false) } }, 120)
                    return
                }
            }
            if (pkg == packageName && cls == MainActivity::class.java.name && phoneRecentsViaHome) {
                // Only passing through the home screen on the way to the phone's own recent apps: nothing
                // closes; once NEXUS has finished appearing, open the phone's screen from here
                phoneRecentsViaHome = false
                val token = ++viaHomeToken
                h.postDelayed({ safe { if (token == viaHomeToken) askPhoneRecents() } }, 420)
                return
            }
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

    /**
     * NEXUS Recent Apps over the app in front ([by]: the phone's square button, or NEXUS's own Recent button):
     * NEXUS's quick fade, with Recent Apps opening underneath.
     */
    private fun openNexusRecents(by: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSwitcher <= 800) return
        lastSwitcher = now
        current = null
        // NEXUS's own layer, over the app you're in: at once, nothing for Android to open
        if (!talkBackOn() && recents.show()) { LockTrace.log(this, "$by: NEXUS Recent Apps (over the app)"); return }
        // (After the phone's own square button the phone has already begun its own animation: a quicker fade)
        val faded = fadeFor(FADE_RECENTS, null, if (by == "Recent button") 110L else 70L)
        LockTrace.log(this, "$by: opening NEXUS Recent Apps")
        try {
            startActivity(Intent(this, SwitcherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("fromSquare", faded).putExtra("zoom", false), SwitcherActivity.noIconSplash(this))
        } catch (_: Exception) { liftVeil() }
    }

    // ------------------------------------------------------------------ NEXUS's quick fade, for every screen change
    // Android plays the phone's own slide whenever the screen changes between apps (opening an app, Home, Recent
    // apps, Back out of an app...) and ignores a launcher's choice of animation there. So NEXUS covers each change
    // with one simple, quick fade of its own: the NEXUS dark fades in, the change happens underneath, and the dark
    // fades away as soon as the new screen is in place (from Recent Apps, an app fades in over its card instead).
    // It never takes touches and never stays more than a moment.

    private var veilKind = 0
    private var veilAt = 0L
    private var veilSeenAt = 0L
    /** The window that was in front when the fade began (the change is "in place" once another one is). */
    private var veilFromWin = Int.MIN_VALUE
    private var veilsLogged = 0
    private val veilRun = Runnable { safe { stepVeil() } }
    /** After the new screen is in place, before the fade lifts (the phone's slide is all but done by then). */
    private val VEIL_HOLD = 170L

    /**
     * Fades the NEXUS dark in over [fadeInMs] (or shows [picture] at once) for a screen change of [kind];
     * it fades away by itself once the change is done. Returns false if it can't (the change then happens as usual).
     */
    fun fadeFor(kind: Int, picture: Bitmap? = null, fadeInMs: Long = 90L): Boolean {
        if (closeJob != null || talkBackOn()) return false
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return false
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        ensureCover(force = true)
        val v = cover ?: return false
        coverToken++
        if (picture != null) v.showStill(picture) else if (kind == FADE_RECENTS) v.showRecents(null) else v.showVeil()
        setCover(true)
        if (fadeInMs > 0) {
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(fadeInMs).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        veilKind = kind; veilAt = SystemClock.uptimeMillis(); veilSeenAt = 0L
        veilFromWin = topAppWindow()
        armSafety(VEIL_MAX + 600, "screen change: fade lifted by the fallback")
        h.removeCallbacks(veilRun); h.postDelayed(veilRun, 40L)
        return true
    }

    /** Opening an app from NEXUS (kept for the places that open apps). */
    fun veilForLaunch(@Suppress("UNUSED_PARAMETER") pkg: String, picture: Bitmap?, fadeInMs: Long): Boolean = fadeFor(FADE_APP, picture, fadeInMs)

    /** The change didn't happen after all: fade back. */
    fun cancelVeil() = liftVeil()

    private fun stepVeil() {
        if (veilKind == 0) return
        val now = SystemClock.uptimeMillis()
        if (veilSeenAt == 0L) { val w = topAppWindow(); if (w != Int.MIN_VALUE && w != veilFromWin) veilSeenAt = now }
        // If nothing changes (e.g. Back inside an app), it lifts almost at once
        val limit = when (veilKind) { FADE_BACK -> 200L; FADE_HOME -> 450L; FADE_RECENTS -> 800L; else -> VEIL_MAX }
        // (Recent Apps opens without the phone's slide, so its fade can lift almost at once)
        val hold = if (veilKind == FADE_RECENTS) 50L else VEIL_HOLD
        when {
            veilSeenAt != 0L && now - veilSeenAt >= hold -> liftVeil()
            veilSeenAt == 0L && now - veilAt >= limit -> liftVeil()
            veilSeenAt != 0L -> h.postDelayed(veilRun, hold - (now - veilSeenAt))
            else -> h.postDelayed(veilRun, 40L)
        }
    }

    /** The app window in front (its id), or Int.MIN_VALUE if it can't be told. */
    private fun topAppWindow(): Int = try {
        windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer }?.id ?: Int.MIN_VALUE
    } catch (_: Throwable) { Int.MIN_VALUE }

    private fun liftVeil() {
        if (veilKind == 0) return
        val now = SystemClock.uptimeMillis()
        // The first few each time the helper starts are noted (how quickly screens change), so it can be tuned
        if (veilsLogged < 3) {
            veilsLogged++
            LockTrace.log(this, "screen change ($veilKind): " + (if (veilSeenAt != 0L) "in place after ${veilSeenAt - veilAt}ms, " else "") + "faded after ${now - veilAt}ms")
        }
        veilKind = 0
        h.removeCallbacks(veilRun)
        fadeCover()
        // The app now in front counts as opened (in case it never announced itself)
        h.postDelayed({ safe { recheckFront() } }, 300)
    }

    // ------------------------------------------------------------------ NEXUS buttons in your apps
    // NEXUS settings → NEXUS buttons in all apps. NEXUS's Back / Home / Recent buttons sit exactly over the
    // phone's own button bar while you use other apps, so the buttons look and work the same everywhere.
    // They follow the phone's own bar: when it hides (full-screen videos and games), they hide too. They never
    // show on the lock screen, with TalkBack, with gesture navigation, in landscape (where the phone's bar moves
    // to the side), over the phone's own launcher, or over NEXUS's screens (which have their own).
    // They only press Back, Home and Recent: they never read, type or tap anything in your apps.

    private var appBar: NavBar? = null
    private var appBarLp: android.view.WindowManager.LayoutParams? = null
    private var appBarShown = false
    private var buttonsOn = false
    private var buttonsQueued = false
    private val buttonsRun = Runnable { buttonsQueued = false; safe { updateButtons() } }
    /** The app whose screen is in front (NEXUS and the phone's own launcher included). */
    private var frontPkg: String? = null
    private var frontCls = ""
    /** The phone's own button bar's window, once it's been checked to be the phone's (system) one. */
    private var navWindowId = -1
    private val barRect = android.graphics.Rect()
    private val toggleTimes = ArrayDeque<Long>()
    private var buttonsPausedUntil = 0L
    private var buttonsPauses = 0
    private val buttonsLogged = HashSet<String>()
    private var homeCheckedAt = 0L
    private var homeOk = false

    /** Picks up a change of setting (NEXUS settings → NEXUS buttons in all apps, or Navigation buttons). */
    fun refreshAppButtons() { h.post { safe { setupAppButtons() } } }

    private fun setupAppButtons() {
        buttonsOn = appButtonsOn(this)
        homeCheckedAt = 0L
        // Window changes (the phone's bar showing or hiding) are only listened to while this is switched on
        try {
            val info = serviceInfo
            if (info != null) {
                val want = if (buttonsOn) info.eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED
                           else info.eventTypes and AccessibilityEvent.TYPE_WINDOWS_CHANGED.inv()
                if (want != info.eventTypes) { info.eventTypes = want; serviceInfo = info }
            }
        } catch (_: Exception) {}
        if (buttonsOn) { appBar?.arrange(NavBar.order(this)); queueButtons(0) } else dropButtons()
    }

    /** Checks again shortly (many window changes in a row are handled together). */
    private fun queueButtons(delay: Long = 30L) {
        if (!buttonsOn || buttonsQueued) return
        buttonsQueued = true
        h.postDelayed(buttonsRun, delay)
    }

    private fun updateButtons() {
        val why = buttonsBlocked() ?: run {
            val bar = phoneBar()
            if (bar != null) { showButtons(bar.first, bar.second); return }
            "the phone's own buttons aren't on screen"
        }
        logButtons("hidden: $why")
        hideButtons(why)
    }

    /** Why NEXUS's buttons shouldn't show right now (null = nothing stops them). */
    private fun buttonsBlocked(): String? {
        if (!buttonsOn) return "switched off"
        if (NavBar.order(this) == "off") return "navigation buttons switched off"
        val now = SystemClock.uptimeMillis()
        if (now - homeCheckedAt > 3000) { homeOk = HomeMode.isOn(this) && HomeMode.isDefault(this); homeCheckedAt = now }
        if (!homeOk) return "NEXUS isn't the home screen"
        if (talkBackOn()) return "TalkBack is on"
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return "screen off"
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return "phone locked"
        // NEXUS's cover (Recent Apps opening, closing apps) draws the buttons itself: look again once it's gone
        // (They stay put above NEXUS's quick fade, like the phone's own buttons would)
        if (closeJob != null || ((coverLp?.alpha ?: 0f) > 0.5f && veilKind == 0)) { queueButtons(200); return "NEXUS cover showing" }
        if (!threeButtonNav(this)) return "gesture navigation"
        if (now < buttonsPausedUntil) { if (buttonsPausedUntil != Long.MAX_VALUE) queueButtons(buttonsPausedUntil - now + 50); return "paused" }
        // (Only its launcher or recent-apps screens: Settings, for one, also counts as a home app on many phones)
        val f = frontPkg
        if (f != null && f != packageName && f in homePkgs && f != "com.android.settings" &&
            LAUNCHER_WORDS.any { frontCls.contains(it, ignoreCase = true) }) return "the phone's own launcher or recent apps is showing"
        return null
    }

    /** The phone's own button bar, if it's on screen along the bottom: where it is, and whether the keyboard is open. */
    private fun phoneBar(): Pair<android.graphics.Rect, Boolean>? {
        val ws = try { windows } catch (_: Exception) { null } ?: return null
        val size = screenSize() ?: return null
        val d = resources.displayMetrics.density
        var bar: android.graphics.Rect? = null
        var ime = false
        for (w in ws) {
            when (w.type) {
                android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> ime = true
                android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> if (bar == null) {
                    w.getBoundsInScreen(barRect)
                    // Along the whole bottom edge, the height of a button bar
                    if (barRect.bottom >= size.y - 2 && barRect.left <= 2 && barRect.right >= size.x - 2 &&
                        barRect.height() >= (36 * d).toInt() && barRect.height() <= (80 * d).toInt()) {
                        // ...and really the phone's own (system) bar, not some other app's strip along the bottom
                        if (w.id == navWindowId || fromSystemUi(w)) { navWindowId = w.id; bar = android.graphics.Rect(barRect) }
                        else logButtons("a bar-shaped window that isn't the phone's own was left alone")
                    }
                }
            }
        }
        return bar?.let { it to ime }
    }

    private fun fromSystemUi(w: android.view.accessibility.AccessibilityWindowInfo): Boolean =
        try { w.root?.packageName?.toString() == "com.android.systemui" } catch (_: Exception) { false }

    private fun screenSize(): android.graphics.Point? = try {
        val p = android.graphics.Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = getSystemService(android.view.WindowManager::class.java)!!.maximumWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            @Suppress("DEPRECATION")
            getSystemService(android.hardware.display.DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        }
        p
    } catch (_: Exception) { null }

    private fun showButtons(r: android.graphics.Rect, ime: Boolean) {
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        NavBar.saveHeight(this, r.height())
        val v0 = appBar; val lp0 = appBarLp
        if (v0 == null || lp0 == null) {
            val v = NavBar(this,
                onBack = { safe { backButton() } },
                onHome = { safe { homeButton() } },
                onRecent = { safe { recentButton() } })
            v.solid = true; v.ime = ime
            val lp = android.view.WindowManager.LayoutParams(r.width(), r.height(),
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.graphics.PixelFormat.OPAQUE)
            lp.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            lp.x = r.left; lp.y = r.top
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
            lp.title = "NEXUS buttons"
            try { wm.addView(v, lp) } catch (t: Throwable) { logButtons("couldn't be shown: " + t.javaClass.simpleName); return }
            appBar = v; appBarLp = lp; appBarShown = true; noteToggle()
            logButtons("shown over the phone's own buttons (" + r.width() + "x" + r.height() + ")")
            return
        }
        v0.arrange(NavBar.order(this)); v0.ime = ime
        val moved = lp0.x != r.left || lp0.y != r.top || lp0.width != r.width() || lp0.height != r.height()
        if (appBarShown && !moved) return
        lp0.x = r.left; lp0.y = r.top; lp0.width = r.width(); lp0.height = r.height()
        lp0.flags = lp0.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (!appBarShown) { appBarShown = true; noteToggle() }
        v0.visibility = android.view.View.VISIBLE
        try { wm.updateViewLayout(v0, lp0) } catch (_: Throwable) {}
    }

    /** Out of the way: invisible and letting every touch through to the phone's own buttons. */
    private fun hideButtons(@Suppress("UNUSED_PARAMETER") why: String) {
        val v = appBar ?: return
        val lp = appBarLp ?: return
        if (!appBarShown) return
        appBarShown = false; noteToggle()
        v.visibility = android.view.View.GONE
        lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
    }

    private fun dropButtons() {
        val v = appBar ?: return
        appBar = null; appBarLp = null; appBarShown = false
        try { getSystemService(android.view.WindowManager::class.java)?.removeView(v) } catch (_: Throwable) {}
    }

    /**
     * The NEXUS lock screen never shows the phone's own Back / Home buttons (Android shows them now and then over a
     * lock screen, e.g. after a swipe from the bottom edge): while it's in front, a strip the colour of the lock
     * screen's bottom edge always covers their place (the lock screen paints the same colour there, so the strip
     * itself can't be seen). It takes no touches, so the swipe still unlocks, and it steps aside for the phone's PIN
     * screen (which has its own Back button) and for calls, alarms and anything else that comes in front.
     */
    private var lockStrip: android.view.View? = null
    private var lockStripLp: android.view.WindowManager.LayoutParams? = null
    fun updateLockStrip() { h.post { safe { applyLockStrip() } } }
    private fun applyLockStrip() {
        val want = LockActivity.inFront && !LockActivity.askingUnlock && !talkBackOn() &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val wm = getSystemService(android.view.WindowManager::class.java) ?: return
        var v = lockStrip; var lp = lockStripLp
        if (!want) {
            if (v != null && lp != null && v.visibility == android.view.View.VISIBLE) {
                v.visibility = android.view.View.GONE
                lp.flags = lp.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
            }
            return
        }
        val hpx = lockStripPx(this)
        if (v == null || lp == null) {
            v = android.view.View(this).apply { setBackgroundColor(LOCK_BOTTOM) }
            lp = android.view.WindowManager.LayoutParams(android.view.WindowManager.LayoutParams.MATCH_PARENT, hpx,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE)
            lp.gravity = android.view.Gravity.BOTTOM or android.view.Gravity.LEFT
            if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            lp.title = "NEXUS lock strip"
            try { wm.addView(v, lp) } catch (_: Throwable) { return }
            lockStrip = v; lockStripLp = lp
            return
        }
        if (v.visibility != android.view.View.VISIBLE || lp.height != hpx) {
            v.visibility = android.view.View.VISIBLE; lp.height = hpx
            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
        }
    }

    /** Safety: if the buttons ever keep flicking on and off, they step aside for a while (the phone's own show). */
    private fun noteToggle() {
        val now = SystemClock.uptimeMillis()
        // Only real flicker counts (8 changes inside about a second): switching apps quickly never gets near that
        toggleTimes.addLast(now)
        while (toggleTimes.isNotEmpty() && now - toggleTimes.first() > 1200) toggleTimes.removeFirst()
        if (toggleTimes.size >= 8) {
            toggleTimes.clear()
            // The first time for 15 seconds; if it happens again, until the helper next starts
            buttonsPauses++
            buttonsPausedUntil = if (buttonsPauses >= 2) Long.MAX_VALUE else now + 15000
            LockTrace.log(this, "NEXUS buttons: kept switching on and off, so they stepped aside " +
                (if (buttonsPauses >= 2) "until the helper restarts" else "for 15s") + " (the phone's own buttons show meanwhile)")
            h.post { safe { hideButtons("paused") } }
        }
    }

    /** NEXUS's Home button over an app: the home screen shows at once (no phone slide), then Home. */
    private fun homeButton() {
        // (Recent Apps up: it handles it, fading into the home screen)
        if (recents.showing) { recents.goNexus(); return }
        fadeFor(FADE_HOME, null, 90L)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** NEXUS's Back button over an app: the quick fade (not while typing: that Back only closes the keyboard). */
    private fun backButton() {
        if (recents.showing) { recents.back(); return }
        if (appBar?.ime != true) fadeFor(FADE_BACK, null, 80L)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /** NEXUS's Recent button over an app: NEXUS Recent Apps (or the phone's own, if that's what's set). */
    private fun recentButton() {
        if (recents.showing) { recents.recentPressed(); return }
        if (takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this) && !talkBackOn()) openNexusRecents("Recent button")
        else performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    private val LAUNCHER_WORDS = listOf("launcher", "recents", "quickstep")

    /** Each kind of note once per helper start, so the log stays short. */
    private fun logButtons(what: String) { if (buttonsLogged.add(what)) LockTrace.log(this, "NEXUS buttons: $what") }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        safe { queueButtons(60) }
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
    private val activityCache = HashMap<String, Boolean>()
    /** Is [cls] one of [pkg]'s screens (activities), rather than the name of a view or pop-up? */
    private fun isActivity(pkg: String, cls: String): Boolean {
        if (cls.isEmpty()) return false
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val ok = try { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0); true } catch (_: Exception) { false }
        if (activityCache.size > 400) activityCache.clear()
        activityCache[key] = ok
        return ok
    }

    private fun isAppScreen(pkg: String, cls: String): Boolean {
        val key = "$pkg/$cls"
        screenCache[key]?.let { return it }
        val activity = isActivity(pkg, cls)
        val popup = POPUP_WORDS.any { cls.contains(it, ignoreCase = true) }
        val ok = activity || !popup
        if (screenCache.size > 400) screenCache.clear()
        screenCache[key] = ok
        return ok
    }
    private val POPUP_WORDS = listOf("Dialog", "Popup", "PopUp", "Toast", "Menu", "Notification", "Tooltip", "Snackbar", "BottomSheet")

    private fun takeOverRecents() = getSharedPreferences("hub", MODE_PRIVATE).getBoolean("replaceRecents", true)

    /**
     * Every few seconds, refresh the current app's picture (for Recent Apps and the square button's zoom).
     * Kept in memory; it's only saved to storage when you leave the app (or at most every 30 seconds), so the
     * phone isn't writing a file every few seconds. Slower in Battery Saver.
     */
    /**
     * Whatever app is in front now counts as opened. Catches apps that came up while the phone was still locked
     * (opened from a notification, say), which Android doesn't announce again once you unlock.
     */
    private fun recheckFront() {
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val top = try { windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer } } catch (_: Throwable) { null } ?: return
        val pkg = try { top.root?.packageName?.toString() } catch (_: Throwable) { null } ?: return
        if (pkg == current || !isRealApp(pkg) || inPictureInPicture(pkg)) return
        lastApp = pkg; current = pkg; currentCls = null; leftByScreenOff = false
        if (toClose.remove(pkg)) saveQueue()
        val token = ++openToken
        h.postDelayed({ safe { if (token == openToken && current == pkg) { Recents.touch(this, pkg); AppCache.warm(this, listOf(pkg)) } } }, 1500)
    }

    /** Is [pkg] showing only as a little floating picture-in-picture window? */
    private fun inPictureInPicture(pkg: String): Boolean = try {
        windows.any {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isInPictureInPictureMode &&
                it.root?.packageName?.toString() == pkg
        }
    } catch (_: Throwable) { false }

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
