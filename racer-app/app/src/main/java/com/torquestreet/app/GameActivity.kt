package com.torquestreet.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.torquestreet.app.core.CarShape
import com.torquestreet.app.core.Cars
import com.torquestreet.app.core.Env
import com.torquestreet.app.core.Layer
import com.torquestreet.app.core.Mesh
import com.torquestreet.app.core.Mode
import com.torquestreet.app.core.Race
import com.torquestreet.app.core.RaceState
import com.torquestreet.app.core.TexGen
import com.torquestreet.app.core.Track
import com.torquestreet.app.core.World
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** A race: loads the track (with a progress screen), then runs it with the HUD, controls and sound. */
class GameActivity : Activity(), SensorEventListener {
    private lateinit var prefs: Prefs
    private val controls = Controls()
    private val hud = HudState()
    private val sound = SoundState()
    private var engine: SoundEngine? = null
    private var glView: GLSurfaceView? = null
    private var renderer: GameRenderer? = null
    private var hudView: HudView? = null
    private lateinit var root: FrameLayout
    private lateinit var panel: PanelView
    private lateinit var loading: LoadingView
    private var loaded: Loaded? = null
    private var track: Track? = null
    private val h = Handler(Looper.getMainLooper())
    private var endAt = 0L
    private var endShown = false
    private var tiltF = 0f
    private var sensors: SensorManager? = null
    @Volatile private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Haptics.enabled = prefs.vibrate
        sound.volume = prefs.sound
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this)
        loading = LoadingView(this, Track.all[prefs.track].name)
        panel = PanelView(this)
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersive()
        Thread({ load() }, "loader").apply { priority = Thread.MAX_PRIORITY }.start()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    // ------------------------------------------------------------------ loading

    private fun step(text: String, k: Float) = runOnUiThread { loading.set(text, k) }

    private fun load() {
        try {
            val tid = prefs.track
            step("Laying the road", 0.05f)
            val tr = Track.build(tid)
            track = tr
            step(when (tr.scene) { com.torquestreet.app.core.Scene.CITY -> "Building the city"; com.torquestreet.app.core.Scene.COAST -> "Shaping the coast"; else -> "Raising the mountains" }, 0.15f)
            val world = World(tr).build()
            step("Painting the textures", 0.45f)
            val size = if (prefs.quality == 0) 256 else 512
            val tex = textures(tr.scene, size)
            step("Bringing out the cars", 0.85f)
            val env = Env(prefs.time, prefs.weather, tr.scene)
            val spec = Cars.player[prefs.car]
            val race = Race(tr, prefs.mode, spec, spec.paints[prefs.paint.coerceIn(0, spec.paints.size - 1)], env, System.nanoTime())
            val meshes = HashMap<CarShape, Mesh>()
            for (s in Cars.player.map { it.shape } + Cars.trafficShapes) meshes[s] = Cars.mesh(s)
            val cop = Cars.mesh(Cars.INTERCEPTOR, police = true)
            val L = Loaded(world, tex, size, env, race, meshes, cop)
            step("Ready", 1f)
            runOnUiThread { if (!destroyed) start(L) }
        } catch (t: Throwable) {
            App.saveCrash(this, "loading the race", t)
            runOnUiThread { showError("Couldn't load the race", t.message ?: t.toString()) }
        }
    }

    /** The textures: painted once, then kept on the phone so the next race starts quicker. */
    private fun textures(scene: com.torquestreet.app.core.Scene, size: Int): Array<ByteArray> {
        val file = File(filesDir, "tex_v1_${scene.ordinal}_$size.bin")
        val layer = size * size * 4
        if (file.length() == (layer * Layer.COUNT).toLong()) {
            try {
                val all = file.readBytes()
                return Array(Layer.COUNT) { all.copyOfRange(it * layer, (it + 1) * layer) }
            } catch (_: Throwable) {}
        }
        val out = Array(Layer.COUNT) { i ->
            step("Painting the textures", 0.45f + 0.38f * i / Layer.COUNT)
            TexGen.layer(i, scene, size)
        }
        try { file.outputStream().use { o -> for (l in out) o.write(l) } } catch (_: Throwable) { file.delete() }
        return out
    }

    private fun start(L: Loaded) {
        loaded = L
        val gv = GLSurfaceView(this)
        gv.setEGLContextClientVersion(3)
        gv.setEGLConfigChooser(8, 8, 8, 0, 24, 0)
        gv.preserveEGLContextOnPause = true
        // Drawn at a lower resolution on the smoother settings, then scaled up
        val dm = resources.displayMetrics
        val sw = max(dm.widthPixels, dm.heightPixels); val sh = min(dm.widthPixels, dm.heightPixels)
        val scale = floatArrayOf(0.6f, 0.78f, 1f)[prefs.quality.coerceIn(0, 2)]
        gv.holder.setFixedSize((sw * scale).toInt(), (sh * scale).toInt())
        val r = GameRenderer(this, L, controls, prefs, hud, sound) { msg -> runOnUiThread { showError("Graphics problem", msg) } }
        renderer = r
        gv.setRenderer(r)
        gv.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        glView = gv
        val hv = HudView(this, prefs, hud, controls, L.race.track, { togglePause() }, { switchCamera() })
        hudView = hv
        root.addView(gv, 0, FrameLayout.LayoutParams(-1, -1))
        root.addView(hv, 1, FrameLayout.LayoutParams(-1, -1))
        loading.animate().alpha(0f).setStartDelay(250).setDuration(500).withEndAction { root.removeView(loading) }.start()
        engine = SoundEngine(sound).also { it.start() }
        registerTilt()
        h.post(watch)
    }

    // ------------------------------------------------------------------ tilt steering

    private fun registerTilt() {
        if (!prefs.tilt) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        sensors = sm
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]
        @Suppress("DEPRECATION")
        val rot = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_90 else windowManager.defaultDisplay.rotation
        // Turning the phone like a steering wheel (landscape either way round)
        val ang = Math.toDegrees((if (rot == Surface.ROTATION_270) atan2(-gy, -gx) else atan2(gy, gx)).toDouble()).toFloat()
        val dead = 2.5f
        val a = if (abs(ang) < dead) 0f else (abs(ang) - dead) * Math.signum(ang)
        var steer = (a / (26f / prefs.tiltSens)).coerceIn(-1f, 1f)
        if (prefs.tiltInvert) steer = -steer
        tiltF += (steer - tiltF) * 0.35f
        hudView?.tiltSteer = tiltF
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    // ------------------------------------------------------------------ pause, results

    private fun switchCamera() {
        val r = renderer ?: return
        r.cameraMode = (r.cameraMode + 1) % 2
        prefs.camera = r.cameraMode
    }

    private fun togglePause() { if (panel.showing && !endShown) resume() else pause() }

    private fun pause() {
        val r = renderer ?: return
        if (endShown) return
        r.paused = true; sound.paused = true
        controls.gas = 0f; controls.brake = 0f; controls.nitro = false
        hudView?.visibility = View.INVISIBLE
        panel.show("PAUSED", Color.WHITE, listOf(Track.all[prefs.track].name + "  ·  " + prefs.mode.label), listOf(
            "Resume" to { resume() },
            "Restart" to { recreate() },
            (if (prefs.tilt) "Steering: Tilt" else "Steering: Buttons") to { prefs.tilt = !prefs.tilt; recreateKeepingPause() },
            "Quit to menu" to { finish() }))
    }

    /** The steering style changes the controls' layout: start the race over with it. */
    private fun recreateKeepingPause() { recreate() }

    private fun resume() {
        panel.hide()
        hudView?.resetTouches()
        hudView?.visibility = View.VISIBLE
        renderer?.paused = false; sound.paused = false
    }

    private val watch = object : Runnable {
        override fun run() {
            if (destroyed) return
            val f = HudFrame(); hud.read(f)
            val over = f.state == RaceState.FINISHED || f.state == RaceState.BUSTED || f.state == RaceState.ESCAPED
            if (over && endAt == 0L) endAt = System.currentTimeMillis()
            if (over && !endShown && System.currentTimeMillis() - endAt > 2600) showResults(f)
            h.postDelayed(this, 200)
        }
    }

    private fun showResults(f: HudFrame) {
        endShown = true
        sound.paused = false
        val L = loaded ?: return
        val race = L.race
        val units = if (prefs.mph) "mph" else "km/h"
        val top = (f.topSpeed * 3.6f * (if (prefs.mph) 0.6214f else 1f)).toInt()
        val (title, color, lines) = when (f.state) {
            RaceState.FINISHED -> {
                val best = prefs.best(prefs.track, prefs.mode)
                val newBest = best == 0f || f.time < best
                if (newBest) prefs.setBest(prefs.track, prefs.mode, f.time)
                Triple(Race.ordinal(f.pos) + " PLACE", if (f.pos == 1) Ui.GOLD else Color.WHITE, listOf(
                    "Time " + Race.fmtTime(f.time) + (if (newBest) "   ·   new record" else ""),
                    "Best lap " + (if (f.best > 0f) Race.fmtTime(f.best) else "-") + "   ·   Top speed $top $units",
                    if (f.heat > 0) "Heat level ${f.heat}" + (if (f.evaded > 0) "   ·   escaped the police ${f.evaded}x" else "") else "No trouble with the police"))
            }
            RaceState.ESCAPED -> Triple("ESCAPED", 0xFF66FF99.toInt(), listOf("You lost them in " + Race.fmtTime(f.time), "Heat level ${f.heat}   ·   Top speed $top $units"))
            else -> Triple("BUSTED", 0xFFFF4A4A.toInt(), listOf(if (race.mode == Mode.RACE) "The police stopped you mid-race" else "They caught you after " + Race.fmtTime(f.time), "Heat level ${f.heat}   ·   Top speed $top $units"))
        }
        renderer?.paused = true
        hudView?.visibility = View.INVISIBLE
        val buttons = ArrayList<Pair<String, () -> Unit>>()
        buttons.add((if (race.mode == Mode.RACE) "Race again" else "Try again") to { recreate() })
        buttons.add("Change car or track" to { finish() })
        if (HubLink.installed(this)) buttons.add("Return to NEXUS" to { HubLink.open(this) })
        panel.show(title, color, lines, buttons)
    }

    private fun showError(title: String, msg: String) {
        hudView?.visibility = View.INVISIBLE
        panel.show(title, 0xFFFF6A50.toInt(), listOf(msg.take(90), "Please send a screenshot of this so it can be fixed."), listOf("Back to menu" to { finish() }))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            endShown -> finish()
            panel.showing -> resume()
            renderer != null -> pause()
            else -> finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (renderer != null && !endShown && !panel.showing) pause()
        glView?.onPause()
        sound.paused = true
        sensors?.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
        if (sensors != null) registerTilt()
        immersive()
    }

    override fun onDestroy() {
        destroyed = true
        h.removeCallbacksAndMessages(null)
        engine?.shutdown()
        sensors?.unregisterListener(this)
        super.onDestroy()
    }
}

