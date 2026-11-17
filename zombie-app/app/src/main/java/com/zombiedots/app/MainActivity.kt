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

    // HUD (top strip) and action buttons (bottom strip), both outside the playing area
    private lateinit var line1: TextView
    private lateinit var line2: TextView
    private lateinit var line3: TextView
    private lateinit var speedBtn: TextView
    private lateinit var menuBtn: TextView
    private lateinit var nukeBtn: NukeButtonView
    private lateinit var planeBtn: TextView
    private lateinit var aimBanner: TextView

    // Menus
    private lateinit var menuPanel: View
    private lateinit var howPanel: View
    private lateinit var settingsPanel: View
    private lateinit var overPanel: View
    private lateinit var menuTitle: TextView
    private lateinit var menuSubtitle: TextView
    private lateinit var resumeRow: View
    private lateinit var restartRow: View
    private lateinit var speedRow: TextView
    private lateinit var overDetail: TextView
    private lateinit var overBest: TextView
    private var shown: View? = null
    private var returnTo: View? = null

    private val handler = Handler(Looper.getMainLooper())

    private val speeds = listOf(0.5f to "½x", 1f to "1x", 2f to "2x", 4f to "4x")
    private val speedNames = mapOf(0.5f to "Slow", 1f to "Normal", 2f to "Fast", 4f to "Super fast")

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
        game.best = prefs.getFloat("best", 0f)

        val root = FrameLayout(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#06070A"))
        }
        column.addView(buildTopBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val gameFrame = FrameLayout(this)
        gameFrame.addView(game)
        aimBanner = text("✈ Drag your finger across the map to aim the plane. Tap PLANE again to cancel.", 13f, light = false).apply {
            background = XmbBackground.tinted(d, Color.parseColor("#3DA5FF"), 12f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            visibility = View.GONE
        }
        gameFrame.addView(aimBanner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(10f), dp(8f), dp(10f), 0)
        })
        column.addView(gameFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(buildBottomBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(column)

        menuPanel = buildMenuPanel().also { root.addView(it) }
        howPanel = buildHowPanel().also { root.addView(it) }
        settingsPanel = buildSettingsPanel().also { root.addView(it) }
        overPanel = buildOverPanel().also { root.addView(it) }
        setContentView(root)
        applyScale()

        updateHud()
        if (!prefs.getBoolean("seenHelp", false)) {
            prefs.edit().putBoolean("seenHelp", true).apply()
            returnTo = null
            show(howPanel)
        }
        handler.post(hudTick)
    }

    override fun onPause() {
        super.onPause()
        if (!::game.isInitialized) return
        if (game.world.phase == Phase.RUNNING && !game.paused) openMenu()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            shown === howPanel || shown === settingsPanel -> show(returnTo)
            shown === menuPanel -> closeMenu()
            game.world.phase == Phase.RUNNING -> openMenu()
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

    private fun pressable(normal: android.graphics.drawable.Drawable) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), XmbBackground.glass(d, 12f, strong = true))
        addState(intArrayOf(), normal)
    }

    /** Small square glass button for the top strip. */
    private fun iconButton(label: String, desc: String, onClick: () -> Unit) = text(label, 16f, light = false).apply {
        gravity = Gravity.CENTER
        background = pressable(XmbBackground.glass(d, 12f))
        contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** A PS3-style menu row: glassy, glows when pressed or highlighted. */
    private fun menuRow(label: String, highlight: Boolean = false, onClick: () -> Unit): TextView =
        text(label, 19f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(56f)
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
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
    private fun panel(opacity: Float = 0.95f): Pair<FrameLayout, LinearLayout> {
        val frame = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        frame.addView(XmbBackground(this, opacity))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(28f), dp(24f), dp(28f))
        }
        val colWidth = minOf(resources.displayMetrics.widthPixels, dp(480f))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(FrameLayout(this@MainActivity).apply {
                addView(column, FrameLayout.LayoutParams(colWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            })
        }
        frame.addView(scroll)
        return frame to column
    }

    private fun title(s: String) = text(s, 32f).apply {
        letterSpacing = 0.06f
        setPadding(0, 0, 0, dp(4f))
    }

    private fun show(p: View?) {
        for (v in listOf(menuPanel, howPanel, settingsPanel, overPanel)) {
            v.visibility = if (v === p) View.VISIBLE else View.GONE
        }
        shown = p
        game.covered = p != null
        p?.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
        updateHud()
    }

    // ------------------------------------------------------------------ top & bottom strips

    private fun buildTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = XmbBackground.glass(d, 0f)
            setPadding(dp(14f), dp(6f), dp(8f), dp(6f))
        }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        line1 = text("", 15f, light = false)
        line2 = text("", 12f, Color.parseColor("#D6E2FF"))
        line3 = text("", 12f, Color.parseColor("#9FD0FF"), light = false)
        stats.addView(line1)
        stats.addView(line2)
        stats.addView(line3)
        bar.addView(stats, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        speedBtn = iconButton("1x", "Game speed") { cycleSpeed() }
        bar.addView(speedBtn, LinearLayout.LayoutParams(dp(50f), dp(44f)).apply { marginEnd = dp(6f) })
        menuBtn = iconButton("❚❚", "Pause") { if (game.world.phase == Phase.RUNNING) openMenu() else openMenu() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(50f), dp(44f)))
        return bar
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = XmbBackground.glass(d, 0f)
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
        }
        nukeBtn = NukeButtonView(this, object : NukeButtonView.Listener {
            override fun onLaunch() { game.fireNuke(); updateHud() }
            override fun onCountdown(n: Int) { game.nukeCountdown = n; if (n > 0) onAnnounce("$n") }
            override fun onMessage(text: String) = onAnnounce(text)
        })
        nukeBtn.vibrate = game.vibrate
        planeBtn = actionButton(Color.parseColor("#3DA5FF")) { onPlanePressed() }
        bar.addView(nukeBtn, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginEnd = dp(5f) })
        bar.addView(planeBtn, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(5f) })
        return bar
    }

    private fun actionButton(color: Int, onClick: () -> Unit) = text("", 13f, light = false).apply {
        gravity = Gravity.CENTER
        background = pressable(XmbBackground.tinted(d, color, 14f))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
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
        if (!::line1.isInitialized) return
        val w = game.world
        speedBtn.text = speeds.firstOrNull { it.first == game.speed }?.second ?: "1x"
        speedBtn.contentDescription = "Game speed: ${speedNames[game.speed] ?: "Normal"}. Tap to change."
        val running = w.phase == Phase.RUNNING
        menuBtn.text = if (running) "❚❚" else "☰"
        menuBtn.contentDescription = if (running) "Pause" else "Menu"

        if (!running) {
            line1.text = "ZOMBIE DOTS"
            line1.setTextColor(Color.WHITE)
            line2.text = if (w.phase == Phase.SETUP) "👆 Tap the map to put your team down" else "Game over"
            line3.text = if (game.best > 0f) "🏆 Best: ${fmt(game.best)}" else ""
            nukeBtn.available = false
            nukeBtn.used = false
            planeBtn.text = "✈ PLANE"
            planeBtn.alpha = 0.4f
            planeBtn.contentDescription = "Plane. Place your team first."
            aimBanner.visibility = View.GONE
            game.planeAimRequested = false
            return
        }

        val next = w.nextWaveIn.toInt().coerceAtLeast(0) + 1
        line1.text = "Wave ${w.wave}  ·  next wave in ${next}s"
        line1.setTextColor(if (next <= 5) Color.parseColor("#FFB13B") else Color.WHITE)
        line2.text = "⏱ ${fmt(w.time)}    🪖 Survivors ${game.teamSize()}    🧟 ${w.zombies.size}"
        line3.text = "Survivors are: ${w.tactic.label}"
        line1.contentDescription = "Wave ${w.wave}. Next wave in $next seconds."
        line2.contentDescription = "Time ${fmt(w.time)}. Survivors ${game.teamSize()}. Zombies ${w.zombies.size}."

        nukeBtn.available = w.nukeReady && !game.paused
        nukeBtn.used = !w.nukeReady

        planeBtn.text = when {
            !w.airstrikeReady -> "✈ PLANE\nBack in ${w.airstrikeCooldown.toInt() + 1}s"
            game.planeAimRequested -> "✈ PLANE\nNow drag a line!"
            else -> "✈ PLANE\nReady"
        }
        planeBtn.alpha = if (w.airstrikeReady) 1f else 0.4f
        planeBtn.contentDescription = planeBtn.text.toString().replace("\n", ". ")
        aimBanner.visibility = if (game.planeAimRequested && w.airstrikeReady) View.VISIBLE else View.GONE
    }

    private fun cycleSpeed() {
        val i = speeds.indexOfFirst { it.first == game.speed }
        val next = speeds[(i + 1).mod(speeds.size)]
        game.speed = next.first
        prefs.edit().putFloat("speed", next.first).apply()
        if (::speedRow.isInitialized) updateSpeedRow()
        onAnnounce("Game speed: ${speedNames[next.first]}")
        updateHud()
    }

    private fun onPlanePressed() {
        val w = game.world
        if (w.phase != Phase.RUNNING) { onAnnounce("Tap the map to put your team down first."); return }
        if (!w.airstrikeReady) { onAnnounce("The plane comes back in ${w.airstrikeCooldown.toInt() + 1} seconds."); return }
        game.planeAimRequested = !game.planeAimRequested
        if (game.planeAimRequested) onAnnounce("Now drag your finger across the map to aim the plane.")
        updateHud()
    }

    // ------------------------------------------------------------------ menus

    private fun buildMenuPanel(): View {
        val (frame, col) = panel()
        menuTitle = title("Paused")
        menuSubtitle = text("The game is stopped. Pick what to do:", 15f, Color.parseColor("#C8D6FF"))
        col.addView(menuTitle)
        col.addView(menuSubtitle)
        resumeRow = menuRow("▶   Keep playing", highlight = true) { closeMenu() }
        col.addView(resumeRow)
        restartRow = menuRow("↺   Start over") { game.world.reset(); game.paused = false; show(null) }
        col.addView(restartRow)
        speedRow = menuRow("") { cycleSpeed() }
        col.addView(speedRow)
        updateSpeedRow()
        col.addView(menuRow("❓   How to play") { returnTo = menuPanel; show(howPanel) })
        col.addView(menuRow("⚙   Settings") { returnTo = menuPanel; show(settingsPanel) })
        col.addView(menuRow("✕   Close menu") { closeMenu() })
        return frame
    }

    private fun updateSpeedRow() {
        val name = speedNames[game.speed] ?: "Normal"
        speedRow.text = "⏩   Game speed: $name"
        speedRow.contentDescription = "Game speed: $name. Tap to change."
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
            box.addView(text(l, 16f, light = i != 0 || lines.size == 1).apply {
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
        col.addView(card("☢  NUKE: tap the NUKE button to open the clear safety case. Then press and HOLD the red button: 3… 2… 1… every zombie disappears! Let go early to cancel. You only get ONE, so save it for an emergency."))
        col.addView(card("🧟  Every round brings more zombies, and they get tougher. Bright orange zombies are fast runners!"))
        col.addView(card("✈  PLANE: press PLANE, then drag your finger across the map. A plane flies that way and drops bombs. It comes back after 20 seconds."))
        col.addView(card("⏩  The 1x button at the top changes the game speed. ❚❚ pauses."))
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
            scaled.add(this to 18f)
            background = XmbBackground.glass(d, 14f)
            setPadding(dp(18f), dp(12f), dp(18f), dp(12f))
            minHeight = dp(60f)
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
        col.addView(toggle("Vibration", "vibrate", game.vibrate) { game.vibrate = it; nukeBtn.vibrate = it })
        col.addView(menuRow("✔   Done", highlight = true) { show(returnTo) })
        return frame
    }

    private fun buildOverPanel(): View {
        val (frame, col) = panel(opacity = 0.9f)
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.addView(text("Game over", 34f).apply { gravity = Gravity.CENTER; letterSpacing = 0.06f })
        overDetail = text("", 16f, Color.parseColor("#C8D6FF")).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        overBest = text("", 17f, Color.parseColor("#FFD27A"), light = false).apply { gravity = Gravity.CENTER; setPadding(0, dp(10f), 0, 0) }
        col.addView(overDetail)
        col.addView(overBest)
        col.addView(menuRow("▶   Play again", highlight = true) { game.world.reset(); show(null) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(24f)
        })
        col.addView(menuRow("❓   How to play") { returnTo = overPanel; show(howPanel) })
        return frame
    }

    // ------------------------------------------------------------------ game flow

    private fun openMenu() {
        val running = game.world.phase == Phase.RUNNING
        if (running) game.paused = true
        menuTitle.text = if (running) "Paused" else "Menu"
        menuSubtitle.text = if (running) "The game is stopped. Pick what to do:" else "Pick what to do:"
        resumeRow.visibility = if (running) View.VISIBLE else View.GONE
        restartRow.visibility = if (running) View.VISIBLE else View.GONE
        returnTo = null
        show(menuPanel)
    }

    private fun closeMenu() {
        game.paused = false
        show(null)
    }

    override fun onPhaseChanged(phase: Phase) {
        when (phase) {
            Phase.SETUP, Phase.RUNNING -> if (shown === overPanel) show(null) else updateHud()
            Phase.OVER -> {
                val w = game.world
                val best = prefs.getFloat("best", 0f)
                val newBest = w.time > best
                if (newBest) {
                    prefs.edit().putFloat("best", w.time).apply()
                    game.best = w.time
                }
                overDetail.text = "Your team lasted ${fmt(w.time)}\nWave ${w.wave}  ·  ${w.kills} zombies stopped  ·  ${w.turned} turned"
                overBest.text = if (newBest) "🏆  New best time!" else "🏆  Best: ${fmt(best)}"
                handler.postDelayed({ if (game.world.phase == Phase.OVER) show(overPanel) }, 1200)
                onAnnounce("Game over. Your team lasted ${fmt(w.time)}.")
                updateHud()
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
