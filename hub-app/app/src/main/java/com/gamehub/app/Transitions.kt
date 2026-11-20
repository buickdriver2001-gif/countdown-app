package com.gamehub.app

import android.app.ActivityOptions
import android.content.Context
import android.os.Bundle

/**
 * One transition style for every screen change in NEXUS, modelled on the NEXUS lock screen:
 * the screen you leave settles into the deep NEXUS dark, and the next one rises out of it
 * (fading up while settling from slightly smaller). Nothing pops, nothing flashes.
 */
object Transitions {
    /** Open another screen or app: it rises out of the dark over the current one. */
    fun rise(c: Context, solidSplash: Boolean = false): Bundle {
        val o = ActivityOptions.makeCustomAnimation(c, R.anim.nexus_enter, R.anim.nexus_hold)
        if (solidSplash && android.os.Build.VERSION.SDK_INT >= 33) o.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        return o.toBundle()
    }

    /**
     * Open an app from its tile or icon: the app opens out of exactly that spot, growing to fill the screen
     * (like a phone's own home screen). [r] is the tile's area within [from].
     */
    fun grow(from: android.view.View, r: android.graphics.RectF): Bundle {
        if (r.isEmpty || from.width == 0) return rise(from.context)
        return try {
            ActivityOptions.makeClipRevealAnimation(from, r.left.toInt(), r.top.toInt(), r.width().toInt().coerceAtLeast(1), r.height().toInt().coerceAtLeast(1)).toBundle()
        } catch (_: Exception) { rise(from.context) }
    }

    /** Open an app whose last picture is already filling the screen: a quick fade, so it just comes alive. */
    fun reveal(c: Context): Bundle = ActivityOptions.makeCustomAnimation(c, R.anim.nexus_enter_quick, R.anim.nexus_hold).toBundle()
}
