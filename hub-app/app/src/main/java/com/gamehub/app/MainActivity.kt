package com.gamehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {

    private val builtIn = listOf(
        GameEntry("Zombie Dots", "com.zombiedots.app", "Survive the endless horde", Color.rgb(255, 80, 80), 0),
        GameEntry("Ashen Vow", "com.ashenvow.app", "Defeat The Hollow Warden", Color.rgb(230, 150, 60), 1),
        GameEntry("SynthPad", "com.synthpad.app", "Make music with synths", Color.rgb(120, 220, 255), 2)
    )
    private lateinit var hub: HubView
    private lateinit var drawer: AppDrawer
    private lateinit var tutorial: TutorialView
    private val sfx by lazy { Sfx() }
    private var leftForApp = false
    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }

    /** Games the player added from their phone, in the order they were added. */
    private fun customPkgs(): List<String> = prefs.getString("custom", "")!!.split(",").filter { it.isNotBlank() }

    private fun buildList(): List<GameEntry> {
        val list = ArrayList(builtIn)
        for (pkg in customPkgs()) {
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                val label = packageManager.getApplicationLabel(info).toString()
                list.add(GameEntry(label, pkg, description(pkg, label), Color.rgb(170, 200, 255), -1, custom = true,
                    drawable = packageManager.getApplicationIcon(pkg)))
            } catch (_: Exception) { /* uninstalled since: skip it */ }
        }
        return list
    }

    /** A description the player wrote, or a built-in one for apps we know, or a plain fallback. */
    private fun description(pkg: String, label: String): String {
        prefs.getString("desc_$pkg", null)?.let { if (it.isNotBlank()) return it }
        val l = label.lowercase()
        return when {
            "ellipse" in l -> "Space Flight Simulation"
            "gta" in l || "grand theft auto" in l -> "Become a Dangerous Criminal"
            else -> "On your phone"
        }
    }

    private fun editDescription(g: GameEntry) {
        var field: android.widget.EditText? = null
        field = NexusUi.panel(this, "Description", "Shown under ${g.name} in NEXUS.", listOf(
            NexusUi.Option("Save", primary = true) {
                prefs.edit().putString("desc_${g.pkg}", field?.text?.toString()?.trim() ?: "").apply()
                refresh()
            }
        ), input = g.tagline)
    }

    /** Hold an added app (without moving it): change its description or take it off NEXUS. */
    private fun appOptions(g: GameEntry) {
        NexusUi.panel(this, g.name, null, listOf(
            NexusUi.Option("Edit description") { editDescription(g) },
            NexusUi.Option("App info") {
                try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${g.pkg}"))) } catch (_: Exception) {}
            },
            NexusUi.Option("Remove from NEXUS", danger = true) { removeGame(g) }
        ))
    }

    /** Saves a new position for an app (the order is kept by package name). */
    private fun moveApp(from: Int, to: Int) {
        val pkgs = hub.games.map { it.pkg }.toMutableList()
        val p = pkgs.removeAt(from); pkgs.add(to, p)
        prefs.edit().putString("order", pkgs.joinToString(",")).apply()
        refresh()
    }

    private fun refresh() {
        val list = buildList().toMutableList()
        val order = prefs.getString("order", "")!!.split(",").filter { it.isNotBlank() }
        if (order.isNotEmpty()) {
            // Saved order first; anything new goes at the end in its usual place
            val natural = list.withIndex().associate { it.value.pkg to it.index }
            list.sortBy { g -> order.indexOf(g.pkg).let { if (it < 0) 10_000 + (natural[g.pkg] ?: 0) else it } }
        }
        for (g in list) g.installed = packageManager.getLaunchIntentForPackage(g.pkg) != null
        hub.games = list
    }

    /** Adds one or more apps from the drawer to the NEXUS list. */
    private fun addApps(items: List<AppItem>) {
        val have = hub.games.map { it.pkg }.toSet()
        val fresh = items.filter { it.pkg !in have }
        if (fresh.isEmpty()) return
        prefs.edit().putString("custom", (customPkgs() + fresh.map { it.pkg }).joinToString(",")).apply()
        refresh()
        val msg = if (fresh.size == 1) "${fresh[0].label} added to NEXUS" else "${fresh.size} apps added to NEXUS"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Opens any app from the drawer, with the same smooth fade. */
    private fun openPkg(pkg: String) {
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        leftForApp = true
        startActivity(launch, android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
    }

    private fun removeGame(g: GameEntry) {
        NexusUi.panel(this, "Remove ${g.name}?", "This only removes it from NEXUS. The app stays on your phone.", listOf(
            NexusUi.Option("Remove", danger = true) {
                prefs.edit().putString("custom", customPkgs().filter { it != g.pkg }.joinToString(",")).apply()
                refresh()
            }
        ))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.crashFile(this).exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        hub = HubView(this, builtIn, { open(it) }, { drawer.open(add = true) }, { appOptions(it) }, sfx, { homeSettings() },
            { from, to -> moveApp(from, to) }, { drawer.open(add = false) }, { openSwitcher() })
        drawer = AppDrawer(this, sfx, { openPkg(it.pkg) }, { addApps(it) }, { pkg -> hub.games.any { it.pkg == pkg } })
        drawer.onVisible = { open -> hub.covered = open; if (open) hub.clearSelection() }
        val root = android.widget.FrameLayout(this)
        root.addView(hub)
        root.addView(drawer)
        tutorial = TutorialView(this, sfx, object : TutorialView.Actions {
            override fun setHome() = openHomeSettings()
            override fun appInfo() = openAppInfo()
            override fun accessibility() = openAccessibility()
            override fun done() = closeTutorial()
        }).apply { visibility = android.view.View.GONE }
        root.addView(tutorial)
        setContentView(root)
        drawer.load()
        // Full screen: no status bar, no navigation buttons (swipe from an edge to peek at them)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.decorView.post { hideBars() }
        val fromApp = intent?.getBooleanExtra("fromApp", false) == true
        if (fromApp) { hub.booting = false; leftForApp = true }
        else if (isHomeIntent(intent) && !HomeMode.isOn(this) && HomeMode.goNormalHome(this)) {
            // NEXUS was closed on purpose: Home goes to the normal home screen
            hub.booting = false
        } else {
            // Opened from its icon (or Home while NEXUS mode is on): NEXUS mode on
            HomeMode.setOn(this, true)
            if (savedInstanceState == null) Thread { playChime() }.start()
            // First time: show the guide once the start-up animation has finished
            if (!prefs.getBoolean("tutorialSeen", false)) waitThenTutorial()
        }
    }

    private fun isHomeIntent(i: Intent?) = i?.hasCategory(Intent.CATEGORY_HOME) == true

    private var pausedAt = 0L

    override fun onPause() {
        super.onPause()
        pausedAt = android.os.SystemClock.uptimeMillis()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ::tutorial.isInitialized && tutorial.visibility == android.view.View.VISIBLE -> closeTutorial()
            ::drawer.isInitialized && drawer.isOpen -> drawer.close()
            else -> askClose()
        }
    }

    private fun waitThenTutorial() {
        hub.postDelayed({
            if (isFinishing) return@postDelayed
            if (hub.booting) waitThenTutorial() else showTutorial()
        }, 300)
    }

    private fun showTutorial() {
        tutorial.restart()
        tutorial.visibility = android.view.View.VISIBLE
        tutorial.alpha = 0f
        tutorial.animate().alpha(1f).setDuration(350).start()
    }

    private fun closeTutorial() {
        prefs.edit().putBoolean("tutorialSeen", true).apply()
        tutorial.animate().alpha(0f).setDuration(250).withEndAction { tutorial.visibility = android.view.View.GONE }.start()
    }

    private fun openSwitcher() {
        startActivity(Intent(this, SwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
    }

    /** NEXUS's own App info page (where "Allow restricted settings" lives, under ⋮). */
    private fun openAppInfo() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    private fun openAccessibility() {
        try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
    }

    /** Settings → Open apps: status, setup steps, and an on/off switch for the square button. */
    private fun openAppsSettings() {
        val on = Recents.serviceOn(this)
        val replace = prefs.getBoolean("replaceRecents", true)
        val status = if (on) "Helper is ON. Open apps are being remembered." else "Helper is OFF. Follow the two steps below to turn it on."
        val opts = ArrayList<NexusUi.Option>()
        if (!on) {
            opts.add(NexusUi.Option("1.  Allow restricted settings") { openAppInfo() })
            opts.add(NexusUi.Option("2.  Turn on helper", primary = true) { openAccessibility() })
        } else opts.add(NexusUi.Option("Turn helper off") { openAccessibility() })
        opts.add(NexusUi.Option("Square button opens NEXUS Open Apps:  " + (if (replace) "ON" else "OFF")) {
            prefs.edit().putBoolean("replaceRecents", !replace).apply()
            openAppsSettings()
        })
        opts.add(NexusUi.Option("Show Open Apps now") { openSwitcher() })
        NexusUi.panel(this, "Open apps", "$status\n\nStep 1: on the App info page, tap ⋮ at the top right and choose Allow restricted settings (only needed for apps installed from a download).\nStep 2: in Accessibility, choose NEXUS Open Apps and switch it on.\n\nPictures of your apps stay on this phone and are never sent anywhere.", opts, cancel = "Done")
    }

    /** Closing NEXUS on purpose: NEXUS mode off until it's opened again from its icon. */
    private fun askClose() {
        NexusUi.panel(this, "Close NEXUS?",
            if (HomeMode.isDefault(this)) "Home will go to your normal home screen until you open NEXUS again." else "NEXUS will close.",
            listOf(NexusUi.Option("Close NEXUS", danger = true) { closeNexus() }), cancel = "Stay")
    }

    private fun closeNexus() {
        HomeMode.setOn(this, false)
        if (HomeMode.isDefault(this)) HomeMode.goNormalHome(this)
        finishAndRemoveTask()
    }

    private fun offerHome() {
        NexusUi.panel(this, "Make NEXUS your home screen?",
            "Home will take you to NEXUS, even after the screen turns off and on. To use your normal home screen, close NEXUS with Back. Open NEXUS again any time from its icon.\n\nOn the next screen, choose NEXUS as your Home app.",
            listOf(NexusUi.Option("Set it up", primary = true) { openHomeSettings() }), cancel = "Not now")
    }

    private fun openHomeSettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun chooseNormalHome() {
        val homes = HomeMode.otherHomes(this)
        if (homes.isEmpty()) { Toast.makeText(this, "No other home screen found", Toast.LENGTH_SHORT).show(); return }
        val current = HomeMode.normalHome(this)
        NexusUi.panel(this, "Your normal home screen", "Where NEXUS takes you when you close it.",
            homes.map { h -> NexusUi.Option((if (h.second == current) "✓  " else "     ") + h.first, primary = h.second == current) { HomeMode.setNormalHome(this, h.second) } })
    }

    /** The gear: NEXUS settings. */
    private fun homeSettings() {
        val isHome = HomeMode.isDefault(this)
        NexusUi.panel(this, "NEXUS settings", null, listOf(
            NexusUi.Option(if (isHome) "NEXUS is your home screen  ✓" else "Make NEXUS my home screen", primary = !isHome) { openHomeSettings() },
            NexusUi.Option("Choose my normal home screen") { chooseNormalHome() },
            NexusUi.Option("Open apps (square button)") { openAppsSettings() },
            NexusUi.Option("Tutorial") { showTutorial() },
            NexusUi.Option("How it works") {
                NexusUi.panel(this, "How it works",
                    "• While NEXUS is open, Home brings you back to NEXUS, even after the screen turns off and on.\n• Close NEXUS (Back, or Close NEXUS here) and Home goes to your normal home screen.\n• Open NEXUS from its icon to make it your home again.\n• Swipe up or tap ALL APPS to see every app. Hold an app in the list to move it.\n• Tap RECENT (or press the square button, once the helper is on) for your open apps.\n\nTo stop using NEXUS as Home completely, choose your old home screen in Settings → Apps → Default apps → Home app.",
                    emptyList(), cancel = "OK")
            },
            NexusUi.Option("Close NEXUS", danger = true) { askClose() }
        ), cancel = "Done")
    }

    private fun hideBars() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        try {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onDestroy() {
        super.onDestroy()
        sfx.release()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isHomeIntent(intent) && intent?.getBooleanExtra("fromApp", false) != true) {
            // Home pressed: NEXUS mode on = stay in / come back to NEXUS; off = normal home screen
            if (!HomeMode.isOn(this) && HomeMode.goNormalHome(this)) return
            leftForApp = android.os.SystemClock.uptimeMillis() - pausedAt > 700
            return
        }
        // Opened from its icon or back from one of the apps
        HomeMode.setOn(this, true)
        leftForApp = true // brought back from one of the apps
    }

    override fun onResume() {
        super.onResume()
        if (::hub.isInitialized) { refresh(); hub.resumed(leftForApp) }
        leftForApp = false
        hideBars()
    }

    private fun open(g: GameEntry) {
        val launch = packageManager.getLaunchIntentForPackage(g.pkg)
        if (launch != null) {
            // Cross-fade into the app
            leftForApp = true
            startActivity(launch, android.app.ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            return
        }
        // Not installed yet: open its download page
        val tag = when (g.pkg) { "com.zombiedots.app" -> "zombie-build"; "com.ashenvow.app" -> "ashen-build"; else -> "synth-build" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/buickdriver2001-gif/countdown-app/releases?q=$tag&expanded=true")))
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open the download page", Toast.LENGTH_SHORT).show()
        }
    }

    /** A soft, airy start-up chord that swells in and fades away (generated, no sound files). */
    private fun playChime() {
        try {
            val rate = 22050
            val secs = 4.5f
            val n = (rate * secs).toInt()
            val buf = ShortArray(n)
            val notes = floatArrayOf(220f, 277.18f, 329.63f, 415.3f, 554.37f, 880f)
            val gains = floatArrayOf(0.22f, 0.18f, 0.18f, 0.14f, 0.1f, 0.05f)
            for (i in 0 until n) {
                val t = i.toFloat() / rate
                val env = min(1f, t / 1.3f) * exp(-(t / 2.2f).let { it * it }) * 1.6f
                var v = 0f
                for (k in notes.indices) {
                    val f = notes[k]
                    // Two slightly detuned voices per note for a shimmering pad
                    v += gains[k] * (sin(2 * PI * f * t).toFloat() + sin(2 * PI * f * 1.004 * t + k).toFloat()) * 0.5f
                }
                // A faint high sparkle that arrives a moment later
                v += 0.04f * sin(2 * PI * 1760.0 * t).toFloat() * min(1f, kotlin.math.max(0f, t - 0.8f)) * exp(-t * 1.2f)
                buf[i] = (v * env * 0.45f * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, n)
            track.play()
            Thread.sleep((secs * 1000).toLong() + 200)
            track.release()
        } catch (_: Exception) {
        }
    }
}
