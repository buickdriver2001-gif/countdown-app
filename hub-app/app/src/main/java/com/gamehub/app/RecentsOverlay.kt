package com.gamehub.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * NEXUS Recent Apps shown in NEXUS's own layer, right over the app you're in: it appears at once (no screen
 * for Android to open), and the app stays just underneath, so Back simply fades Recent Apps away. Opening
 * another app or going home happens underneath, and Recent Apps fades away once that's in place (so the
 * phone's own slide is never seen).
 *
 * Safety: never over the lock screen; gone the moment the screen turns off; steps aside at once for a call,
 * an alarm or timer going off, or any other app that comes in front; a swipe down from the top edge opens the
 * notification shade; and if it's left untouched for two minutes it closes itself.
 */
class RecentsOverlay(private val svc: NexusAccessibility) : SwitcherHost {
    override val ctx: Context get() = svc
    override var coverPending = false
    override var zoomHandover = false

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var sfxMade: Sfx? = null
    private val sfx: Sfx get() = sfxMade ?: Sfx().also { sfxMade = it }
    private var root: Root? = null
    private var rootLp: WindowManager.LayoutParams? = null
    /** The layer has been added to the screen (it stays, hidden, between uses: see [park]). */
    private var attached = false
    /** Recent Apps is on screen (showing, or fading out). */
    private var up = false
    private var view: SwitcherView? = null

    /** Recent Apps is up (and not on its way out). */
    var showing = false
        private set
    private var leaving = false
    /** The app (or NEXUS home) that was in front when Recent Apps came up. */
    private var openedOver: String? = null

    // Waiting for something to come up underneath before fading away
    private var waitFor: String? = null
    private var waitAt = 0L
    private var seenAt = 0L
    private var fromWin = Int.MIN_VALUE

