package com.ashenvow.app

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
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        val hud = HudView(this, game, renderer)
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(hud)
        setContentView(root)
        window.decorView.post { hideBars() }
    }

    private fun hideBars() {
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