/** The loading screen: the track's name and how far along it is. */
@SuppressLint("ViewConstructor")
class LoadingView(c: Context, private val trackName: String) : View(c) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var text = "Loading"; private var k = 0f; private var shown = 0f
    private val r = RectF()
    fun set(t: String, v: Float) { text = t; k = v; invalidate() }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF05070C.toInt())
        val w = width.toFloat(); val h = height.toFloat()
        shown += (k - shown) * 0.15f
        p.typeface = Ui.bold; p.textAlign = Paint.Align.CENTER
        p.textSize = 22 * d; p.color = Ui.ORANGE
        c.drawText("TORQUE STREET", w / 2, h * 0.36f, p)
        p.textSize = 34 * d; p.color = Color.WHITE
        c.drawText(trackName.uppercase(), w / 2, h * 0.36f + 44 * d, p)
        val bw = w * 0.4f
        p.color = Color.argb(60, 255, 255, 255); r.set(w / 2 - bw / 2, h * 0.62f, w / 2 + bw / 2, h * 0.62f + 5 * d); c.drawRoundRect(r, 3 * d, 3 * d, p)
        p.color = Ui.ORANGE; r.set(w / 2 - bw / 2, h * 0.62f, w / 2 - bw / 2 + bw * shown, h * 0.62f + 5 * d); c.drawRoundRect(r, 3 * d, 3 * d, p)
        p.typeface = Ui.reg; p.textSize = 15 * d; p.color = Color.argb(200, 220, 228, 245)
        c.drawText(text, w / 2, h * 0.62f + 30 * d, p)
        p.textSize = 13 * d; p.color = Color.argb(150, 200, 210, 230)
        c.drawText(TIPS[(System.currentTimeMillis() / 4000 % TIPS.size).toInt()], w / 2, h * 0.86f, p)
        postInvalidateOnAnimation()
    }

    companion object {
        val TIPS = listOf(
            "Drifting through corners fills your nitro.",
            "Fly past a parked police car and they'll come after you.",
            "Lose the police: get far enough ahead and stay out of sight.",
            "Stopped with the police on you? That's busted. Keep moving.",
            "Roadblocks always leave a gap: look for it.",
            "Wet roads have less grip: brake earlier in the rain.")
    }
}
