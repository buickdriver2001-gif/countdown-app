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
        GameEntry("Ashen Vow", "com.ashenvow.app", "Defeat Hollow Warden", Color.rgb(230, 150, 60), 1),
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

    /** Hold an app (without moving it): tile size, and for added apps edit / remove. */
    private fun appOptions(g: GameEntry) {
        val opts = ArrayList<NexusUi.Option>()
        opts.add(NexusUi.Option("Tile size:  ${sizeName(g.tileW, g.tileH)}", primary = true) { chooseTileSize(g) })
        opts.add(NexusUi.Option("Open") { open(g) })
        if (!g.custom) {
            NexusUi.panel(this, g.name, null, opts)
            return
        }
        NexusUi.panel(this, g.name, null, opts + listOf(
            NexusUi.Option("Edit description") { editDescription(g) },
            NexusUi.Option("App info") {
                try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${g.pkg}"))) } catch (_: Exception) {}
            },
            NexusUi.Option("Remove from NEXUS", danger = true) { removeGame(g) }
        ))
    }

    private fun sizeName(w: Int, h: Int): String = when {
        w >= hub.gridCols && h == 1 -> "Full row"
        w == 1 && h == 1 -> "Small 1×1"
        w == 2 && h == 1 -> "Wide 2×1"
        w == 1 && h == 2 -> "Tall 1×2"
        w == 2 && h == 2 -> "Large 2×2"
        else -> "${w}×$h"
    }

    private fun chooseTileSize(g: GameEntry) {
        val sizes = listOf(1 to 1, 2 to 1, 1 to 2, 2 to 2, 99 to 1)
        NexusUi.panel(this, "Tile size", g.name, sizes.map { (w, h) ->
            val cur = (if (w == 99) g.tileW >= hub.gridCols else g.tileW == w) && g.tileH == h
            NexusUi.Option((if (cur) "✓  " else "     ") + (if (w == 99) "Full row" else sizeName(w, h)), primary = cur) {
                prefs.edit().putString("tile_${g.pkg}", "${w}x$h").apply()
                refresh()
            }
        })
    }

    private fun chooseGrid() {
        val cur = prefs.getString("grid", "4x6")
        val grids = listOf("3x5" to "3 × 5  (big tiles)", "4x6" to "4 × 6  (standard)", "4x8" to "4 × 8  (compact)")
        NexusUi.panel(this, "Grid size", "Columns × rows that fit on one screen. More apps scroll.", grids.map { (k, label) ->
            NexusUi.Option((if (k == cur) "✓  " else "     ") + label, primary = k == cur) {
                prefs.edit().putString("grid", k).apply()
                refresh()
            }
        })
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
        for (g in list) {
            g.installed = packageManager.getLaunchIntentForPackage(g.pkg) != null
            val t = prefs.getString("tile_${g.pkg}", "2x1")!!.split("x")
            g.tileW = t.getOrNull(0)?.toIntOrNull() ?: 2; g.tileH = t.getOrNull(1)?.toIntOrNull() ?: 1
        }
        val grid = prefs.getString("grid", "4x6")!!.split("x")
        hub.gridCols = grid.getOrNull(0)?.toIntOrNull() ?: 4; hub.gridRows = grid.getOrNull(1)?.toIntOrNull() ?: 6
        hub.games = list
        hub.loadLiveShots()
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
        startActivity(launch, Transitions.rise(this))
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
            { from, to -> moveApp(from, to) }, { drawer.open(add = false) }, { openSwitcher() }, { openSystem() })
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

    private fun openSystem() {
        startActivity(Intent(this, SystemActivity::class.java), Transitions.rise(this, solidSplash = true))
    }

    private fun openSwitcher() {
        startActivity(Intent(this, SwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), Transitions.rise(this, solidSplash = true))
    }

    /** NEXUS's own App info page (where "Allow restricted settings" lives, under ⋮). */
    private fun openAppInfo() {
        try { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }

    private fun openAccessibility() {
        try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (_: Exception) {}
    }

    /** Settings → Recent apps: status, setup steps, and an on/off switch for the square button. */
    private fun openAppsSettings() {
        val on = Recents.serviceOn(this)
        val replace = prefs.getBoolean("replaceRecents", true)
        val status = if (on) "Helper is ON. Recent apps are being remembered." else "Helper is OFF. Follow the two steps below to turn it on."
        val opts = ArrayList<NexusUi.Option>()
        if (!on) {
            opts.add(NexusUi.Option("1.  Turn on helper", primary = true) { openAccessibility() })
            opts.add(NexusUi.Option("2.  Allow restricted settings (if asked)") { openAppInfo() })
        } else opts.add(NexusUi.Option("Turn helper off") { openAccessibility() })
        opts.add(NexusUi.Option("Square button opens NEXUS Recent Apps:  " + (if (replace) "ON" else "OFF")) {
            prefs.edit().putBoolean("replaceRecents", !replace).apply()
            openAppsSettings()
        })
        opts.add(NexusUi.Option("Show Recent Apps now") { openSwitcher() })
        NexusUi.panel(this, "Recent apps", "$status\n\nStep 1: in Accessibility, choose NEXUS Recent Apps and switch it on.\nStep 2: if Android says \"Restricted setting\", open App info, tap ⋮ at the top right and choose Allow restricted settings. Then do step 1 again.\n\nPictures of your apps stay on this phone and are never sent anywhere.", opts, cancel = "Done")
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

    /**
     * Sets the phone's home-screen wallpaper to NEXUS's deep blue, so the split second Android shows
     * its own screens (like recent apps) looks like NEXUS instead of the old home screen.
     */
    private fun askWallpaper() {
        NexusUi.panel(this, "Match wallpaper to NEXUS?",
            "Your phone briefly shows its wallpaper when Android opens its own screens (like the square button). " +
                "This sets your wallpaper to NEXUS's deep blue so those moments look like NEXUS too.\n\n" +
                "You can pick a different wallpaper any time in your phone's wallpaper settings.",
            listOf(NexusUi.Option("Home screen only", primary = true) { setNexusWallpaper(lock = false) },
                NexusUi.Option("Home and lock screen") { setNexusWallpaper(lock = true) }), cancel = "Not now")
    }

    /** NEXUS lock screen: explanation and on/off. */
    private fun lockSettings() {
        val on = LockActivity.enabled(this)
        val helper = Recents.serviceOn(this)
        val msg = "When your screen turns on, NEXUS shows its own lock screen (clock, date, battery) on top of your phone's. " +
            "Swipe up and your phone's normal PIN, pattern or fingerprint unlock appears, so your phone stays just as secure. " +
            "It never covers calls or alarms, and your notifications and camera are still on the phone's lock screen underneath." +
            (if (!helper) "\n\nNeeds the Recent Apps helper switched on (Gear icon → Recent apps)." else "")
        val opts = ArrayList<NexusUi.Option>()
        if (!helper) opts.add(NexusUi.Option("Turn on helper first", primary = true) { openAppsSettings() })
        opts.add(NexusUi.Option(if (on) "Turn NEXUS lock screen OFF" else "Turn NEXUS lock screen ON", primary = helper && !on) {
            prefs.edit().putBoolean("lockScreen", !on).apply()
            NexusAccessibility.instance?.refreshCover()
            Toast.makeText(this, if (!on) "NEXUS lock screen on. Turn your screen off and on to see it." else "NEXUS lock screen off", Toast.LENGTH_SHORT).show()
        })
        NexusUi.panel(this, "NEXUS lock screen", msg, opts, cancel = "Done")
    }

    private fun setNexusWallpaper(lock: Boolean) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(720); val h = dm.heightPixels.coerceAtLeast(1280)
        Thread {
            val ok = try {
                val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.rgb(3, 6, 18), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
                val step = 40 * dm.density
                p.strokeWidth = 1f; p.color = Color.argb(14, 140, 190, 255)
                var x = 0f; while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), p); x += step }
                var y = 0f; while (y < h) { c.drawLine(0f, y, w.toFloat(), y, p); y += step }
                p.shader = android.graphics.RadialGradient(w / 2f, h * 0.45f, w * 0.9f, intArrayOf(Color.argb(70, 40, 90, 220), Color.TRANSPARENT), null, android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                val wm = android.app.WallpaperManager.getInstance(this)
                val which = android.app.WallpaperManager.FLAG_SYSTEM or (if (lock) android.app.WallpaperManager.FLAG_LOCK else 0)
                if (android.os.Build.VERSION.SDK_INT >= 24) wm.setBitmap(b, null, true, which) else wm.setBitmap(b)
                b.recycle()
                true
            } catch (_: Exception) { false }
            runOnUiThread { Toast.makeText(this, if (ok) "Wallpaper set" else "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    /** The gear: NEXUS settings. */
    private fun homeSettings() {
        val isHome = HomeMode.isDefault(this)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        NexusUi.panel(this, "NEXUS settings", "Version $version", listOf(
            NexusUi.Option("System", primary = true) { openSystem() },
            NexusUi.Option(if (isHome) "NEXUS is your home screen  ✓" else "Make NEXUS my home screen", primary = !isHome) { openHomeSettings() },
            NexusUi.Option("Grid size:  ${prefs.getString("grid", "4x6")!!.replace("x", " × ")}") { chooseGrid() },
            NexusUi.Option("Choose my normal home screen") { chooseNormalHome() },
            NexusUi.Option("Recent apps (square button)") { openAppsSettings() },
            NexusUi.Option("NEXUS lock screen:  " + (if (LockActivity.enabled(this)) "ON" else "OFF")) { lockSettings() },
            NexusUi.Option("Match wallpaper to NEXUS") { askWallpaper() },
            NexusUi.Option("Tutorial") { showTutorial() },
            NexusUi.Option("How it works") {
                NexusUi.panel(this, "How it works",
                    "• While NEXUS is open, Home brings you back to NEXUS, even after the screen turns off and on.\n• Close NEXUS (Back, or Close NEXUS here) and Home goes to your normal home screen.\n• Open NEXUS from its icon to make it your home again.\n• Swipe up or tap ALL APPS to see every app. Hold an app in the list to move it.\n• Tap RECENT (or press the square button, once the helper is on) for your recent apps.\n\nTo stop using NEXUS as Home completely, choose your old home screen in Settings → Apps → Default apps → Home app.",
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
            startActivity(launch, Transitions.rise(this))
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
