package com.ashenvow.app

import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private val game = Game((System.nanoTime() % 100000).toInt())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.crashFile(this).exists()) {
            startActivity(android.content.Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val renderer = GameRenderer(game)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(SmoothConfigChooser())
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        // A steady 60 frames a second (rather than the screen's top rate) keeps motion evenly paced
        if (android.os.Build.VERSION.SDK_INT >= 30) glView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(h: android.view.SurfaceHolder) {
                try { h.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Throwable) {}
            }
            override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
        })
        val hud = HudView(this, game, renderer)
        hud.onHub = { runOnUiThread { HubLink.open(this) } }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(hud)
        setContentView(root)
        // Wait for the 3D world to finish building before fading in
        LaunchFade.attach(this, "ASHEN VOW") { renderer.firstFrameDone }
        window.decorView.post { hideBars() }
    }

    private fun hideBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onPause() {
        super.onPause()
        if (game.screen == Screen.FIGHT) game.paused = true
        glView.onPause()
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (game.screen == Screen.FIGHT && !game.paused) game.paused = true
        else @Suppress("DEPRECATION") super.onBackPressed()
    }
}

/**
 * Picks the best screen setup the phone offers: smoothed (anti-aliased) edges and a precise depth buffer
 * when available, so swords and pillars don't shimmer as things move. Falls back step by step to exactly
 * what Android would pick by default, so it can never fail on a phone where the game ran before.
 */
private class SmoothConfigChooser : android.opengl.GLSurfaceView.EGLConfigChooser {
    private val es2 = 4 // EGL_OPENGL_ES2_BIT

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        // (anti-aliasing samples, minimum depth bits), best first
        for ((samples, depth) in listOf(4 to 24, 4 to 16, 0 to 24, 0 to 16)) {
            val attrs = ArrayList<Int>()
            attrs += listOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, es2)
            if (samples > 0) attrs += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            attrs += EGL10.EGL_NONE
            // Same colour format Android uses by default: 8-bit red/green/blue, no alpha
            pick(egl, display, attrs.toIntArray()) { c ->
                attr(egl, display, c, EGL10.EGL_RED_SIZE) == 8 && attr(egl, display, c, EGL10.EGL_GREEN_SIZE) == 8 &&
                    attr(egl, display, c, EGL10.EGL_BLUE_SIZE) == 8 && attr(egl, display, c, EGL10.EGL_ALPHA_SIZE) == 0 &&
                    attr(egl, display, c, EGL10.EGL_DEPTH_SIZE) >= depth
            }?.let { return it }
        }
        // Last resort: anything that can draw the game
        return pick(egl, display, intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es2, EGL10.EGL_NONE)) { true }
            ?: throw IllegalArgumentException("No usable screen setup")
    }

    private fun pick(egl: EGL10, display: EGLDisplay, attrs: IntArray, ok: (EGLConfig) -> Boolean): EGLConfig? {
        val num = IntArray(1)
        if (!egl.eglChooseConfig(display, attrs, null, 0, num) || num[0] <= 0) return null
        val cfgs = arrayOfNulls<EGLConfig>(num[0])
        if (!egl.eglChooseConfig(display, attrs, cfgs, num[0], num)) return null
        return cfgs.firstOrNull { it != null && ok(it) }
    }

    private fun attr(egl: EGL10, d: EGLDisplay, c: EGLConfig, a: Int): Int {
        val v = IntArray(1)
        return if (egl.eglGetConfigAttrib(d, c, a, v)) v[0] else 0
    }
}
