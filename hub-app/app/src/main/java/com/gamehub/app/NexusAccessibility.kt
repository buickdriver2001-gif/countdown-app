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
import java.io.FileOutputStream

/**
 * NEXUS's helper service (turned on by the user in Accessibility settings). It:
 *  - keeps a small screenshot of the last screen of each app you use (stored only inside NEXUS)
 *  - notices when the phone's recent-apps screen opens (the square button) and shows NEXUS Recent Apps instead
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
    private fun safe(block: () -> Unit) {
        try { block() } catch (t: Throwable) { App.saveHelperError(this, t) }
    }

    private fun handleScreen(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_SCREEN_OFF) { leftByScreenOff = true; persistShot() }
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

    /** Called by the NEXUS lock screen once it is lit and in front (the phone's own lock screen is no longer drawn). */
    fun lockReady() {
        val ms = SystemClock.uptimeMillis() - screenOnAt
        LockTrace.log(this, "  lock screen lit and in front" + (if (screenOnAt > 0 && ms in 0..5000) " (${ms}ms after screen on)" else ""))
        armSafety(0, "cover lifted on time")
    }

    /** Called by NEXUS Recent Apps once it is drawn and in front: the cover fades away to reveal it
     *  (after the app has finished shrinking into its card). */
    fun switcherReady() {
        val end = cover?.zoomEnd() ?: 0L
        armSafety(maxOf(30L, end - SystemClock.uptimeMillis() + 30L), "square button: Recent Apps ready, cover lifted on time")
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
            LockTrace.log(this, "  asked Android to open the lock screen")
        } catch (t: Exception) { LockTrace.log(this, "  couldn't open the lock screen: " + t) }
    }

    override fun onServiceConnected() {
        running = true
        instance = this
        LockTrace.log(this, "helper started")
        // The screen on/off listener first: the NEXUS lock screen depends on it
        val f = android.content.IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOn, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screenOn, f)
        } catch (t: Exception) { App.noteProblem(this, "starting the helper", t) }
        // Everything else: each part on its own, so one problem can't stop the rest
        safe { refreshHomes() }
        // Usage counts were kept by an earlier version for the lock screen cube; it no longer needs them
        safe { getSharedPreferences("hub", MODE_PRIVATE).edit().remove("usecount").apply() }
        h.postDelayed(tick, 2000)
        safe { ensureCover() }
        // Have names and icons ready for Recent Apps
        safe { AppCache.warm(this, Recents.list(this).map { it.first }) }
    }

    override fun onDestroy() {
        LockTrace.log(this, "helper stopped")
        try { unregisterReceiver(screenOn) } catch (_: Exception) {}
        dropCover()
        instance = null
        running = false
        h.removeCallbacksAndMessages(null)
        persistShot()
        shotThread.quitSafely()
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
    private class CloseJob(val pkgs: List<String>, val all: Boolean, val toSwitcher: Boolean) {
        /** 1 opening the phone's recent apps, 2 closing, 3 going home. */
        var stage = 0; var tries = 0; var closed = 0; var recentsPkg = ""; var missed = 0
        /** Where it's going back to: true NEXUS Recent Apps, false the home screen. */
        var backToSwitcher = false
    }
    private var closeJob: CloseJob? = null

    fun enqueueClose(pkgs: List<String>, all: Boolean): Boolean {
        if (!fullCloseOn(this) || Build.VERSION.SDK_INT < 26) return false
        for (p in pkgs) if (p !in OWN_APPS && p != packageName) toClose.add(p)
        if (all) closeAllPending = true
        return true
    }

    /** Step 0: the cube comes up at once (the caller is heading to the NEXUS home screen underneath it). */
    fun beginClose(returnToSwitcher: Boolean): Boolean {
        if (closeJob != null || (toClose.isEmpty() && !closeAllPending)) return false
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm?.isInteractive != true || km?.isKeyguardLocked == true) return false
        val j = CloseJob(toClose.toList(), closeAllPending, returnToSwitcher)
        toClose.clear(); closeAllPending = false
        closeJob = j; j.stage = 0
        LockTrace.log(this, "closing apps fully: " + if (j.all) "all" else j.pkgs.joinToString())
        // The cover comes up (fading in), and takes touches meanwhile so nothing underneath gets tapped by accident
        ensureCover(force = true)
        val v = cover; val lp = coverLp
        if (v == null || lp == null) { closeJob = null; return false }
        coverToken++; fadeToken++; h.removeCallbacks(safety)
        v.showClosing(j.pkgs.map { it to AppCache.get(this, it)?.icon })
        lp.flags = lp.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        lp.alpha = 1f
        v.animate().cancel(); v.alpha = 1f; v.alpha = 0f
        try { getSystemService(android.view.WindowManager::class.java)?.updateViewLayout(v, lp) } catch (_: Throwable) {}
        v.animate().alpha(1f).setDuration(140).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        // If the home screen was already in front (so no sign of it arriving), carry on anyway
        h.postDelayed({ safe { if (closeJob === j && j.stage == 0) proceedToRecents(j) } }, 900)
        // Whatever happens, it never takes more than a few seconds
        h.postDelayed({ safe { if (closeJob === j) endClose(j, "took too long", goHome = j.stage == 2) } }, 7500)
        return true
    }

    /** Step 1: from the NEXUS home screen (under the cube), open the phone's own recent apps. */
    private fun proceedToRecents(j: CloseJob) {
        if (closeJob !== j || j.stage != 0) return
        j.stage = 1
        h.postDelayed({ safe { if (closeJob === j && !askPhoneRecents()) endClose(j, "couldn't open the phone's recent apps", goHome = false) } }, 260)
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
            if (++j.tries < 10) { h.postDelayed({ safe { runCloses(j) } }, 200); return }
            // Most likely the helper needs switching off and on once, to get the access it now needs
            App.noteProblem(this, "closing apps fully", IllegalStateException("Android didn't let the helper look at the phone's recent apps. In NEXUS settings > Recent apps, turn the helper off and on again."))
            goHomeAfterClose(j, "couldn't look at the phone's recent apps (helper may need turning off and on)")
            return
        }
        LockTrace.log(this, "  phone's recent apps: " + cardNames(root).let { if (it.isEmpty()) "no app cards found" else "cards " + it.joinToString() })
        if (j.all) {
            // Clear all if the button can be pressed; then Close on any cards still there, one by one
            val clear = findByWords(root, setOf("clear all"))
            val pressed = clear != null && clear.isEnabled && clear.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (pressed) { LockTrace.log(this, "  pressed Clear all"); cover?.peelAll() }
            h.postDelayed({ safe { closeEveryCard(j, 0) } }, if (pressed) 700L else 0L)
            return
        }
        closeNext(j, ArrayDeque(j.pkgs))
    }

    /** Clear all: Close on every card left, one at a time, until none are left. */
    private fun closeEveryCard(j: CloseJob, round: Int) {
        if (closeJob !== j) return
        val root = recentsRoot(j)
        val card = if (round < 40 && root != null) findAnyCard(root) else null
        if (card == null || !card.first.performAction(card.second)) {
            h.postDelayed({ safe { goHomeAfterClose(j, if (round == 0) "nothing left to close" else "closed $round") } }, 300)
            return
        }
        cover?.peel(null)
        h.postDelayed({ safe { closeEveryCard(j, round + 1) } }, 380)
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

    /** Any app card with a Close button (for Clear all). */
    private fun findAnyCard(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, Int>? {
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            closeAction(n)?.let { return n to it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return null
    }

    private fun closeNext(j: CloseJob, left: ArrayDeque<String>) {
        if (closeJob !== j) return
        val pkg = left.removeFirstOrNull()
        if (pkg == null) {
            h.postDelayed({ safe { goHomeAfterClose(j, "closed ${j.closed}" + (if (j.missed > 0) ", ${j.missed} not found" else "")) } }, 350)
            return
        }
        val name = label(pkg)
        val root = recentsRoot(j)
        val card = root?.let { findCard(it, name) }
        if (card != null && card.first.performAction(card.second)) { j.closed++; LockTrace.log(this, "  closed $name") }
        else { j.missed++; LockTrace.log(this, "  $name wasn't in the phone's recent apps (already closed?)") }
        cover?.peel(pkg) // either way it's no longer open: its icon falls off the cube
        h.postDelayed({ safe { closeNext(j, left) } }, 400) // the card's close animation
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
        h.postDelayed({ safe { if (closeJob === j) endClose(j, null, goHome = false) } }, 1500)
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
        val wait = ((cover?.fallsEnd() ?: 0L) - SystemClock.uptimeMillis()).coerceIn(0L, 700L)
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
    private fun findCard(root: AccessibilityNodeInfo, name: String): Pair<AccessibilityNodeInfo, Int>? {
        val want = name.trim().lowercase()
        var loose: Pair<AccessibilityNodeInfo, Int>? = null
        val q = ArrayDeque<AccessibilityNodeInfo>(); q.add(root)
        var seen = 0
        while (q.isNotEmpty() && seen++ < 3000) {
            val n = q.removeFirst()
            val act = closeAction(n)
            if (act != null) {
                val names = namesOf(n)
                if (names.any { it == want }) return n to act
                if (loose == null && names.any { it.startsWith("$want,") }) loose = n to act
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
        // The phone's own recent apps, asked for on purpose (to fully close apps): leave it alone while it's open,
        // until you go back to NEXUS or into an app
        if (phoneRecentsOpen && !isRecents && leftPhoneRecents(pkg, cls)) phoneRecentsOpen = false
        if (isRecents && (phoneRecentsOpen || SystemClock.uptimeMillis() < phoneRecentsAskedUntil)) {
            if (!phoneRecentsOpen) LockTrace.log(this, "phone's own recent apps open (asked for)")
            phoneRecentsOpen = true; phoneRecentsAskedUntil = 0L
            // NEXUS opened it to close apps: let it settle, then press Close on their cards
            closeJob?.let { j -> if (j.stage == 1) { j.stage = 2; j.recentsPkg = pkg; h.postDelayed({ safe { runCloses(j) } }, 450) } }
            return
        }
        if (isRecents && takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this) && !talkBackOn()) {
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
                LockTrace.log(this, "square button: opening NEXUS Recent Apps")
                armSafety(1100, "square button: cover lifted by the 1.1s fallback (Recent Apps was slow to appear)")
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
            // Which screen of the app is showing (only real screens: some apps report a view's name instead)
            val screen = if (isActivity(pkg, cls)) cls else null
            if (pkg != current) currentCls = screen else if (screen != null) currentCls = screen
            toClose.remove(pkg) // opened again before it was closed: leave it open
            if (pkg != current) {
                persistShot()
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
            if (current != null) persistShot()
            current = null; currentCls = null; openToken++
            // Back on the NEXUS home screen straight from one of our own apps: that app closes completely
            // (not when the screen just went off and came back, and not via the square button's Recent Apps)
            // Back home at the end of closing apps fully: lower the cover, nothing else to do
            closeJob?.let { j ->
                if (pkg == packageName && cls == MainActivity::class.java.name) {
                    // Under the cube: arrived at the NEXUS home screen (step 0: now open the phone's recent apps),
                    // or back at the end (step 3: let the cube finish and fade)
                    if (j.stage == 0) proceedToRecents(j)
                    else if (j.stage == 3 && !j.backToSwitcher) h.postDelayed({ safe { endClose(j, null, goHome = false) } }, 280)
                    return
                }
                if (pkg == packageName && cls == SwitcherActivity::class.java.name && j.stage == 3 && j.backToSwitcher) {
                    h.postDelayed({ safe { endClose(j, null, goHome = false) } }, 300)
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
    private val tick = object : Runnable {
        override fun run() {
            safe { snap() }
            val saver = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
            h.postDelayed(this, if (saver) 8000L else 3000L)
        }
    }

    /** Pictures are made and saved here, one at a time (not a new thread each time). */
    private val shotThread = android.os.HandlerThread("nexus-pictures").apply { start() }
    private val shotH = Handler(shotThread.looper)
    /** The newest picture that hasn't been saved to storage yet. */
    @Volatile private var unsaved: Pair<String, Bitmap>? = null
    /** When each app's picture was last saved. */
    private val savedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Saves the newest picture now (you've left that app, or the screen turned off). */
    private fun persistShot() {
        val u = unsaved ?: return
        unsaved = null
        shotH.post { saveShot(u) }
    }

    private fun saveShot(s: Pair<String, Bitmap>) {
        try {
            val tmp = File(snapDir(this), "${s.first}.tmp")
            FileOutputStream(tmp).use { s.second.compress(Bitmap.CompressFormat.JPEG, 78, it) }
            tmp.renameTo(snapFile(this, s.first))
            savedAt[s.first] = SystemClock.uptimeMillis()
        } catch (_: Throwable) {}
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
                    shotH.post {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buf, r.colorSpace)
                            if (hw != null && current == pkg) {
                                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                val w = 400; val hh = (soft.height * (w.toFloat() / soft.width)).toInt()
                                val small = Bitmap.createScaledBitmap(soft, w, hh, true)
                                soft.recycle()
                                val shot = pkg to small
                                lastShot = shot // kept for the square-button opening (not recycled)
                                // In memory for now; saved when you leave the app, or if it's been 30 seconds
                                if (SystemClock.uptimeMillis() - (savedAt[pkg] ?: 0L) > 30_000L) { unsaved = null; saveShot(shot) }
                                else unsaved = shot
                            }
                        } catch (_: Throwable) {
                        } finally { buf.close() }
                    }
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