    private inner class Root(c: Context) : FrameLayout(c) {
        private val edge = run {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            (if (id > 0) resources.getDimensionPixelSize(id) else 0).coerceAtLeast((24 * resources.displayMetrics.density).toInt()) +
                (8 * resources.displayMetrics.density).toInt()
        }
        private val slop = 20 * resources.displayMetrics.density
        private var downX = 0f
        private var downY = -1f

        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            // Still in use: the two-minute close waits
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && showing && waitFor == null) {
                h.removeCallbacks(idleClose); h.postDelayed(idleClose, IDLE_MS)
            }
            return super.dispatchTouchEvent(ev)
        }

        /** A swipe down from the top edge (where the status bar is): the notification shade, as anywhere else. */
        override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = if (ev.y <= edge) ev.y else -1f }
                android.view.MotionEvent.ACTION_MOVE -> if (downY >= 0f) {
                    val dy = ev.y - downY; val dx = kotlin.math.abs(ev.x - downX)
                    if (dy > slop && dy > dx) {
                        downY = -1f
                        svc.safeRun { hide(false); svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> downY = -1f
            }
            return false
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) svc.safeRun { back() }
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    /** Shows Recent Apps over whatever is in front. False if it can't (the screen of its own is used instead). */
    fun show(): Boolean {
        if (showing) return true
        if (svc.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return false
        if (svc.getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return false
        // No apps open: an empty Recent Apps is no use, so the home screen instead
        if (!SwitcherView.anyOpen(svc)) { nothingOpen(); return true }
        val wm = svc.getSystemService(WindowManager::class.java) ?: return false
        // One layer, kept between uses (taking a layer off the screen can flash its last picture on some phones)
        val r = root ?: Root(svc).also { root = it }
        r.animate().cancel()
        r.removeAllViews()
        val v = SwitcherView(this, sfx)
        r.addView(v)
        val order = NavBar.order(svc)
        if (order != "off") {
            val nav = NavBar(svc, onBack = { svc.safeRun { back() } }, onHome = { svc.safeRun { sfx.tick(); goNexus() } }, onRecent = { v.openCentered() })
            r.addView(nav, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, NavBar.heightPx(svc), android.view.Gravity.BOTTOM))
            v.navInset = NavBar.heightPx(svc).toFloat()
        }
        val lp = rootLp ?: WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).also {
            if (Build.VERSION.SDK_INT >= 30) it.fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 28) it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            it.title = "NEXUS Recent Apps"
            it.windowAnimations = 0
            rootLp = it
        }
        // Takes touches and keys while it's up
        lp.flags = lp.flags and (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).inv()
        openedOver = svc.topAppPackage()
        r.alpha = 0f
        r.visibility = View.VISIBLE
        try {
            if (!attached) { wm.addView(r, lp); attached = true } else wm.updateViewLayout(r, lp)
        } catch (t: Throwable) { LockTrace.log(svc, "Recent Apps layer couldn't open: " + t.javaClass.simpleName); attached = false; return false }
        up = true
        view = v
        showing = true; leaving = false; waitFor = null
        v.load()
        // A quick fade over the app; the cards glide in at the same time
        r.animate().alpha(1f).setDuration(120).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        r.requestFocus()
        h.removeCallbacks(idleClose); h.postDelayed(idleClose, IDLE_MS)
        // Calls and alarms: what's sounding right now doesn't count (e.g. a call you're already on)
        startMode = audio()?.mode ?: android.media.AudioManager.MODE_NORMAL
        alarmPlayers = alarmPlayerCount()
        h.removeCallbacks(guard); h.postDelayed(guard, GUARD_MS)
        return true
    }

    /** Back on NEXUS's Recent button while Recent Apps is up: the card in the middle opens. */
    fun recentPressed() { if (showing && !leaving) view?.openCentered() }

    /** The helper is stopping: Recent Apps goes, and its sounds are let go. */
    fun destroy() {
        hide(false)
        detach()
        h.removeCallbacks(guard)
        sfxMade?.release(); sfxMade = null
    }

    // ------------------------------------------------------------------ calls and alarms

    private var startMode = android.media.AudioManager.MODE_NORMAL
    private var alarmPlayers = 0
    private fun audio() = svc.getSystemService(android.media.AudioManager::class.java)

    /**
     * How many alarm or ringtone players the phone has. (Android lists ones that are only set up and not
     * sounding too, so it's a new one appearing that counts: an alarm, a timer or a call starting to ring.)
     */
    private fun alarmPlayerCount(): Int = try {
        audio()?.activePlaybackConfigurations?.count {
            val u = it.audioAttributes.usage
            u == android.media.AudioAttributes.USAGE_ALARM || u == android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE
        } ?: 0
    } catch (_: Throwable) { 0 }

    /**
     * A call coming in (or starting), or an alarm or timer going off, while Recent Apps is up: it steps aside at
     * once so you see it (they show as a pop-up at the top, which Recent Apps would otherwise be covering).
     */
    private val guard = object : Runnable {
        override fun run() {
            svc.safeRun {
                if (!showing || !up) return@safeRun
                val am = audio()
                val mode = am?.mode ?: android.media.AudioManager.MODE_NORMAL
                val callNow = mode != startMode && (mode == android.media.AudioManager.MODE_RINGTONE ||
                    mode == android.media.AudioManager.MODE_IN_CALL || mode == android.media.AudioManager.MODE_IN_COMMUNICATION)
                val players = alarmPlayerCount()
                val alarm = players > alarmPlayers
                alarmPlayers = players
                if (callNow || alarm) {
                    LockTrace.log(svc, "Recent Apps stepped aside: " + (if (callNow) "call" else "alarm or ringtone"))
                    hide(false)
                    return@safeRun
                }
                startMode = mode
                h.postDelayed(this, GUARD_MS)
            }
        }
    }

    /** Fades Recent Apps away (or removes it at once). */
    fun hide(animated: Boolean = true) {
        val r = root ?: return
        if (!up) return
        showing = false; leaving = false; waitFor = null; afterCloses = null
        h.removeCallbacks(waitRun); h.removeCallbacks(idleClose); h.removeCallbacks(guard); h.removeCallbacks(closesTimeout)
        // Touches go straight through from now on (to whatever is underneath), even while it fades
        passThrough()
        if (!animated) { park(); return }
        r.animate().cancel()
        r.animate().alpha(0f).setDuration(150).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { park() }.start()
    }

    private fun passThrough() {
        val r = root ?: return; val lp = rootLp ?: return
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try { svc.getSystemService(WindowManager::class.java)?.updateViewLayout(r, lp) } catch (_: Throwable) {}
    }

    /**
     * Out of sight until next time: one clear frame first, then hidden (so it never shows anything old, now or
     * when it comes back) - the layer itself stays, letting every touch through.
     */
    private fun park() {
        val r = root ?: return
        up = false
        r.animate().cancel()
        r.alpha = 0f
        passThrough()
        r.postOnAnimation { if (!up) { r.visibility = View.GONE; r.removeAllViews(); view = null } }
    }

    /** Really takes the layer off the screen (only when the helper stops). */
    private fun detach() {
        val r = root ?: return
        try { svc.getSystemService(WindowManager::class.java)?.removeView(r) } catch (_: Throwable) {}
        root = null; rootLp = null; attached = false; up = false; view = null
    }

    private val idleClose = Runnable { svc.safeRun { if (showing && waitFor == null) hide(true) } }

    /** Back: to the app you were in (it's usually right underneath), or the home screen if it was opened there. */
    fun back() {
        if (!showing || leaving) return
        val under = svc.topAppPackage()
        val first = Recents.list(svc).firstOrNull()?.first
        val canOpen = first != null && svc.packageManager.getLaunchIntentForPackage(first) != null
        // Apps closing underneath right now (the phone's recent apps is behind): go where Back leads once they're done
        if (NexusAccessibility.closingUnder) { if (canOpen && openedOver != svc.packageName) view?.openWithZoom(first!!) else goNexus(); return }
        when {
            // Still right there underneath (the app you were in, or the home screen): just fade away
            under == first || (openedOver == svc.packageName && under == svc.packageName) -> hide(true)
            canOpen && openedOver != svc.packageName -> view?.openWithZoom(first!!)
            // Opened over the home screen (or nothing to go back to): back to the home screen
            else -> goNexus()
        }
    }

    /**
     * Something else came in front (a call, an alarm, another app): Recent Apps steps aside at once. Apps it's
     * waiting for, the phone's own launcher and NEXUS itself don't count.
     */
    fun onWindowEvent(pkg: String, cls: String, homeLike: Boolean) {
        if (!showing) return
        val w = waitFor
        if (w != null) {
            if (pkg == w && (w != svc.packageName || cls == MainActivity::class.java.name)) seen()
            return
        }
        // What Recent Apps put back underneath after closing apps (see restoreUnder)
        if (pkg == restoring) { restoring = null; return }
        // The NEXUS home screen came up some other way (e.g. the phone's Home, with NEXUS starting afresh)
        if (pkg == svc.packageName) { if (cls == MainActivity::class.java.name && openedOver != svc.packageName) hide(true); return }
        if (homeLike || pkg == "com.android.systemui" || "inputmethod" in pkg || "keyboard" in pkg) return
        if (pkg == openedOver) return
        hide(true)
    }

    // ------------------------------------------------------------------ SwitcherHost

    override fun gone() = !showing || leaving
    override fun focused() = true

    override fun openApp(pkg: String, revealed: Boolean, picture: Bitmap?): Boolean {
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        // Apps still closing underneath: open it the moment they're done (leaving now would stop them closing).
        // Its card already fills the screen meanwhile.
        if (whenClosed { openApp(pkg, revealed, picture) }) return true
        // Already right underneath: just fade away
        if (svc.topAppPackage() == pkg) { hide(true); return true }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            fromWin = svc.topAppWindowId()
            svc.startActivity(i)
            awaitThenFade(pkg)
            true
        } catch (_: Exception) { false }
    }

    /** Recent Apps asked for with no apps open: the home screen (which says "No open apps" for a moment). */
    private fun nothingOpen() {
        HubView.noteNothingOpen()
        if (svc.topAppPackage() == svc.packageName) { LockTrace.log(svc, "Recent Apps: no apps open (stayed on the home screen)"); return }
        LockTrace.log(svc, "Recent Apps: no apps open, so the home screen")
        svc.goHome()
    }

    override fun goNexus() {
        val i = svc.packageManager.getLaunchIntentForPackage(svc.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("fromApp", true)
        // Apps swiped away that haven't started closing yet: close them now, underneath, then go home
        if (!forceGo && !NexusAccessibility.closingUnder && !NexusAccessibility.clearAllPending) NexusAccessibility.closeUnder()
        // Still closing underneath: the cards fade away, and home comes the moment they're done
        if (whenClosed { goNexus() }) { view?.leavingOut = true; return }
        // Clear all: the closing cube takes over at once (the home screen comes up under it)
        if (NexusAccessibility.closeNow(returnToSwitcher = false)) {
            try { svc.startActivity(i) } catch (_: Exception) {}
            hide(false)
            return
        }
        // Opened over the home screen and it's still right there: just fade away
        if (openedOver == svc.packageName && svc.topAppPackage() == svc.packageName) { hide(true); return }
        try {
            fromWin = svc.topAppWindowId()
            svc.startActivity(i)
            awaitThenFade(svc.packageName)
        } catch (_: Exception) { hide(true) }
    }

    override fun closeApp(pkg: String, queue: Boolean): Boolean {
        val own = pkg in NexusAccessibility.OWN_APPS
        if (own) NexusAccessibility.closeOwnApp(svc, pkg)
        // (Only has an effect on older Android versions, and only for apps already in the background)
        try { (svc.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).killBackgroundProcesses(pkg) } catch (_: Exception) {}
        Recents.remove(svc, pkg)
        if (own) return true
        if (!queue || !NexusAccessibility.closeFully(listOf(pkg), all = false)) return false
        // Closed right away, underneath Recent Apps (it stays live meanwhile)
        NexusAccessibility.closeUnder()
        return true
    }

    // ------------------------------------------------------------------ waiting for apps closing underneath

    private var afterCloses: (() -> Unit)? = null
    /** Waited long enough: go ahead anyway (anything not closed yet is closed next time). */
    private var forceGo = false

    /** If apps are still closing underneath, runs [action] once they're done (true) - otherwise false. */
    private fun whenClosed(action: () -> Unit): Boolean {
        if (forceGo || !NexusAccessibility.closingUnder) return false
        leaving = true
        afterCloses = action
        h.removeCallbacks(closesTimeout); h.postDelayed(closesTimeout, CLOSE_WAIT_MAX)
        return true
    }

    /** The apps underneath are done closing (or it stopped): carry on with what you pressed meanwhile. */
    fun closesDone() {
        val act = afterCloses ?: run { restoreUnder(); return }
        afterCloses = null
        h.removeCallbacks(closesTimeout)
        if (!up) return
        leaving = false
        svc.safeRun { act() }
    }

    /** What was put back underneath after closing (its screen coming up isn't "another app came in front"). */
    private var restoring: String? = null

    /**
     * Apps finished closing and you're still in Recent Apps: what you were in goes back underneath (the app, or
     * the home screen if that app was one you closed), so Back is instant again and the phone's own recent apps
     * (opened behind Recent Apps to close them) is never what's left there.
     */
    private fun restoreUnder() {
        if (!showing || leaving || !up || waitFor != null) return
        val over = openedOver ?: return
        val ours = svc.packageName
        val under = svc.topAppPackage()
        if (!svc.isPhoneLauncher(under)) return // (the phone's recent apps isn't what's underneath)
        val target = when {
            over == ours -> ours
            Recents.list(svc).any { it.first == over } -> over
            svc.isPhoneLauncher(over) || over == "com.android.systemui" -> return // (opened over the phone's own recent apps)
            else -> ours // the app you were in was closed: the home screen
        }
        val i = svc.packageManager.getLaunchIntentForPackage(target) ?: return
        if (target == ours) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("fromApp", true)
        else i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        restoring = target; openedOver = target
        try { svc.startActivity(i) } catch (_: Exception) { restoring = null }
    }

    private val closesTimeout = Runnable {
        val act = afterCloses ?: return@Runnable
        afterCloses = null
        if (!up) return@Runnable
        LockTrace.log(svc, "Recent Apps: didn't wait any longer for apps closing")
        leaving = false; forceGo = true
        try { svc.safeRun { act() } } finally { forceGo = false }
    }

    override fun explainOnce(after: (() -> Unit)?): Boolean {
        svc.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("closeNoteSeen", true).apply()
        return false
    }

    override fun openSetting(action: String) {
        try { svc.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
        hide(false)
    }

    override fun openAppInfo() {
        try {
            svc.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + svc.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        hide(false)
    }

    override fun openPhoneRecents() { if (NexusAccessibility.openPhoneRecentsViaHome()) goNexus() }

    // ------------------------------------------------------------------ fading away once the new screen is there

    private fun awaitThenFade(target: String) {
        leaving = true
        waitFor = target; waitAt = SystemClock.uptimeMillis(); seenAt = 0L
        h.removeCallbacks(waitRun); h.postDelayed(waitRun, 40)
    }

    private fun seen() {
        if (seenAt != 0L) return
        seenAt = SystemClock.uptimeMillis()
        h.removeCallbacks(waitRun); h.postDelayed(waitRun, HOLD)
    }

    private val waitRun = object : Runnable {
        override fun run() {
            svc.safeRun {
                if (waitFor == null || !up) return@safeRun
                val now = SystemClock.uptimeMillis()
                if (seenAt == 0L) { val w = svc.topAppWindowId(); if (w != Int.MIN_VALUE && w != fromWin) seenAt = now }
                when {
                    seenAt != 0L && now - seenAt >= HOLD -> hide(true)
                    seenAt == 0L && now - waitAt >= NexusAccessibility.VEIL_MAX -> hide(true)
                    seenAt != 0L -> h.postDelayed(this, HOLD - (now - seenAt))
                    else -> h.postDelayed(this, 40)
                }
            }
        }
    }

    companion object {
        /** After the new screen is in place, before fading away (the phone's own slide underneath is all but done). */
        private const val HOLD = 170L
        /** Left untouched this long, Recent Apps closes itself. */
        private const val IDLE_MS = 120_000L
        /** How often it checks for a call or an alarm while it's up. */
        private const val GUARD_MS = 400L
        /** The longest Recent Apps waits for apps closing underneath before carrying on. */
        private const val CLOSE_WAIT_MAX = 2500L
    }
}
