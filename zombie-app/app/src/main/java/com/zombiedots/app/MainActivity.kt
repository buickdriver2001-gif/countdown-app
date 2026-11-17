package com.zombiedots.app

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity(), GameView.Listener {

    private lateinit var game: GameView
    private val prefs by lazy { getSharedPreferences("zombiedots", Context.MODE_PRIVATE) }
    private val d by lazy { resources.displayMetrics.density }
    private fun dp(v: Float) = (v * d).toInt()

    private var textScale = 1f
    private val scaled = ArrayList<Pair<TextView, Float>>()

    // HUD
    private lateinit var hudTop: LinearLayout
    private lateinit var waveText: TextView
    private lateinit var infoText: TextView
    private lateinit var tacticText: TextView
    private lateinit var bottomBar: LinearLayout
    private lateinit var nukeBtn: TextView
    private lateinit var planeBtn: TextView
    private lateinit var aimBanner: TextView

    // Panels
    private lateinit var setupPanel: View
    private lateinit var pausePanel: View
    private lateinit var howPanel: View
    private lateinit var settingsPanel: View
    private lateinit var overPanel: View
    private lateinit var overTitle: TextView
    private lateinit var overDetail: TextView
    private lateinit var overBest: TextView
    private lateinit var setupBest: TextView
    private lateinit var speedRow: TextView
    private var shown: View? = null
    private var returnTo: View? = null

    private var nukeArmedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val speeds = listOf(0.5f to "🐢 Slow", 1f to "Normal", 2f to "🐇 Fast", 4f to "🚀 Super fast")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.crashFile(this).exists()) {
            startActivity(android.content.Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textScale = if (prefs.getBoolean("bigText", false)) 1.3f else 1f
        game = GameView(this, this)
        game.shapes = prefs.getBoolean("shapes", false)
        game.tapAddsZombies = prefs.getBoolean("tapZombies", false)
        game.vibrate = prefs.getBoolean("vibrate", true)
        game.speed = prefs.getFloat("speed", 1f)

        val root = FrameLayout(this)
        root.addView(game)
        buildHud(root)
        setupPanel = buildSetupPanel().also { root.addView(it) }
        pausePanel = buildPausePanel().also { root.addView(it) }
        howPanel = buildHowPanel().also { root.addView(it) }
        settingsPanel = buildSettingsPanel().also { root.addView(it) }
        overPanel = buildOverPanel().also { root.addView(it) }
        setContentView(root)
        applyScale()

        onPhaseChanged(Phase.SETUP)
        if (!prefs.getBoolean("seenHelp", false)) {
            prefs.edit().putBoolean("seenHelp", true).apply()
            returnTo = setupPanel
            show(howPanel)
        }
        handler.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        if (!::game.isInitialized) return
        if (game.world.phase == Phase.RUNNING && !game.paused) pauseGame()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shown === howPanel || shown === settingsPanel -> show(returnTo)
            shown === pausePanel -> resumeGame()
            game.world.phase == Phase.RUNNING -> pauseGame()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ building blocks

    private fun text(s: CharSequence, sp: Float, color: Int = Color.WHITE, light: Boolean = true, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            typeface = Typeface.create(if (light) "sans-serif-light" else "sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
            setShadowLayer(6f, 0f, 1f, Color.parseColor("#88000000"))
            scaled.add(this to sp)
        }

    private fun applyScale() {
        for ((tv, sp) in scaled) tv.textSize = sp * textScale
    }

    /** A PS3-style menu row: glassy, glows when pressed or highlighted. */
    private fun menuRow(label: String, highlight: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 20f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(60f)
            setPadding(dp(22f), dp(12f), dp(22f), dp(12f))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), XmbBackground.glass(d, 14f, strong = true))
                addState(intArrayOf(), XmbBackground.glass(d, 14f, strong = highlight))
            }
            isClickable = true
            isFocusable = true
            contentDescription = label.replace(Regex("[^\\p{L}\\p{N} :!.,'?]"), "").trim()
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        }

    /** Full-screen menu: XMB backdrop with a centred, scrollable column. */
    private fun panel(blocking: Boolean = true, opacity: Float = 0.95f): Pair<FrameLayout, LinearLayout> {
        val frame = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = blocking
            isFocusable = blocking
        }
        frame.addView(XmbBackground(this, opacity))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(28f), dp(24f), dp(28f))
        }
        val colWidth = minOf(resources.displayMetrics.widthPixels, dp(480f))
        if (blocking) {
            // Menus scroll, in case of big text or small screens
            val scroll = ScrollView(this).apply {
                isFillViewport = true
                addView(FrameLayout(this@MainActivity).apply {
                    addView(column, FrameLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                })
            }
            frame.addView(scroll)
        } else {
            frame.addView(column, FrameLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        return frame to column
    }

    private fun title(s: String) = text(s, 34f).apply {
        letterSpacing = 0.06f
        setPadding(0, 0, 0, dp(6f))
    }

    private fun show(p: View?) {
        for (v in listOf(setupPanel, pausePanel, howPanel, settingsPanel, overPanel)) {
            v.visibility = if (v === p) View.VISIBLE else View.GONE
        }
        shown = p
        p?.let {
            it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            it.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        updateHudVisibility()
    }

    // ------------------------------------------------------------------ HUD

    private fun buildHud(root: FrameLayout) {
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), 0)
        }
        hudTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = XmbBackground.glass(d, 16f)
            setPadding(dp(14f), dp(8f), dp(8f), dp(8f))
        }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        waveText = text("", 18f, light = false)
        infoText = text("", 14f, Color.parseColor("#D6E2FF"))
        stats.addView(waveText)
        stats.addView(infoText)
        hudTop.addView(stats, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pauseBtn = text("❚❚", 20f, light = false).apply {
            gravity = Gravity.CENTER
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), XmbBackground.glass(d, 14f, strong = true))
                addState(intArrayOf(), XmbBackground.glass(d, 14f))
            }
            contentDescription = "Pause"
            isClickable = true
            setOnClickListener { pauseGame() }
        }
        hudTop.addView(pauseBtn, LinearLayout.LayoutParams(dp(58f), dp(58f)))
        top.addView(hudTop)

        tacticText = text("", 15f, Color.parseColor("#9FD0FF"), light = false).apply {
            background = XmbBackground.glass(d, 14f)
            setPadding(dp(14f), dp(6f), dp(14f), dp(6f))
        }
        top.addView(tacticText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })

        aimBanner = text("✈ Now drag your finger across the map to show the plane where to fly.\nTap PLANE again to cancel.", 16f, light = false).apply {
            background = XmbBackground.tinted(d, Color.parseColor("#3DA5FF"), 14f)
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            visibility = View.GONE
        }
        top.addView(aimBanner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8f)
        })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), 0, dp(12f), dp(12f))
        }
        nukeBtn = actionButton(Color.parseColor("#FF8A3D")) { onNukePressed() }
        planeBtn = actionButton(Color.parseColor("#3DA5FF")) { onPlanePressed() }
        bottomBar.addView(nukeBtn, LinearLayout.LayoutParams(0, dp(76f), 1f).apply { marginEnd = dp(6f) })
        bottomBar.addView(planeBtn, LinearLayout.LayoutParams(0, dp(76f), 1f).apply { marginStart = dp(6f) })
        root.addView(bottomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    private fun actionButton(color: Int, onClick: () -> Unit) = text("", 17f, light = false).apply {
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), XmbBackground.glass(d, 18f, strong = true))
            addState(intArrayOf(), XmbBackground.tinted(d, color, 18f))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun updateHudVisibility() {
        val running = game.world.phase == Phase.RUNNING
        val vis = if (running) View.VISIBLE else View.GONE
        hudTop.visibility = vis
        tacticText.visibility = vis
        bottomBar.visibility = vis
        if (!running) { aimBanner.visibility = View.GONE; game.planeAimRequested = false }
    }

    private fun fmt(t: Float): String {
        val s = t.toInt()
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private val hudTick = object : Runnable {
        override fun run() {
            updateHud()
            handler.postDelayed(this, 200)
        }
    }

    private fun updateHud() {
        val w = game.world
        if (w.phase != Phase.RUNNING) return
        val next = w.nextWaveIn.toInt().coerceAtLeast(0) + 1
        waveText.text = "Wave ${w.wave}   ·   next wave in ${next}s"
        waveText.setTextColor(if (next <= 5) Color.parseColor("#FFB13B") else Color.WHITE)
        infoText.text = "⏱ ${fmt(w.time)}     🙂 Team ${game.teamSize()}     🧟 ${w.zombies.size}"
        hudTop.contentDescription = "Wave ${w.wave}. Next wave in $next seconds. Time ${fmt(w.time)}. " +
            "Team ${game.teamSize()}. Zombies ${w.zombies.size}."
        tacticText.text = "Team is: ${w.tactic.label}"

        val armed = SystemClock.uptimeMillis() < nukeArmedUntil && w.nukeReady
        nukeBtn.text = when {
            !w.nukeReady -> "☢ NUKE\nUsed up"
            armed -> "☢ TAP AGAIN\nto launch the nuke!"
            else -> "☢ NUKE\nReady · 1 left"
        }
        nukeBtn.alpha = if (w.nukeReady) 1f else 0.45f
        nukeBtn.contentDescription = nukeBtn.text.toString().replace("\n", ". ")

        planeBtn.text = when {
            !w.airstrikeReady -> "✈ PLANE\nBack in ${w.airstrikeCooldown.toInt() + 1}s"
            game.planeAimRequested -> "✈ PLANE\nNow drag a line!"
            else -> "✈ PLANE\nReady!"
        }
        planeBtn.alpha = if (w.airstrikeReady) 1f else 0.45f
        planeBtn.contentDescription = planeBtn.text.toString().replace("\n", ". ")
        aimBanner.visibility = if (game.planeAimRequested && w.airstrikeReady) View.VISIBLE else View.GONE
    }

    private fun onNukePressed() {
        val w = game.world
        if (!w.nukeReady) { onAnnounce("The nuke is used up. You only get one each game."); return }
        if (SystemClock.uptimeMillis() < nukeArmedUntil) {
            nukeArmedUntil = 0L
            game.fireNuke()
        } else {
            nukeArmedUntil = SystemClock.uptimeMillis() + 3000
            onAnnounce("Tap the nuke button again to launch it.")
        }
        updateHud()
    }

    private fun onPlanePressed() {
        val w = game.world
        if (!w.airstrikeReady) { onAnnounce("The plane comes back in ${w.airstrikeCooldown.toInt() + 1} seconds."); return }
        game.planeAimRequested = !game.planeAimRequested
        if (game.planeAimRequested) onAnnounce("Now drag your finger across the map to aim the plane.")
        updateHud()
    }

    // ------------------------------------------------------------------ panels

    private fun buildSetupPanel(): View {
        val (frame, col) = panel(blocking = false, opacity = 0.72f)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(text("ZOMBIE DOTS", 40f).apply { letterSpacing = 0.12f; gravity = Gravity.CENTER })
        col.addView(text("👆  Tap anywhere to put your team down", 21f, light = false).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(18f), 0, dp(4f))
        })
        col.addView(text("Then watch them try to survive!", 16f, Color.parseColor("#C8D6FF")).apply { gravity = Gravity.CENTER })
        setupBest = text("", 15f, Color.parseColor("#FFD27A")).apply { gravity = Gravity.CENTER; setPadding(0, dp(12f), 0, 0) }
        col.addView(setupBest)
        col.addView(menuRow("❓   How to play") { returnTo = setupPanel; show(howPanel) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(26f)
        })
        col.addView(menuRow("⚙   Settings") { returnTo = setupPanel; show(settingsPanel) })
        return frame
    }

    private fun buildPausePanel(): View {
        val (frame, col) = panel()
        col.addView(title("Paused"))
        col.addView(text("The game is stopped. Pick what to do:", 16f, Color.parseColor("#C8D6FF")))
        col.addView(menuRow("▶   Keep playing", highlight = true) { resumeGame() })
        col.addView(menuRow("↺   Start over") {
            game.world.reset(); game.paused = false
        })
        speedRow = menuRow("") { cycleSpeed() }
        col.addView(speedRow)
        updateSpeedRow()
        col.addView(menuRow("❓   How to play") { returnTo = pausePanel; show(howPanel) })
        col.addView(menuRow("⚙   Settings") { returnTo = pausePanel; show(settingsPanel) })
        return frame
    }

    private fun cycleSpeed() {
        val i = speeds.indexOfFirst { it.first == game.speed }
        val next = speeds[(i + 1).mod(speeds.size)]
        game.speed = next.first
        prefs.edit().putFloat("speed", next.first).apply()
        updateSpeedRow()
    }

    private fun updateSpeedRow() {
        val name = speeds.firstOrNull { it.first == game.speed }?.second ?: "Normal"
        speedRow.text = "⏩   Game speed: $name"
        speedRow.contentDescription = "Game speed: ${name.replace(Regex("[^\\p{L} ]"), "").trim()}. Tap to change."
    }

    private fun dot(color: Int, rest: String): CharSequence {
        val s = SpannableString("●  $rest")
        s.setSpan(ForegroundColorSpan(color), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun card(vararg lines: CharSequence): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = XmbBackground.glass(d, 14f)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        }
        lines.forEachIndexed { i, l ->
            box.addView(text(l, 17f, light = i != 0 || lines.size == 1).apply {
                setLineSpacing(dp(3f).toFloat(), 1f)
                if (i > 0) setPadding(0, dp(4f), 0, 0)
            })
        }
        return box
    }

    private fun buildHowPanel(): View {
        val (frame, col) = panel()
        col.addView(title("How to play"))
        col.addView(card("👆  Tap anywhere on the map to put your team down."))
        col.addView(card("👀  Your team moves and fights all by themselves. You just watch… and help!"))
        col.addView(card(
            "Who is who:",
            dot(game.playerColor, "Yellow = your survivor, the leader"),
            dot(game.hunterColor, "Blue = hunters. They shoot zombies."),
            dot(game.strayColor, "Gray = lost survivors. They run over and join your team!"),
            dot(game.zombieColor, "Red = zombies. They chase your team.")
        ))
        col.addView(card("🟢  When a zombie bites someone, a green ring fills up around them. When the ring is full, they turn into a zombie!"))
        col.addView(card("☢  NUKE: press the NUKE button two times. Every zombie disappears! You only get ONE, so save it for an emergency."))
        col.addView(card("✈  PLANE: press PLANE, then drag your finger across the map. A plane flies that way and drops bombs. It comes back after 20 seconds."))
        col.addView(card("❚❚  Press the pause button any time to stop the game."))
        col.addView(card("🏆  How long can your team survive?"))
        col.addView(menuRow("✔   Got it!", highlight = true) { show(returnTo) })
        return frame
    }

    private fun toggle(label: String, key: String, initial: Boolean, onChange: (Boolean) -> Unit): View =
        Switch(this).apply {
            text = label
            isChecked = initial
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            scaled.add(this to 19f)
            background = XmbBackground.glass(d, 14f)
            setPadding(dp(18f), dp(14f), dp(18f), dp(14f))
            minHeight = dp(64f)
            switchPadding = dp(12f)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
                onChange(checked)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10f)
            }
        }

    private fun buildSettingsPanel(): View {
        val (frame, col) = panel()
        col.addView(title("Settings"))
        col.addView(toggle("Bigger text", "bigText", textScale > 1f) {
            textScale = if (it) 1.3f else 1f
            applyScale()
        })
        col.addView(toggle("Shapes as well as colors (zombies become squares, your leader a star)", "shapes", game.shapes) {
            game.shapes = it
        })
        col.addView(toggle("Tapping the map adds more zombies (harder)", "tapZombies", game.tapAddsZombies) {
            game.tapAddsZombies = it
        })
        col.addView(toggle("Vibration", "vibrate", game.vibrate) { game.vibrate = it })
        col.addView(menuRow("✔   Done", highlight = true) { show(returnTo) })
        return frame
    }

    private fun buildOverPanel(): View {
        val (frame, col) = panel(opacity = 0.9f)
        col.gravity = Gravity.CENTER_HORIZONTAL
        overTitle = text("Game over", 36f).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f }
        overDetail = text("", 17f, Color.parseColor("#C8D6FF")).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        overBest = text("", 18f, Color.parseColor("#FFD27A"), light = false).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        col.addView(overTitle)
        col.addView(overDetail)
        col.addView(overBest)
        col.addView(menuRow("▶   Play again", highlight = true) { game.world.reset() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24f)
        })
        col.addView(menuRow("❓   How to play") { returnTo = overPanel; show(howPanel) })
        return frame
    }

    // ------------------------------------------------------------------ game flow

    private fun pauseGame() {
        if (game.world.phase != Phase.RUNNING) return
        game.paused = true
        show(pausePanel)
    }

    private fun resumeGame() {
        game.paused = false
        show(null)
    }

    override fun onPhaseChanged(phase: Phase) {
        when (phase) {
            Phase.SETUP -> {
                val best = prefs.getFloat("best", 0f)
                setupBest.text = if (best > 0f) "🏆  Best: your team lasted ${fmt(best)}" else ""
                show(setupPanel)
            }
            Phase.RUNNING -> show(null)
            Phase.OVER -> {
                val w = game.world
                val best = prefs.getFloat("best", 0f)
                val newBest = w.time > best
                if (newBest) prefs.edit().putFloat("best", w.time).apply()
                overTitle.text = "Game over"
                overDetail.text = "Your team lasted ${fmt(w.time)}\nWave ${w.wave}  ·  ${w.kills} zombies stopped  ·  ${w.turned} turned"
                overBest.text = if (newBest) "🏆  New best time!" else "🏆  Best: ${fmt(best)}"
                handler.postDelayed({ if (game.world.phase == Phase.OVER) show(overPanel) }, 1200)
                onAnnounce("Game over. Your team lasted ${fmt(w.time)}.")
            }
        }
    }

    override fun onAnnounce(message: String) {
        @Suppress("DEPRECATION")
        game.announceForAccessibility(message)
    }

    override fun onAirstrikeLaunched() {
        updateHud()
    }
}
