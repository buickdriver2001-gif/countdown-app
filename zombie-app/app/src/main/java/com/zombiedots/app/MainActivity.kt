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
    private lateinit var healthBtn: TextView
    private lateinit var cureBtn: TextView
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

    private val speeds = listOf(0.5f to "½x", 1f to "1x", 2f to "2x", 4f to "4x", 6f to "6x", 10f to "10x")
    private val speedNames = mapOf(0.5f to "Slow", 1f to "Normal", 2f to "Fast", 4f to "Super fast", 6f to "Faster", 10f to "Fastest")

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
        game.setGlobe(true) // The planet is the only mode now
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
        if (!prefs.getBoolean("seenTutorialV3", false)) {
            prefs.edit().putBoolean("seenTutorialV3", true).apply()
            openTutorial(null)
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

    /** Full-screen menu: dark backdrop with a centred, scrollable column. */
    private fun panel(opacity: Float = 0.95f): Pair<FrameLayout, LinearLayout> {
        val frame = FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }
        // Plain dark backdrop (the old wavy PS3-style background is gone)
        frame.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb((opacity * 255).toInt(), 16, 20, 28), Color.argb((opacity * 255).toInt(), 6, 8, 12))
            )
        })
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
        line1 = text("", 14f, light = false)
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
        healthBtn = actionButton(Color.parseColor("#4BE36B")) { onHealthPressed() }
        cureBtn = actionButton(Color.parseColor("#E36BD0")) { onCurePressed() }
        bar.addView(nukeBtn, LinearLayout.LayoutParams(0, dp(52f), 1.45f).apply { marginEnd = dp(4f) })
        bar.addView(planeBtn, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        bar.addView(healthBtn, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        bar.addView(cureBtn, LinearLayout.LayoutParams(0, dp(52f), 1f).apply { marginStart = dp(4f) })
        return bar
    }

    private fun actionButton(color: Int, onClick: () -> Unit) = text("", 11.5f, light = false).apply {
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
            healthBtn.text = "❤ HEALTH"
            healthBtn.alpha = 0.4f
            cureBtn.text = "💊 CURE"
            cureBtn.alpha = 0.4f
            planeBtn.contentDescription = "Plane. Place your team first."
            aimBanner.visibility = View.GONE
            game.planeAimRequested = false
            game.cureMode = false
            return
        }

        val breakTime = w.nextWaveIn > 0f
        val next = w.nextWaveIn.toInt() + 1
        line1.text = if (breakTime) "Round ${w.wave} cleared!  ·  next wave in ${next}s"
            else "Wave ${w.wave}  ·  ${w.zombiesLeft} zombies left"
        line1.setTextColor(if (breakTime) Color.parseColor("#9CFF4D") else Color.WHITE)
        line2.text = "⏱ ${fmt(w.time)}    🪖 Survivors ${game.teamSize()}    🧟 ${w.zombies.size}"
        line3.text = "Survivors are: ${w.tactic.label}"
        line1.contentDescription = if (breakTime) "Round ${w.wave} cleared. Next wave in $next seconds."
            else "Wave ${w.wave}. ${w.zombiesLeft} zombies left."
        line2.contentDescription = "Time ${fmt(w.time)}. Survivors ${game.teamSize()}. Zombies ${w.zombies.size}."

        nukeBtn.available = w.nukeReady && !game.paused
        nukeBtn.used = !w.nukeReady

        planeBtn.text = when {
            !w.airstrikeReady -> "✈ PLANE\n${w.airstrikeCooldown.toInt() + 1}s"
            game.planeAimRequested -> "✈ PLANE\ndrag a line!"
            else -> "✈ PLANE\nReady"
        }
        healthBtn.text = when {
            !w.healthReady -> "❤ ${w.maxHp(Role.HUNTER).toInt()} HP\n${w.healthCooldown.toInt() + 1}s"
            else -> "❤ HEAL +15\n${w.maxHp(Role.HUNTER).toInt()} HP"
        }
        healthBtn.alpha = if (w.healthReady) 1f else 0.45f
        healthBtn.contentDescription = if (w.healthReady) "Health ready. Hunters have ${w.maxHp(Role.HUNTER).toInt()} health. Tap to heal everyone fully and add 15 more."
            else "Hunters have ${w.maxHp(Role.HUNTER).toInt()} health. Next health boost in ${w.healthCooldown.toInt() + 1} seconds."
        val sick = w.humans.count { it.infection > 0.01f }
        if (!w.cureReady) game.cureMode = false
        cureBtn.text = when {
            !w.cureReady -> "💊 CURE\n${w.cureCooldown.toInt() + 1}s"
            sick > 0 -> "💊 CURE ×${w.cureCharges}\n$sick infected"
            else -> "💊 CURE ×${w.cureCharges}\nReady"
        }
        cureBtn.alpha = if (w.cureReady) 1f else 0.45f
        cureBtn.contentDescription = if (w.cureReady) "Cure. ${w.cureCharges} charges left. $sick survivors are infected. Each tap heals the whole team by a third."
            else "Cure comes back in ${w.cureCooldown.toInt() + 1} seconds."
        planeBtn.alpha = if (w.airstrikeReady) 1f else 0.4f
        planeBtn.contentDescription = planeBtn.text.toString().replace("\n", ". ")
        when {
            game.cureMode -> {
                aimBanner.text = "💊 Slow motion! Tap a survivor to cure them. Tap CURE again to cure the most infected."
                aimBanner.background = XmbBackground.tinted(d, Color.parseColor("#E36BD0"), 12f)
                aimBanner.visibility = View.VISIBLE
            }
            game.planeAimRequested && w.airstrikeReady -> {
                aimBanner.text = "✈ Drag your finger across the map to aim the plane. Tap PLANE again to cancel."
                aimBanner.background = XmbBackground.tinted(d, Color.parseColor("#3DA5FF"), 12f)
                aimBanner.visibility = View.VISIBLE
            }
            else -> aimBanner.visibility = View.GONE
        }
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

    private fun onHealthPressed() {
        val w = game.world
        if (w.phase != Phase.RUNNING) { onAnnounce("Tap the map to put your team down first."); return }
        when {
            !w.healthReady -> onAnnounce("Next health upgrade in ${w.healthCooldown.toInt() + 1} seconds.")
            w.upgradeHealth() -> {
                game.vibe(Haptics.Kind.HEALTH)
                onAnnounce("Everyone healed! Hunters now have ${w.maxHp(Role.HUNTER).toInt()} health.")
            }
        }
        updateHud()
    }

    private fun onCurePressed() {
        val w = game.world
        if (w.phase != Phase.RUNNING) { onAnnounce("Tap the map to put your team down first."); return }
        if (!w.cureReady) { onAnnounce("Cures refill in ${w.cureCooldown.toInt() + 1} seconds."); return }
        if (w.mostInfected() == null) { onAnnounce("Nobody is infected right now. Save the cure for later!"); return }
        if (w.cureAll()) {
            game.vibe(Haptics.Kind.CURE)
            onAnnounce(if (w.cureCharges > 0) "Team healed by a third. ${w.cureCharges} cure charges left."
                else "Team healed by a third. That was the last charge. Cures refill in 2 minutes.")
        }
        updateHud()
    }

    override fun onCureTarget(h: Human) {
        val pct = (h.infection * 100).toInt()
        if (h.infection < 0.01f) {
            onAnnounce("That survivor isn't infected. Tap one with a green ring.")
            return
        }
        if (game.world.cureOne(h)) {
            game.vibe(Haptics.Kind.CURE)
            onAnnounce(if (pct > 0) "Cured! Their infection was $pct percent." else "Cured!")
        }
        game.cureMode = false
        updateHud()
    }

    private fun onPlanePressed() {
        val w = game.world
        if (w.phase != Phase.RUNNING) { onAnnounce("Tap the map to put your team down first."); return }
        if (!w.airstrikeReady) { onAnnounce("The plane comes back in ${w.airstrikeCooldown.toInt() + 1} seconds."); return }
        game.cureMode = false
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
        col.addView(menuRow("❓   How to play") { openTutorial(menuPanel) })
        col.addView(menuRow("⚙   Settings") { returnTo = menuPanel; show(settingsPanel) })
        col.addView(menuRow("🏠   Back to NEXUS") { HubLink.open(this) })
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

    // ------------------------------------------------------------------ tutorial (paged)

    private val tutorialPages = ArrayList<View>()
    private lateinit var pageDots: TextView
    private lateinit var pageBack: TextView
    private lateinit var pageNext: TextView
    private lateinit var pageTitle: TextView
    private var page = 0
    private val pageTitles = ArrayList<String>()

    private fun tutorialPage(title: String, vararg cards: View) {
        pageTitles.add(title)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (cd in cards) box.addView(cd)
        tutorialPages.add(box)
    }

    private fun buildHowPanel(): View {
        val (frame, col) = panel()
        pageTitle = title("")
        col.addView(pageTitle)

        tutorialPage("Welcome!",
            card("🧟  Zombies are coming! Your little team of dots has to survive as long as possible."),
            card("🌍  The battle happens on a spinning planet. Drag to spin it, pinch to zoom. It follows your team on its own."),
            card("👆  To start, tap anywhere on the planet to put your team down."),
            card("👀  After that, your team moves, runs and fights all by themselves. You just watch… and help them with your buttons!")
        )
        tutorialPage("Who is who",
            card(
                "Your team:",
                dot(game.playerColor, "Gold = your leader. Carries a powerful shotgun that hits many zombies at once!"),
                dot(game.hunterColor, "Blue = hunters. They shoot zombies."),
                dot(game.strayColor, "Gray = lost survivors. They run over and join your team! Later rounds bring whole groups. Your squad can grow up to 100 hunters!")
            ),
            card("🪖  The top bar shows how many survivors you have, the round, and how many zombies are left.")
        )
        tutorialPage("The zombies",
            card(
                "Zombie types:",
                dot(game.zombieColor, "Red = normal zombie"),
                dot(game.runnerColor, "Orange = fast runner"),
                dot(game.fatColor, "Big dark red = fat zombie. Slow, but takes lots of hits."),
                dot(Color.parseColor("#C9D1DC"), "Steel ring = armored. Shoot the armor off first!")
            )
        )
        tutorialPage("The Purger",
            card(
                "☠  The PURGER:",
                dot(Color.parseColor("#B65CFF"), "A dark zombie with a purple halo. Bullets can't hurt it!"),
                dot(Color.parseColor("#B65CFF"), "Zombies near it glow purple: they get faster, bite harder and are tougher to kill.")
            ),
            card("💎  Somewhere on the planet is its purple TOTEM. A purple arrow at the edge shows where. Spin the planet to find it, then TAP the totem again and again until it breaks."),
            card("✨  When the totem breaks, the Purger disappears… for now. It first shows up in round 4.")
        )
        tutorialPage("Getting bitten",
            card("❤  Every bite takes away HEALTH. A little bar above a survivor shows it: green, then yellow, then red. Health slowly comes back when they stay safe for a few seconds."),
            card("🟢  Bites can also infect: a green ring fills up around them. It keeps creeping up slowly, so use CURE!"),
            card("⚠  A yellow warning sign means the ring is almost full!"),
            card("🧟  If their health runs out OR the green ring fills up, they turn into a zombie and chase their old team.")
        )
        tutorialPage("Your buttons",
            card("☢  NUKE: tap it to open the safety case, then press and HOLD the red button. 3… 2… 1… every zombie disappears! You only get ONE."),
            card("✈  PLANE: tap it, then drag your finger across the map. A plane drops bombs along your line. Comes back after 20 seconds."),
            card("❤  HEALTH: heals your whole team to full and adds +15 max health (hunters start at 100, your leader at 150). It also makes them a bit faster and stronger. Use it every time it's ready!"),
            card("💎  TOTEM: when the Purger shows up, tap its purple totem on the planet over and over to break it."),
            card("💊  CURE: you have 3 charges. Each tap heals your WHOLE team by a third (3 taps = fully cured). When all 3 are used up, they come back after 2 minutes.")
        )
        tutorialPage("Rounds and bosses",
            card("🔁  Kill every zombie to finish a round. Each round brings more zombies, and they get tougher."),
            card(
                "☠  The BOSS:",
                dot(Color.parseColor("#7A1018"), "The behemoth is a GIANT, super slow zombie with a boss health bar at the bottom."),
                dot(Color.parseColor("#7A1018"), "The first one comes in round 6. A new one comes the round after you beat it. Every second one wears armor!")
            )
        )
        tutorialPage("Last tips",
            card("⏩  The 1x button at the top changes the game speed: ½x, 1x, 2x, 4x, 6x and 10x. ❚❚ pauses the game."),
            card("⚙  In Settings you can also turn on bigger text and shapes (for colorblind players)."),
            card("🏆  There's no last round. See how far your team can go!")
        )

        val holder = FrameLayout(this)
        for (pg in tutorialPages) { pg.visibility = View.GONE; holder.addView(pg) }
        col.addView(holder)

        pageDots = text("", 16f, Color.parseColor("#C8D6FF")).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(14f), 0, 0)
        }
        col.addView(pageDots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pageBack = menuRow("◀  Back") { showPage(page - 1) }.apply { gravity = Gravity.CENTER }
        pageNext = menuRow("Next  ▶", highlight = true) {
            if (page < tutorialPages.size - 1) showPage(page + 1) else show(returnTo)
        }.apply { gravity = Gravity.CENTER }
        nav.addView(pageBack, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6f); topMargin = dp(10f) })
        nav.addView(pageNext, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6f); topMargin = dp(10f) })
        col.addView(nav)
        col.addView(text("Skip", 15f, Color.parseColor("#9FB4E0")).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(4f))
            isClickable = true
            contentDescription = "Skip the tutorial"
            setOnClickListener { show(returnTo) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showPage(0)
        return frame
    }

    private fun showPage(i: Int) {
        page = i.coerceIn(0, tutorialPages.size - 1)
        tutorialPages.forEachIndexed { idx, v -> v.visibility = if (idx == page) View.VISIBLE else View.GONE }
        pageTitle.text = pageTitles[page]
        pageDots.text = (0 until tutorialPages.size).joinToString("  ") { if (it == page) "●" else "○" }
        pageDots.contentDescription = "Page ${page + 1} of ${tutorialPages.size}"
        pageBack.visibility = if (page == 0) View.INVISIBLE else View.VISIBLE
        val last = page == tutorialPages.size - 1
        pageNext.text = if (last) "Let's play!  ✔" else "Next  ▶"
        pageNext.contentDescription = if (last) "Finish tutorial" else "Next page"
        onAnnounce("${pageTitles[page]}. Page ${page + 1} of ${tutorialPages.size}.")
    }

    private fun openTutorial(from: View?) {
        returnTo = from
        showPage(0)
        show(howPanel)
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
        col.addView(menuRow("❓   How to play") { openTutorial(overPanel) })
        col.addView(menuRow("🏠   Back to NEXUS") { HubLink.open(this) })
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
