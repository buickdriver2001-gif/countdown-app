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
 * an alarm or any other app that comes in front; and if it's left alone for two minutes it closes itself.
 */
class RecentsOverlay(private val svc: NexusAccessibility) : SwitcherHost {
    override val ctx: Context get() = svc
    override var coverPending = false
    override var zoomHandover = false

    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private val sfx by lazy { Sfx() }
    private var root: Root? = null
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
        val wm = svc.getSystemService(WindowManager::class.java) ?: return false
        dropNow()
        val r = Root(svc)
        val v = SwitcherView(this, sfx)
        r.addView(v)
        val order = NavBar.order(svc)
        if (order != "off") {
            val nav = NavBar(svc, onBack = { svc.safeRun { back() } }, onHome = { svc.safeRun { sfx.tick(); goNexus() } }, onRecent = { v.openCentered() })
            r.addView(nav, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, NavBar.heightPx(svc), android.view.Gravity.BOTTOM))
            v.navInset = NavBar.heightPx(svc).toFloat()
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT)
        if (Build.VERSION.SDK_INT >= 30) lp.fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        lp.title = "NEXUS Recent Apps"
        openedOver = svc.topAppPackage()
        r.alpha = 0f
        try { wm.addView(r, lp) } catch (t: Throwable) { LockTrace.log(svc, "Recent Apps layer couldn't open: " + t.javaClass.simpleName); return false }
        root = r; view = v
        showing = true; leaving = false; waitFor = null
        v.load()
        // A quick fade over the app; the cards glide in at the same time
        r.animate().alpha(1f).setDuration(120).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        r.requestFocus()
        h.removeCallbacks(idleClose); h.postDelayed(idleClose, 120_000)
        return true
    }

    /** Fades Recent Apps away (or removes it at once). */
    fun hide(animated: Boolean = true) {
        val r = root ?: return
        showing = false; leaving = false; waitFor = null
        h.removeCallbacks(waitRun); h.removeCallbacks(idleClose)
        if (!animated) { remove(r); return }
        r.animate().cancel()
        r.animate().alpha(0f).setDuration(150).setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { remove(r) }.start()
    }

    private fun dropNow() { root?.let { remove(it) } }
    private fun remove(r: View) {
        try { svc.getSystemService(WindowManager::class.java)?.removeView(r) } catch (_: Throwable) {}
        if (root === r) { root = null; view = null }
    }

    private val idleClose = Runnable { svc.safeRun { if (showing && waitFor == null) hide(true) } }

    /** Back: to the app you were in (it's usually right underneath), or the home screen if it was opened there. */
    fun back() {
        if (!showing || leaving) return
        val under = svc.topAppPackage()
        val first = Recents.list(svc).firstOrNull()?.first
        if (openedOver == svc.packageName || under == first || first == null || svc.packageManager.getLaunchIntentForPackage(first) == null) {
            hide(true)
        } else view?.openWithZoom(first)
    }

    /**
     * Something else came in front (a call, an alarm, another app): Recent Apps steps aside at once. Apps it's
     * waiting for, the phone's own launcher and NEXUS itself don't count.
     */
    fun onWindowEvent(pkg: String, homeLike: Boolean) {
        if (!showing) return
        val w = waitFor
        if (w != null) {
            if ((w == svc.packageName && pkg == svc.packageName) || pkg == w) seen()
            return
        }
        if (pkg == svc.packageName || homeLike || pkg == "com.android.systemui" || "inputmethod" in pkg || "keyboard" in pkg) return
        if (pkg == openedOver) return
        hide(true)
    }

    // ------------------------------------------------------------------ SwitcherHost

    override fun gone() = !showing || leaving
    override fun focused() = true

    override fun openApp(pkg: String, revealed: Boolean, picture: Bitmap?): Boolean {
        val i = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return false
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

    override fun goNexus() {
        val i = svc.packageManager.getLaunchIntentForPackage(svc.packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("fromApp", true)
        // Apps swiped away close fully on the way: the closing cover (showing the home screen) takes over at once
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
        Recents.remove(svc, pkg)
        if (own) return true
        return queue && NexusAccessibility.closeFully(listOf(pkg), all = false)
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
                if (waitFor == null || root == null) return@safeRun
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
    }
}
