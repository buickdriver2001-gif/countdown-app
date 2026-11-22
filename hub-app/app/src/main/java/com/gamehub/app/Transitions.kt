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

    // Opening ANOTHER app: Android only honours a few kinds of opening animation from a launcher there. A custom
    // one like rise() is replaced by the phone's own slide-in (the app slides in shrunk and snaps to full size),
    // so other apps always get grow() (out of a tile or icon), app() or reveal().

    /** Open another app with no tile or card to come out of: it zooms up gently from slightly smaller as it fades in. */
    fun app(from: android.view.View, solidSplash: Boolean = false): Bundle {
        val w = from.width; val h = from.height
        if (w == 0 || h == 0) return rise(from.context, solidSplash)
        val sw = (w * 0.86f).toInt(); val sh = (h * 0.86f).toInt()
        return try {
            val o = ActivityOptions.makeScaleUpAnimation(from, (w - sw) / 2, (h - sh) / 2, sw, sh)
            if (solidSplash && android.os.Build.VERSION.SDK_INT >= 33) o.setSplashScreenStyle(android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
            o.toBundle()
        } catch (_: Exception) { rise(from.context, solidSplash) }
    }

    /** Open an app whose card already fills the screen: it simply fades in over its card, nothing moves. */
    fun reveal(from: android.view.View): Bundle {
        if (from.width == 0 || from.height == 0) return rise(from.context)
        return try { ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height).toBundle() }
        catch (_: Exception) { rise(from.context) }
    }
}
