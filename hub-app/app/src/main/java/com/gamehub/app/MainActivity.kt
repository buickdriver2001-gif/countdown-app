package com.gamehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity(), NexusUi.NavHost {

    private val builtIn = listOf(
        GameEntry("Zombie Dots", "com.zombiedots.app", Color.rgb(255, 80, 80), 0),
        GameEntry("Ashen Vow", "com.ashenvow.app", Color.rgb(230, 150, 60), 1),
        GameEntry("SynthPad", "com.synthpad.app", Color.rgb(120, 220, 255), 2),
        GameEntry("Torque Street", "com.torquestreet.app", Color.rgb(255, 122, 26), 3)
    )
    private lateinit var hub: HubView
    private lateinit var drawer: AppDrawer
    private lateinit var tutorial: TutorialView
    private lateinit var navBar: NavBar
    /** NEXUS's own notification panel (pulled down from the top of the home screen). */
    private lateinit var notifPanel: NotifPanel
    private val sfx by lazy { Sfx() }
    private var leftForApp = false
    private val prefs by lazy { getSharedPreferences("hub", MODE_PRIVATE) }

    /** Games the player added from their phone, in the order they were added. */
    private fun customPkgs(): List<String> = prefs.getString("custom", "")!!.split(",").filter { it.isNotBlank() }

    /**
     * Added apps' tiles (name and icon), read from Android once and kept: coming back to NEXUS doesn't
     * ask Android again. An app's tile is read afresh when it's installed, updated or removed.
     */
    private val entries = HashMap<String, GameEntry>()
    /** Whether each app can be opened (also only re-checked when apps change). */
    private val installed = HashMap<String, Boolean>()

    /** Forget what's known about [pkg] (it was installed, updated or removed). */
    private fun forgetApp(pkg: String) { entries.remove(pkg); installed.remove(pkg) }

    private fun buildList(): List<GameEntry> {
        val list = ArrayList(builtIn)
        for (pkg in customPkgs()) {
            val known = entries[pkg]
            if (known != null) { list.add(known); continue }
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                val label = packageManager.getApplicationLabel(info).toString()
                val g = GameEntry(label, pkg, Color.rgb(170, 200, 255), -1, custom = true,
                    drawable = packageManager.getApplicationIcon(pkg))
                entries[pkg] = g
                list.add(g)
            } catch (_: Exception) { /* uninstalled since: skip it */ }
        }
        return list
    }

    /** Descriptions are gone: tidy away any that were saved by older versions. */
    private fun dropOldDescriptions() {
        val old = prefs.all.keys.filter { it.startsWith("desc_") }
        if (old.isNotEmpty()) prefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    /** Hold an app (without moving it): its shortcuts, tile size, App info, and remove / uninstall. */
    private fun appOptions(g: GameEntry) {
        val opts = ArrayList<NexusUi.Option>()
        // The app's own shortcuts first (Android shares them with the Home app), then NEXUS's options
        opts.addAll(AppActions.shortcutOptions(this, g.pkg))
        if (homeStyle() == "tiles") opts.add(NexusUi.Option("Tile size:  ${sizeName(g.tileW, g.tileH)}", primary = true) { chooseTileSize(g) })
        opts.add(NexusUi.Option("Open") { open(g) })
        if (g.installed) opts.add(AppActions.infoOption(this, g.pkg))
        if (g.custom) opts.add(NexusUi.Option("Remove from NEXUS", danger = true) { removeGame(g) })
        if (g.installed) AppActions.uninstallOption(this, g.pkg)?.let { opts.add(it) }
        NexusUi.panel(this, g.name, null, opts)
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
                homeScreenSettings()
            }
        }, cancel = "Back", back = { homeScreenSettings() })
    }

    /** The home screen list in A–Z order (Settings → Home screen → App order). */
    private fun alphabetical() = prefs.getBoolean("alphaOrder", false)

    /** Settings → Home screen → App order: A–Z, or the order you arranged them in (kept while A–Z is on). */
    private fun toggleAlphabetical(back: () -> Unit = { homeScreenSettings() }) {
        val az = !alphabetical()
        prefs.edit().putBoolean("alphaOrder", az).apply()
        refresh()
        back()
        NexusUi.note(this, if (az) "Apps are in A–Z order" else "Apps are back in your own order")
    }

    /** Saves a new position for an app (the order is kept by package name). */
    private fun moveApp(from: Int, to: Int) {
        if (alphabetical()) return
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
        // A–Z order (Settings → Home screen): by name, as your language sorts it (capitals and accents don't matter).
        // Your own order stays saved underneath, for when you switch back.
        val az = alphabetical()
        if (az) {
            val coll = java.text.Collator.getInstance().apply { strength = java.text.Collator.PRIMARY }
            list.sortWith { a, b -> coll.compare(a.name.trim(), b.name.trim()).let { c -> if (c != 0) c else a.pkg.compareTo(b.pkg) } }
        }
        hub.canReorder = !az
        for (g in list) {
            g.installed = installed.getOrPut(g.pkg) { packageManager.getLaunchIntentForPackage(g.pkg) != null }
            val t = prefs.getString("tile_${g.pkg}", "2x1")!!.split("x")
            g.tileW = t.getOrNull(0)?.toIntOrNull() ?: 2; g.tileH = t.getOrNull(1)?.toIntOrNull() ?: 1
        }
        val grid = prefs.getString("grid", "4x6")!!.split("x")
        hub.gridCols = grid.getOrNull(0)?.toIntOrNull() ?: 4; hub.gridRows = grid.getOrNull(1)?.toIntOrNull() ?: 6
        hub.ps2Menu = homeStyle() == "ps2"
        hub.dataGrid = homeStyle() == "grid"
        hub.notifIcons = NexusNotifications.barOn(this)
        hub.notifBoxes = NexusNotifications.boxesOn(this)
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
        NexusUi.note(this, msg)
    }

    /** Opens any app from All Apps (that exact icon, if the app has more than one), with the same smooth fade. */
    private fun openItem(item: AppItem) {
        // The app opens out of the icon that was tapped in All Apps
        val v = drawer.lastTapped
        val opts = if (v != null && v.isAttachedToWindow) Transitions.grow(v, android.graphics.RectF(0f, 0f, v.width.toFloat(), v.height.toFloat()))
                   else Transitions.app(window.decorView)
        drawer.lastTapped = null
        // NEXUS's own quick fade covers the opening (instead of the phone's slide-in)
        val veiled = NexusAccessibility.instance?.veilForLaunch(item.pkg, null, 90L) == true
        if (AppActions.open(this, item.pkg, item.component, opts)) leftForApp = true
        else if (veiled) NexusAccessibility.instance?.cancelVeil()
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
        drawer = AppDrawer(this, sfx, { openItem(it) }, { addApps(it) }, { pkg -> hub.games.any { it.pkg == pkg } })
        hub.onPullDown = { openNotifications() }
        hub.onReorderBlocked = { NexusUi.note(this, "Apps are in A–Z order. To arrange them yourself, switch Settings → App order to your own", long = true) }
        hub.onNotification = { item, from -> openNotification(item, from) }
        // The phone's own buttons show over NEXUS's for a moment after a swipe down from the top: NEXUS's step aside
        hub.onPhoneBars = { away ->
            if (::navBar.isInitialized && !hub.booting && navBar.visibility == android.view.View.VISIBLE)
                navBar.animate().alpha(if (away) 0f else 1f).setDuration(if (away) 90L else 220L).start()
        }
        drawer.onVisible = { open -> hub.covered = open; if (open) hub.clearSelection() }
        val root = android.widget.FrameLayout(this)
        root.addView(hub)
        root.addView(drawer)
        // NEXUS's own notification panel: over the home screen, under NEXUS's Back / Home / Recent buttons
        notifPanel = NotifPanel(this, sfx, object : NotifPanel.Host {
            override fun openNotification(pkg: String, intent: android.app.PendingIntent?, key: String, autoCancel: Boolean, from: android.graphics.RectF?) =
                sendNotification(pkg, intent, key, autoCancel, from)
            override fun sendAction(intent: android.app.PendingIntent) {
                try { intent.send(this@MainActivity, 0, null, null, null, null, startOpts(null)) }
                catch (_: Exception) { NexusUi.note(this@MainActivity, "That didn't work. The app may have changed the notification") }
            }
            override fun phoneQuickSettings() {
                // (Asked for on purpose: NEXUS leaves the phone's own panel alone this time)
                if (NexusAccessibility.phoneQuickSettings()) return
                try {
                    @android.annotation.SuppressLint("WrongConstant") val bar = getSystemService("statusbar")
                    Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(bar)
                } catch (_: Throwable) { NexusUi.note(this@MainActivity, "Swipe down twice from the top of an app for the phone's quick settings", long = true) }
            }
            override fun covering(on: Boolean) { if (::hub.isInitialized && !(::drawer.isInitialized && drawer.isOpen)) hub.covered = on }
            override fun note(text: String) = NexusUi.note(this@MainActivity, text)
        }, NavBar.heightPx(this))
        notifPanel.elevation = 30 * resources.displayMetrics.density
        notifPanel.outlineProvider = null
        root.addView(notifPanel, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        hub.panelPull = { dy -> if (panelAvailable()) { notifPanel.pull(dy); true } else false }
        hub.panelRelease = { vy -> notifPanel.release(vy) }
        hub.stillCovered = { (::drawer.isInitialized && drawer.isOpen) || (::notifPanel.isInitialized && notifPanel.fullyOpen) }
        navBar = NavBar(this,
            onBack = { sfx.tick(); @Suppress("DEPRECATION") onBackPressed() },
            onHome = { sfx.tick(); goHome(onScreen = true) },
            onRecent = { sfx.tick(); if (::notifPanel.isInitialized) notifPanel.closeNow(); openSwitcher() })
        // Above All apps / Add app too (which sits raised over the home screen), so the buttons work there as well;
        // only the first-run guide covers them. (No shadow: it's a flat strip)
        navBar.elevation = 35 * resources.displayMetrics.density
        navBar.outlineProvider = null
        root.addView(navBar, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            NavBar.heightPx(this), android.view.Gravity.BOTTOM))
        applyNavBar()
        tutorial = TutorialView(this, sfx, object : TutorialView.Actions {
            override fun setHome() = openHomeSettings()
            override fun appInfo() = openAppInfo()
            override fun accessibility() = openAccessibility()
            override fun notificationAccess() = openNotificationAccess()
            override fun done() = closeTutorial()
        }).apply { visibility = android.view.View.GONE }
        root.addView(tutorial)
        setContentView(root)
        // The home screen paints the whole screen itself every frame: no need to paint the window under it too
        window.setBackgroundDrawable(null)
        home = java.lang.ref.WeakReference(this)
        // A panel open over the home screen (Settings and the like): its crystals come out
        NexusUi.onPanels = { act, open -> if (act === this && ::hub.isInitialized) hub.panelOpen = open }
        dropOldDescriptions()
        addCountdownTileOnce()
        drawer.load()
        // Keep up with apps being installed, updated or removed (an app being updated briefly disappears)
        try { getSystemService(android.content.pm.LauncherApps::class.java)?.registerCallback(appsChanged, android.os.Handler(mainLooper)) } catch (_: Exception) {}
        // Full screen: no status bar, no navigation buttons (swipe from an edge to peek at them)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        hideBars()
        val fromApp = intent?.getBooleanExtra("fromApp", false) == true
        val newBoot = firstStartSincePhoneOn()
        if (fromApp) { hub.skipIntro(); leftForApp = true }
        else if (isHomeIntent(intent) && !HomeMode.isOn(this) && HomeMode.goNormalHome(this)) {
            // NEXUS was closed on purpose: Home goes to the normal home screen
            hub.skipIntro()
        } else {
            // Opened from its icon (or Home while NEXUS mode is on): NEXUS mode on
            HomeMode.setOn(this, true)
            // The start-up show (and chime) plays when the phone has just been turned on, or when NEXUS is
            // started from its icon. When Android just brings NEXUS back as the Home app, it's there at once.
            val fromIcon = intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true && !isHomeIntent(intent)
            if ((newBoot || fromIcon) && savedInstanceState == null) Thread { playChime() }.start()
            else hub.skipIntro()
            // First time: show the guide once the start-up animation has finished
            if (!prefs.getBoolean("tutorialSeen", false)) waitThenTutorial()
        }
    }

    private fun isHomeIntent(i: Intent?) = i?.hasCategory(Intent.CATEGORY_HOME) == true

    /** True the first time NEXUS starts after the phone was turned on (remembered, so only once). */
    private fun firstStartSincePhoneOn(): Boolean {
        val bootAt = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val last = prefs.getLong("introBootAt", 0L)
        if (kotlin.math.abs(bootAt - last) < 120_000L) return false
        prefs.edit().putLong("introBootAt", bootAt).apply()
        return true
    }

    private var pausedAt = 0L
    /** When this screen last came into view (with pausedAt: whether it was really on screen when Home came). */
    private var resumedAt = 0L

    override fun onPause() {
        super.onPause()
        inFront = false
        pausedAt = android.os.SystemClock.uptimeMillis()
        ambience.stop()
    }

    /** The home screen's soft background sound (Settings → Sound). */
    private val ambience by lazy { Ambience(this) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            ::notifPanel.isInitialized && notifPanel.isOpen -> notifPanel.close()
            ::tutorial.isInitialized && tutorial.visibility == android.view.View.VISIBLE -> closeTutorial()
            ::drawer.isInitialized && drawer.isOpen -> drawer.close()
            // As the phone's Home app, NEXUS stays put on Back (like any launcher); Close NEXUS is under the gear
            HomeMode.isDefault(this) -> if (::hub.isInitialized) hub.clearSelection()
            else -> askClose()
        }
    }

    /**
     * Home pressed: tidy up like other launchers. Panels and All Apps close; if NEXUS was already
     * on screen with nothing open, the list glides back to the top.
     */
    private fun goHome(onScreen: Boolean) {
        var tidied = NexusUi.dismissAll(this)
        if (::notifPanel.isInitialized && notifPanel.isOpen) { if (onScreen) notifPanel.close() else notifPanel.closeNow(); tidied = true }
        if (::drawer.isInitialized && drawer.isOpen) { drawer.close(animate = onScreen); tidied = true }
        if (::hub.isInitialized) { if (onScreen && !tidied) hub.toTop() else hub.clearSelection() }
    }

    /**
     * A notification box on the home screen tapped: what it leads to opens (as if it had been tapped in the
     * notification shade), growing out of the box. If it leads nowhere, the app itself opens.
     */
    private fun openNotification(item: NexusNotifications.Item, from: android.graphics.RectF) =
        sendNotification(item.pkg, item.intent, item.key, item.autoCancel, from)

    /**
     * Opens what a notification leads to (from a home screen box or NEXUS's notification panel), as if it had been
     * tapped in the phone's own shade, growing out of [from]. If it leads nowhere, the app itself opens.
     */
    private fun sendNotification(pkg: String, intent: android.app.PendingIntent?, key: String, autoCancel: Boolean, from: android.graphics.RectF?) {
        var ok = false
        intent?.let { pi ->
            ok = try { pi.send(this, 0, null, null, null, null, startOpts(from)); true } catch (_: Exception) { false }
        }
        if (!ok) ok = try {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false
        } catch (_: Exception) { false }
        if (ok) leftForApp = true
        else NexusUi.note(this, "That notification couldn't be opened")
        // (Only ones the app marked to go away when tapped, as the notification shade does)
        if (autoCancel) NexusNotifications.dismiss(key)
    }

    /** How a notification's app opens: growing out of [from] (if given); NEXUS is in front, so it may open, as from the shade. */
    private fun startOpts(from: android.graphics.RectF?): android.os.Bundle {
        val base = if (from != null) android.app.ActivityOptions.makeScaleUpAnimation(hub, from.left.toInt(), from.top.toInt(),
            from.width().toInt().coerceAtLeast(1), from.height().toInt().coerceAtLeast(1)) else android.app.ActivityOptions.makeBasic()
        @Suppress("DEPRECATION")
        val o = if (android.os.Build.VERSION.SDK_INT >= 34)
            base.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED) else base
        return o.toBundle()
    }

    /** On screen and in front (between onResume and onPause). */
    private var inFront = false
    private fun notifPanelIsOpen() = ::notifPanel.isInitialized && notifPanel.isOpen
    private var accessSeen: Boolean? = null

    /** NEXUS's own notification panel comes down (Notification access allowed, and not switched to the phone's). */
    private fun panelAvailable() = NexusNotifications.granted(this) && NexusNotifications.panelOn(this) &&
        !(::drawer.isInitialized && drawer.isOpen)

    /** Swipe down on NEXUS: the notifications, the supported way first. */
    private fun openNotifications() {
        if (::notifPanel.isInitialized && panelAvailable()) { notifPanel.open(); return }
        try { if (NexusAccessibility.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) == true) return } catch (_: Exception) {}
        try {
            @android.annotation.SuppressLint("WrongConstant") val bar = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(bar)
            return
        } catch (_: Throwable) {}
        // Last resort: bring the status bar in so it can be pulled down
        try { if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.show(android.view.WindowInsets.Type.statusBars()) } catch (_: Exception) {}
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

    private fun openSwitcher() = openRecents(this)

    // NEXUS's Back / Home / Recent buttons over a panel (see NexusUi.NavHost)
    override fun navTick() { sfx.tick() }
    override fun navHome() { sfx.tick(); goHome(onScreen = true) }
    override fun navRecent() { sfx.tick(); openSwitcher() }

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
        val status = when {
            on && helperRunning() -> "Helper is ON. Recent apps are being remembered."
            on -> "The helper is switched on, but Android has stopped it. Tap Turn helper off/on below, switch NEXUS Recent Apps off and back on."
            else -> "Helper is OFF. Follow the two steps below to turn it on."
        }
        val opts = ArrayList<NexusUi.Option>()
        if (!on) {
            opts.add(NexusUi.Option("1.  Turn on helper", primary = true) { openAccessibility() })
            opts.add(NexusUi.Option("2.  Allow restricted settings (if asked)") { openAppInfo() })
        } else opts.add(NexusUi.Option("Turn helper off") { openAccessibility() })
        opts.add(NexusUi.Option("Square button opens NEXUS Recent Apps:  " + (if (replace) "ON" else "OFF")) {
            prefs.edit().putBoolean("replaceRecents", !replace).apply()
            openAppsSettings()
        })
        val full = NexusAccessibility.fullCloseOn(this)
        opts.add(NexusUi.Option("Fully close apps you swipe away:  " + (if (full) "ON" else "OFF")) {
            prefs.edit().putBoolean("fullClose", !full).apply()
            openAppsSettings()
        })
        if (on && helperRunning() && full) opts.add(NexusUi.Option("Close every open app now") { askCloseEverything() })
        opts.add(NexusUi.Option("Show Recent Apps now") { openSwitcher() })
        NexusUi.panel(this, "Recent apps helper", "$status\n\nThe helper runs NEXUS Recent Apps, the NEXUS lock screen and NEXUS's buttons in apps.\n\n" +
            "To turn it on: in Accessibility, choose NEXUS Recent Apps and switch it on. If Android says \"Restricted setting\", open App info, tap ⋮ at the top right, choose Allow restricted settings, then try again.\n\n" +
            "Fully closing apps: Android only lets its own recent apps screen close other apps, so when you swipe an app away in NEXUS Recent Apps, NEXUS opens that screen behind Recent Apps and presses Close for you. While it's there, it also closes any app still open in the phone that NEXUS Recent Apps doesn't show, so the two always match. It only reads that screen, only while closing apps. Nothing is ever sent anywhere.",
            opts, cancel = "Back", back = { homeSettings() })
    }

    /** Settings → Recent apps helper → Close every open app: everything open in the phone closes, with the closing cube. */
    private fun askCloseEverything() {
        NexusUi.panel(this, "Close every open app?",
            "Every app open on your phone is closed, including any that NEXUS Recent Apps doesn't show. Apps keep " +
                "whatever they've saved, as when you swipe them away. It takes a few seconds.",
            listOf(NexusUi.Option("Close them all", primary = true) {
                NexusUi.dismissAll(this)
                if (!NexusAccessibility.closeEverything(this)) NexusUi.note(this, "Couldn't start: the Recent Apps helper isn't running, or NEXUS is busy")
            }), cancel = "Back", back = { openAppsSettings() })
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
            "Home will take you to NEXUS, even after the screen turns off and on. To use your normal home screen for a while, tap the gear and choose Close NEXUS. Open NEXUS again any time from its icon.\n\nOn the next screen, choose NEXUS as your Home app.",
            listOf(NexusUi.Option("Set it up", primary = true) { openHomeSettings() }), cancel = "Not now")
    }

    private var homeAskedAt = 0L

    /** Make NEXUS the Home app: Android's own prompt (Android 10 and up), or the Home app setting. */
    private fun openHomeSettings() {
        if (android.os.Build.VERSION.SDK_INT >= 29 && !HomeMode.isDefault(this)) {
            try {
                val rm = getSystemService(android.app.role.RoleManager::class.java)
                if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                    homeAskedAt = android.os.SystemClock.uptimeMillis()
                    startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), REQ_HOME)
                    return
                }
            } catch (_: Exception) {}
        }
        homeAppSetting()
    }

    private fun homeAppSetting() {
        try { startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_HOME) return
        if (HomeMode.isDefault(this)) { NexusUi.note(this, "NEXUS is now your home screen"); return }
        // Answered instantly = Android has stopped asking (it was declined before): open the setting instead
        if (android.os.SystemClock.uptimeMillis() - homeAskedAt < 700) homeAppSetting()
    }

    private fun chooseNormalHome() {
        val homes = HomeMode.otherHomes(this)
        if (homes.isEmpty()) { NexusUi.note(this, "No other home screen found"); return }
        val current = HomeMode.normalHome(this)
        NexusUi.panel(this, "Your normal home screen", "Where NEXUS takes you when you close it.",
            homes.map { h -> NexusUi.Option((if (h.second == current) "✓  " else "     ") + h.first, primary = h.second == current) { HomeMode.setNormalHome(this, h.second); homeScreenSettings() } },
            cancel = "Back", back = { homeScreenSettings() })
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
                NexusUi.Option("Home and lock screen") { setNexusWallpaper(lock = true) }), cancel = "Back", back = { homeScreenSettings() })
    }

    /** Something went wrong inside the Recent Apps helper (NEXUS kept running): show the details to copy. */
    private fun reportProblem() {
        val f = App.reportFile(this)
        val problems = try { if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        val text = "NEXUS $version, Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
            "Lock screen: " + (if (LockActivity.enabled(this)) "ON" else "OFF") + ", helper: " + (if (helperRunning()) "running" else "not running") +
            ", home: " + (if (HomeMode.isDefault(this)) "NEXUS" else "other") + "\n\n" +
            (if (problems.isNotBlank()) "PROBLEMS\n$problems\n" else "No problems noted.\n\n") +
            "LOCK SCREEN LOG (newest last)\n" + LockTrace.text(this).takeLast(5000)
        val opts = ArrayList<NexusUi.Option>()
        opts.add(NexusUi.Option("Copy the details", primary = true) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("NEXUS problem", text))
            NexusUi.note(this, "Copied. Paste it to Claude.")
        })
        if (problems.isNotBlank()) opts.add(NexusUi.Option("Clear noted problems") { f.delete(); NexusUi.note(this, "Noted problems cleared") })
        NexusUi.panel(this, "Report a problem", "If something isn't working (for example the lock screen), copy these details " +
            "and send them to Claude so it can be fixed. They only list what NEXUS did and when; nothing personal.", opts, cancel = "Back", back = { helpSettings() })
    }

    /** NEXUS lock screen: explanation and on/off. */
    private fun lockSettings() {
        val on = LockActivity.enabled(this)
        val helper = Recents.serviceOn(this)
        val msg = "When your screen turns on, NEXUS shows its own lock screen (clock, date, battery) on top of your phone's. " +
            "Swipe up and your phone's normal PIN, pattern or fingerprint unlock appears, so your phone stays just as secure. " +
            "It never covers calls or alarms, and the camera is still on the phone's lock screen underneath. " +
            "Left alone, it fades out and the screen turns off (after 8 seconds unless you change it below).\n\n" +
            "Notifications: NEXUS can show them on its lock screen too (tap one, unlock, and it opens). It follows the same " +
            "privacy rules as your phone's lock screen: apps set to stay off the lock screen never appear, and with " +
            "\"Hide sensitive content\" private notifications only show the app's name. Nothing is stored or sent anywhere." +
            (if (!helper) "\n\nNeeds the Recent Apps helper switched on (Settings → Recent apps helper)." else "")
        val opts = ArrayList<NexusUi.Option>()
        if (!helper) opts.add(NexusUi.Option("Turn on helper first", primary = true) { openAppsSettings() })
        opts.add(NexusUi.Option(if (on) "Turn NEXUS lock screen OFF" else "Turn NEXUS lock screen ON", primary = helper && !on) {
            prefs.edit().putBoolean("lockScreen", !on).apply()
            NexusAccessibility.instance?.refreshCover()
            lockSettings()
            NexusUi.note(this, if (!on) "NEXUS lock screen on. Turn your screen off and on to see it." else "NEXUS lock screen off", long = !on)
        })
        // Left alone, it fades out and the screen turns off
        val off = LockActivity.offAfterSec(this)
        opts.add(NexusUi.Option("Screen turns off after:  " + (if (off > 0) "$off seconds" else "phone's screen timeout")) {
            prefs.edit().putInt("lockOffSec", when (off) { 8 -> 15; 15 -> 30; 30 -> 0; else -> 8 }).apply()
            lockSettings()
        })
        // Where unlocking takes you
        val toHome = LockActivity.unlockToHome(this)
        opts.add(NexusUi.Option("After unlocking:  " + (if (toHome) "NEXUS home screen" else "back to where you were")) {
            prefs.edit().putBoolean("unlockToHome", !toHome).apply()
            lockSettings()
        })
        // Notifications on the NEXUS lock screen
        if (!NexusNotifications.granted(this)) {
            opts.add(NexusUi.Option("Show notifications here: allow access") { askNotificationAccess() })
        } else {
            val mode = NexusNotifications.mode(this)
            val name = when (mode) { "content" -> "Show all content"; "private" -> "Hide sensitive content"; else -> "Off" }
            opts.add(NexusUi.Option("Notifications:  $name") {
                NexusNotifications.setMode(this, when (mode) { "content" -> "private"; "private" -> "off"; else -> "content" })
                lockSettings()
            })
            opts.add(NexusUi.Option("Send a test notification") { sendTestNotification() })
        }
        NexusUi.panel(this, "NEXUS lock screen", msg, opts, cancel = "Back", back = { homeSettings() })
    }

    /** Shows (or hides) the Back / Home / Recent buttons in the chosen order, and makes room for them. */
    private fun applyNavBar() {
        val o = NavBar.order(this)
        val on = o != "off"
        navBar.arrange(o)
        navBar.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
        // Same height as the phone's own button bar, so the buttons sit in the same place here and in your apps
        val px = NavBar.heightPx(this)
        if (navBar.layoutParams.height != px) navBar.layoutParams = navBar.layoutParams.apply { height = px }
        val inset = if (on) px.toFloat() else 0f
        hub.navInset = inset
        drawer.setBottomInset(inset.toInt())
        // Hidden during the start-up show, then they fade in
        if (on && hub.booting) {
            navBar.alpha = 0f
            val check = object : Runnable { override fun run() {
                if (isDestroyed) return
                if (hub.booting) window.decorView.postDelayed(this, 250) else navBar.animate().alpha(1f).setDuration(400).start()
            } }
            window.decorView.postDelayed(check, 250)
        } else navBar.alpha = 1f
    }

    /** A NEXUS notification to try the lock screen and status bar with. It arrives after 5 seconds. */
    private fun sendTestNotification() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        val nm = getSystemService(android.app.NotificationManager::class.java) ?: return
        try {
            nm.createNotificationChannel(android.app.NotificationChannel(NexusNotifications.TEST_CHANNEL, "NEXUS test notifications",
                android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A test notification, sent from NEXUS settings"
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            })
        } catch (_: Exception) {}
        NexusUi.note(this, "A test notification arrives in 5 seconds. Turn your screen off now to see it on the NEXUS lock screen.", long = true)
        val app = applicationContext
        android.os.Handler(mainLooper).postDelayed({
            try {
                val open = android.app.PendingIntent.getActivity(app, 7, app.packageManager.getLaunchIntentForPackage(app.packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                val n = android.app.Notification.Builder(app, NexusNotifications.TEST_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_nexus)
                    .setContentTitle("NEXUS test notification")
                    .setContentText("This is how your notifications look on the NEXUS lock screen. Tap it to open NEXUS.")
                    .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build()
                app.getSystemService(android.app.NotificationManager::class.java)?.notify(4242, n)
            } catch (_: Exception) {}
        }, 5000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFY) return
        if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) sendTestNotification()
        else NexusUi.note(this, "NEXUS needs permission to send notifications for the test")
    }

    /** Says what to do on Android's Notification access page before going there (it can't be explained once you're there). */
    private fun askNotificationAccess(back: () -> Unit = { lockSettings() }) {
        NexusUi.panel(this, "Notification access",
            "With access, NEXUS's status bar shows which apps have notifications (their icons only, like the phone's own " +
                "status bar), and the NEXUS lock screen can show your notifications if you use it. Nothing is stored or sent anywhere.\n\n" +
                "On the next screen, switch on NEXUS notifications, then come back.\n\n" +
                "If Android says \"Restricted setting\", open NEXUS's App info below, tap ⋮ at the top right, choose " +
                "Allow restricted settings, then try again.",
            listOf(NexusUi.Option("Continue", primary = true) { openNotificationAccess() },
                NexusUi.Option("Open App info") { openAppInfo() }),
            cancel = "Back", back = back)
    }

    /** Android's Notification access page for NEXUS (where it can be allowed or taken away). */
    private fun openNotificationAccess() {
        val cn = android.content.ComponentName(this, NexusNotifications::class.java).flattenToString()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30)
                startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn))
            else startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun setNexusWallpaper(lock: Boolean) {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(720); val h = dm.heightPixels.coerceAtLeast(1280)
        Bg.thread {
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
            runOnUiThread { NexusUi.note(this, if (ok) "Wallpaper set" else "Couldn't set the wallpaper") }
        }.start()
    }

    private fun onOff(b: Boolean) = if (b) "ON" else "OFF"

    /** The gear: NEXUS settings, in a few plain sections (each opens its own page; Back returns here). */
    private fun homeSettings() {
        val isHome = HomeMode.isDefault(this)
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        val helper = when {
            Recents.serviceOn(this) && helperRunning() -> "ON"
            Recents.serviceOn(this) -> "STOPPED"
            else -> "OFF"
        }
        val amb = Ambience.enabled(this); val menu = prefs.getBoolean("menuSounds", true)
        val sound = when { amb && menu -> "ON"; amb -> "background only"; menu -> "menu only"; else -> "OFF" }
        NexusUi.panel(this, "Settings", "Version $version", listOfNotNull(
            if (!isHome) NexusUi.Option("Make NEXUS my home screen", primary = true) { openHomeSettings() } else null,
            NexusUi.Option("Home screen:  " + styleName()) { homeScreenSettings() },
            NexusUi.Option("App order:  " + (if (alphabetical()) "A–Z" else "your own")) { toggleAlphabetical(back = { homeSettings() }) },
            NexusUi.Option("Notifications:  " + notifSummary()) { notificationSettings(back = { homeSettings() }) },
            NexusUi.Option("Lock screen:  " + onOff(LockActivity.enabled(this))) { lockSettings() },
            NexusUi.Option("Recent apps helper:  $helper") { openAppsSettings() },
            NexusUi.Option("Buttons in apps:  " + onOff(NexusAccessibility.appButtonsOn(this))) { buttonSettings() },
            NexusUi.Option("Sound:  $sound") { soundSettings() },
            NexusUi.Option("Help") { helpSettings() },
            NexusUi.Option("System: device info & tests") { openSystem() },
            NexusUi.Option("Close NEXUS", danger = true) { askClose() }
        ), cancel = "Done")
    }

    /** Settings → Home screen. */
    private fun homeScreenSettings() {
        val isHome = HomeMode.isDefault(this)
        NexusUi.panel(this, "Home screen", null, listOfNotNull(
            NexusUi.Option(if (isHome) "NEXUS is your home screen  ✓" else "Make NEXUS my home screen", primary = !isHome) { openHomeSettings() },
            NexusUi.Option("Style:  " + styleName()) { chooseHomeStyle() },
            NexusUi.Option("App order:  " + (if (alphabetical()) "A–Z" else "your own")) { toggleAlphabetical() },
            if (homeStyle() != "tiles") null else NexusUi.Option("Grid size:  ${prefs.getString("grid", "4x6")!!.replace("x", " × ")}") { chooseGrid() },
            NexusUi.Option("Notifications:  " + notifSummary()) { notificationSettings(back = { homeScreenSettings() }) },
            NexusUi.Option("Match wallpaper to NEXUS") { askWallpaper() },
            NexusUi.Option("Home screen when NEXUS is closed") { chooseNormalHome() }
        ), cancel = "Back", back = { homeSettings() })
    }

    /** What NEXUS shows of your notifications on the home screen, in a word or two (for the Settings lists). */
    private fun notifSummary(): String {
        if (!NexusNotifications.granted(this)) return "allow access"
        val boxes = NexusNotifications.boxesOn(this); val icons = NexusNotifications.barOn(this)
        return when { boxes && icons -> "boxes & icons"; boxes -> "boxes"; icons -> "icons"; else -> "OFF" }
    }

    /** Settings → Notifications: the boxes at the top of the home screen and the icons in the status bar. */
    private fun notificationSettings(back: () -> Unit) {
        if (!NexusNotifications.granted(this)) { askNotificationAccess(back = back); return }
        val boxes = NexusNotifications.boxesOn(this); val icons = NexusNotifications.barOn(this)
        NexusUi.panel(this, "Notifications",
            "Boxes: your notifications at the top of the home screen, newest first. Swipe up and down on them to scroll " +
                "through, tap one to open it, swipe it sideways to clear it. They scroll away with your apps.\n\n" +
                "Icons: which apps have notifications, in the status bar.\n\n" +
                "Pull-down panel: swiping down from the top brings NEXUS's own panel (all your notifications, their buttons, " +
                "Clear all, and shortcuts), on the home screen and in your apps (it takes the place of the phone's own, which " +
                "may show for a split second first; needs the Recent Apps helper in apps). The phone's own still comes down on " +
                "the lock screen, during calls, with TalkBack, and from the panel's Quick settings button.\n\n" +
                "They only show while your phone is unlocked. Nothing is stored or sent anywhere.",
            listOf(
                NexusUi.Option("Boxes on the home screen:  " + onOff(boxes)) {
                    NexusNotifications.setBoxesOn(this, !boxes); hub.notifBoxes = !boxes; notificationSettings(back)
                },
                NexusUi.Option("Icons in the status bar:  " + onOff(icons)) {
                    NexusNotifications.setBarOn(this, !icons); hub.notifIcons = !icons; notificationSettings(back)
                },
                NexusUi.Option("Pull-down panel:  " + (if (NexusNotifications.panelOn(this)) "NEXUS's, everywhere" else "phone's own")) {
                    NexusNotifications.setPanelOn(this, !NexusNotifications.panelOn(this))
                    NexusAccessibility.instance?.refreshAppButtons()
                    notificationSettings(back)
                }
            ), cancel = "Back", back = back)
    }

    /** Settings → Buttons in apps. */
    private fun buttonSettings() {
        NexusUi.panel(this, "Buttons in apps",
            "NEXUS's Back, Home and Recent buttons can take the place of the phone's own while you use your apps. " +
                "They need the phone's 3-button navigation, and step aside for full-screen videos and games, the lock screen and TalkBack.",
            listOf(
                NexusUi.Option("NEXUS buttons in apps:  " + onOff(NexusAccessibility.appButtonsOn(this))) { toggleAppButtons() },
                NexusUi.Option("Button order:  " + NavBar.name(NavBar.order(this))) {
                    NavBar.setOrder(this, NavBar.next(NavBar.order(this))); applyNavBar()
                    NexusAccessibility.instance?.refreshAppButtons(); buttonSettings()
                }
            ), cancel = "Back", back = { homeSettings() })
    }

    /** Settings → Sound. */
    private fun soundSettings() {
        val amb = Ambience.enabled(this); val menu = prefs.getBoolean("menuSounds", true)
        NexusUi.panel(this, "Sound",
            "Background sound: a deep, soft drone while you're on the home screen. It plays by itself on your media volume " +
                "(silent or vibrate don't stop it), and pauses while music or videos play and during calls and alarms.",
            listOf(
                NexusUi.Option("Background sound:  " + onOff(amb)) {
                    Ambience.setEnabled(this, !amb)
                    if (!amb) ambience.start() else ambience.stop()
                    soundSettings()
                },
                NexusUi.Option("Background volume:  " + Ambience.volumeName(Ambience.volume(this))) {
                    val v = Ambience.volume(this)
                    Ambience.setVolume(this, when { v < 0.45f -> 0.6f; v < 0.8f -> 1f; else -> 0.3f })
                    ambience.refreshVolume()
                    soundSettings()
                },
                NexusUi.Option("Menu sounds:  " + onOff(menu)) {
                    prefs.edit().putBoolean("menuSounds", !menu).apply(); Sfx.on = !menu
                    soundSettings()
                }
            ), cancel = "Back", back = { homeSettings() })
    }

    /** Settings → Help. */
    private fun helpSettings() {
        NexusUi.panel(this, "Help", null, listOf(
            NexusUi.Option("How it works") { howItWorks() },
            NexusUi.Option("Tutorial") { showTutorial() },
            NexusUi.Option("Report a problem") { reportProblem() }
        ), cancel = "Back", back = { homeSettings() })
    }

    private fun howItWorks() {
        NexusUi.panel(this, "How it works",
            "• While NEXUS is open, Home brings you back to NEXUS. Pressing Home on NEXUS goes back to the top of the list.\n" +
                "• Tap an app once to choose it, and again to open it. Hold an app for its shortcuts, App info and Uninstall; hold and drag to move it.\n" +
                "• Tap ALL APPS to see every app. Type to search; Enter opens the top match.\n" +
                "• Tap ADD APP to add apps to NEXUS: tick as many as you like, then tap ADD.\n" +
                "• Swipe down from the top (or tap the top bar) for your notifications: NEXUS's own panel, with each notification's buttons, Clear all and shortcuts. In your apps too, it takes the place of the phone's own (Settings → Notifications can switch it back). With Notification access, the top bar shows which apps have notifications and the newest show as boxes at the top of the home screen: tap to open, swipe sideways to clear (Settings → Notifications).\n" +
                "• Settings → Home screen → Style: the PS2 menu (a list), Data grid (glass blocks with spinning icons) or Tiles. App order: A–Z or your own.\n" +
                "• Recent Apps (the square button, once the helper is on): tap a card to switch to that app, swipe it up to close the app, or tap beside the cards to go home. With no apps open, it takes you to the home screen. Android only lets its own recent apps screen fully close apps, so NEXUS opens that screen behind Recent Apps and presses Close for you, right away (switch this off under Recent apps helper).\n" +
                "• Buttons in apps: NEXUS's Back, Home and Recent buttons in place of the phone's own (needs 3-button navigation).\n" +
                "• Close NEXUS (in Settings) and Home goes to your normal home screen; open NEXUS from its icon to make it your home again.\n\n" +
                "To stop using NEXUS as Home completely, choose your old home screen in the phone's Settings → Apps → Default apps → Home app.",
            emptyList(), cancel = "Back", back = { helpSettings() })
    }

    /** The home screen's style: "ps2" (the PS2 menu list), "grid" (the data grid) or "tiles". */
    private fun homeStyle(): String = prefs.getString("homeStyle", "ps2").let { if (it == "grid" || it == "tiles") it else "ps2" }

    private fun styleName(s: String = homeStyle()) = when (s) { "grid" -> "Data grid"; "tiles" -> "Tiles"; else -> "PS2 menu" }

    /** Settings → Home screen → Style: the PS2 menu list, the data grid, or tiles. */
    private fun chooseHomeStyle() {
        val cur = homeStyle()
        NexusUi.panel(this, "Home screen style",
            "PS2 menu: your apps as a list, one per line.\n" +
                "Data grid: your apps as glass blocks with their icons floating inside. Tap one to choose it (its icon spins), tap it again to open it.\n" +
                "Tiles: a grid of tiles in sizes you pick.",
            listOf("ps2", "grid", "tiles").map { k ->
                NexusUi.Option((if (k == cur) "✓  " else "     ") + styleName(k), primary = k == cur) { setHomeStyle(k) }
            }, cancel = "Back", back = { homeScreenSettings() })
    }

    private fun setHomeStyle(s: String) {
        prefs.edit().putString("homeStyle", s).apply()
        hub.ps2Menu = s == "ps2"; hub.dataGrid = s == "grid"
        hub.styleChanged()
        homeScreenSettings()
    }

    /** NEXUS buttons in all apps: on or off. It needs the helper, and the phone's 3-button navigation. */
    private fun toggleAppButtons() {
        if (NexusAccessibility.appButtonsOn(this)) { NexusAccessibility.setAppButtons(this, false); buttonSettings(); return }
        if (!Recents.serviceOn(this) || !helperRunning()) {
            NexusUi.panel(this, "NEXUS buttons in all apps",
                "This uses NEXUS's helper (the one behind Recent Apps). Turn the helper on first, then switch this on here.",
                listOf(NexusUi.Option("Turn on helper", primary = true) { openAccessibility() }), cancel = "Back", back = { buttonSettings() })
            return
        }
        if (NavBar.order(this) == "off") { NavBar.setOrder(this, "brh"); applyNavBar() }
        NexusAccessibility.setAppButtons(this, true)
        buttonSettings()
        NexusUi.note(this,
            if (NexusAccessibility.threeButtonNav(this)) "NEXUS's buttons now take the place of the phone's in your apps"
            else "Your phone uses gesture navigation: NEXUS's buttons appear in your apps once it's set to 3-button navigation (in the phone's Settings, search \"navigation\")",
            long = true)
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
        // Back in front: the home screen only rests if something really covers it (never left paused by mistake)
        if (hasFocus && ::hub.isInitialized && hub.covered && !hub.stillCovered()) {
            hub.covered = false
            LockTrace.log(this, "home screen was marked covered with nothing over it: carried on")
        }
        // (The notification shade came down over NEXUS, or went away: its status row comes back once the phone's is gone)
        if (::hub.isInitialized) hub.focusChanged(hasFocus)
    }

    override fun onStop() {
        super.onStop()
        // Left for an app (e.g. a notification was opened): NEXUS's status row and buttons are simply there next time
        if (::hub.isInitialized) hub.phoneBarsGone()
        // (And its notification panel is put away)
        if (::notifPanel.isInitialized) notifPanel.closeNow()
    }

    /** A still picture of this screen as it stands (null if it wouldn't match what comes back: All Apps open etc). */
    private fun stillPicture(): android.graphics.Bitmap? {
        if (isDestroyed || !::hub.isInitialized || hub.booting || drawer.isOpen || tutorial.visibility == android.view.View.VISIBLE) return null
        val b = hub.still() ?: return null
        if (navBar.visibility == android.view.View.VISIBLE && navBar.height > 0)
            NavBar.drawPicture(android.graphics.Canvas(b), b.width.toFloat(), b.height.toFloat(), NavBar.order(this),
                resources.displayMetrics.density, navBar.height.toFloat())
        return b
    }

    override fun onDestroy() {
        if (home?.get() === this) home = null
        try { getSystemService(android.content.pm.LauncherApps::class.java)?.unregisterCallback(appsChanged) } catch (_: Exception) {}
        window.decorView.removeCallbacks(reloadApps)
        if (NexusUi.onPanels != null && home?.get() == null) NexusUi.onPanels = null
        ambience.release()
        super.onDestroy()
        sfx.release()
    }

    /** An app was installed, updated or removed: refresh the tiles, All Apps and Recent Apps' names and icons. */
    private val reloadApps = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        drawer.load()
        addCountdownTileOnce()
        if (::hub.isInitialized) refresh()
        loadCountdown()
    }

    /** Reads your countdown from the Countdown app (in the background), for its live tile. */
    private fun loadCountdown() {
        Bg.thread {
            val info = CountdownLink.read(applicationContext)
            runOnUiThread { if (::hub.isInitialized) hub.countdown = info }
        }.start()
    }

    /** The first time NEXUS finds the Countdown app, it gets a tile (just once: remove it like any other tile). */
    private fun addCountdownTileOnce() {
        if (prefs.getBoolean("countdownTileAdded", false)) return
        if (packageManager.getLaunchIntentForPackage(CountdownLink.PKG) == null) return
        val custom = customPkgs()
        val edit = prefs.edit().putBoolean("countdownTileAdded", true)
        if (CountdownLink.PKG !in custom) edit.putString("custom", (custom + CountdownLink.PKG).joinToString(","))
        edit.apply()
    }
    private val appsChanged = object : android.content.pm.LauncherApps.Callback() {
        private fun changed(pkgs: Array<out String>?) {
            pkgs?.forEach { AppCache.forget(it); forgetApp(it) }
            window.decorView.removeCallbacks(reloadApps)
            window.decorView.postDelayed(reloadApps, 400)
        }
        override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = changed(arrayOf(packageName))
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) {
            // Really uninstalled (Android reports updates separately): forget it in Recent Apps too
            if (user == android.os.Process.myUserHandle()) {
                Recents.remove(this@MainActivity, packageName)
                // and its saved picture goes too
                try { NexusAccessibility.snapFile(this@MainActivity, packageName).delete() } catch (_: Exception) {}
            }
            changed(arrayOf(packageName))
        }
        override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = changed(arrayOf(packageName))
        override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = changed(packageNames)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = changed(packageNames)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isHomeIntent(intent) && intent?.getBooleanExtra("fromApp", false) != true) {
            // Home pressed: NEXUS mode on = stay in / come back to NEXUS; off = normal home screen
            if (!HomeMode.isOn(this) && HomeMode.goNormalHome(this)) return
            leftForApp = android.os.SystemClock.uptimeMillis() - pausedAt > 700
            // The list glides back to the top only when Home is pressed while you're looking at the home screen
            // itself. Coming home after closing apps, or from Recent Apps (NEXUS's or the phone's), it stays where
            // you left it. (Asked before Recent Apps is put away just below.)
            val onHome = !leftForApp && pausedAt - resumedAt > 300 && !NexusAccessibility.homeCoveredRecently()
            // Home pressed from an app: apps swiped away in Recent Apps earlier get closed fully now
            NexusAccessibility.hideRecents()
            if (leftForApp) NexusAccessibility.closeWhenHome()
            goHome(onScreen = onHome)
            // Back from an app with the phone's own Home button: the home screen shows at once on NEXUS's cover
            // while the phone's slide back plays underneath (already done if NEXUS's own Home button was used)
            // (A cross-fade into a picture of this screen when there is one, rather than a dip into the dark)
            if (leftForApp && !NexusAccessibility.busyClosing && !NexusAccessibility.veiling)
                NexusAccessibility.instance?.let { it.fadeFor(NexusAccessibility.FADE_HOME, it.homePicture(), 60L) }
            return
        }
        // Opened from its icon or back from one of the apps
        HomeMode.setOn(this, true)
        leftForApp = true // brought back from one of the apps
    }

    /** Android has finished animating this screen into place (e.g. after Home): NEXUS's picture of it can make way. */
    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        NexusAccessibility.instance?.homeSettled()
    }

    /** Android is short of memory while NEXUS is in the background: let go of the tile pictures (read again on return). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && ::hub.isInitialized) hub.releasePictures()
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        // Notification access just allowed (or taken away): the helper starts (or stops) watching for the phone's shade
        NexusNotifications.granted(this).let { g -> if (g != accessSeen) { accessSeen = g; NexusAccessibility.instance?.refreshAppButtons() } }
        resumedAt = android.os.SystemClock.uptimeMillis()
        // (No fade-in while NEXUS closes apps under its cover: the cover is already showing this screen)
        if (::hub.isInitialized) { refresh(); hub.resumed(leftForApp && !NexusAccessibility.busyClosing && !NexusAccessibility.veiling && !NexusAccessibility.recentsShowing) }
        loadCountdown()
        leftForApp = false
        hideBars()
        // The helper may have measured the phone's own button bar since: match it
        if (::navBar.isInitialized && navBar.visibility == android.view.View.VISIBLE && navBar.layoutParams.height != NavBar.heightPx(this)) applyNavBar()
        ambience.start()
        // If Android has stopped the helper, the NEXUS lock screen and Recent Apps can't work: say so, once
        window.decorView.removeCallbacks(checkHelper)
        window.decorView.postDelayed(checkHelper, 2500)
    }

    /** Is the Recent Apps helper actually running (not just switched on)? */
    private fun helperRunning(): Boolean {
        if (NexusAccessibility.running) return true
        return try {
            val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { it.resolveInfo?.serviceInfo?.packageName == packageName } == true
        } catch (_: Exception) { false }
    }

    private val checkHelper = Runnable {
        if (helperWarned || isFinishing || !hasWindowFocus()) return@Runnable
        val needed = LockActivity.enabled(this) || prefs.getBoolean("replaceRecents", true)
        if (needed && Recents.serviceOn(this) && !helperRunning()) {
            helperWarned = true
            NexusUi.panel(this, "The Recent Apps helper stopped",
                "Android has stopped NEXUS's helper, so the NEXUS lock screen and Recent Apps can't show until it's back on.\n\n" +
                    "Tap Fix it, choose NEXUS Recent Apps, switch it off and back on, and tap Allow.",
                listOf(NexusUi.Option("Fix it", primary = true) { openAccessibility() }), cancel = "Not now")
        } else if (!prefs.getBoolean("appButtonsOffered", false) && !NexusAccessibility.appButtonsOn(this) && helperRunning() &&
            HomeMode.isDefault(this) && NexusAccessibility.threeButtonNav(this) && NavBar.order(this) != "off" &&
            !hub.booting && tutorial.visibility != android.view.View.VISIBLE) {
            // New: offered once (it's up to you; it can be changed any time in NEXUS settings)
            prefs.edit().putBoolean("appButtonsOffered", true).apply()
            NexusUi.panel(this, "NEXUS buttons in your apps",
                "NEXUS's Back, Home and Recent buttons can take the place of the phone's own while you use any app, so they look the same everywhere.\n\n" +
                    "They step aside for full-screen videos and games, the lock screen and TalkBack. You can change this any time in NEXUS settings.",
                listOf(NexusUi.Option("Turn on", primary = true) {
                    NexusAccessibility.setAppButtons(this, true)
                    NexusUi.note(this, "NEXUS's buttons now take the place of the phone's in your apps")
                }), cancel = "Not now")
        }
    }

    companion object {
        private var home: java.lang.ref.WeakReference<MainActivity>? = null

        /**
         * Back to the plain home screen while NEXUS itself is on screen (Home in Recent Apps opened over NEXUS, or
         * Recent with no apps open): panels, All apps and Phone settings close, as the Home button does.
         */
        /**
         * The phone's own notification shade came down over the NEXUS home screen: NEXUS's panel comes down instead
         * (it may already be coming down with your finger). False if it can't here (then NEXUS's helper shows it).
         */
        fun openPanel(): Boolean {
            val m = home?.get() ?: return false
            // (In front: the phone's shade coming down over it doesn't pause it, only takes the focus)
            if (m.isDestroyed || !m.inFront) return false
            if (!m.panelAvailable()) return false
            if (!m.notifPanel.isOpen) m.notifPanel.open()
            return true
        }

        /** NEXUS's panel is down on the home screen right now. */
        fun panelUp(): Boolean = home?.get()?.let { it.inFront && it.notifPanelIsOpen() } ?: false

        fun showHome() {
            SystemActivity.closeIfOpen()
            val m = home?.get() ?: return
            if (!m.isDestroyed) m.goHome(onScreen = false)
        }

        /** NEXUS Recent Apps, from a NEXUS screen (the Recent button). */
        fun openRecents(a: Activity) {
            // In NEXUS's own layer: at once, nothing for Android to open
            if (NexusAccessibility.showRecents()) return
            // No apps open (and the helper is on, so the list is up to date): the home screen, which says so
            if (Recents.serviceOn(a) && !SwitcherView.anyOpen(a)) {
                showHome()
                HubView.noteNothingOpen()
                @Suppress("DEPRECATION") a.window.decorView.announceForAccessibility("No open apps")
                return
            }
            NexusAccessibility.instance?.fadeFor(NexusAccessibility.FADE_RECENTS, null, 90L)
            // (Under NEXUS's fade, with no slide of the phone's own: the cards glide in as the fade lifts)
            a.startActivity(Intent(a, SwitcherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION), Transitions.app(a.window.decorView, solidSplash = true))
        }
        /** A still picture of the NEXUS home screen as it stands (null if not available), for the cover. */
        fun homeStill(): android.graphics.Bitmap? = try { home?.get()?.stillPicture() } catch (_: Throwable) { null }

        /** The "helper stopped" note is shown at most once each time NEXUS starts. */
        @Volatile private var helperWarned = false
        private const val REQ_HOME = 41
        private const val REQ_NOTIFY = 42
    }

    private fun open(g: GameEntry) {
        if (packageManager.getLaunchIntentForPackage(g.pkg) != null) {
            // Cross-fade into the app
            // The app opens out of the tile that was tapped
            val opts = if (!hub.launchRect.isEmpty) Transitions.grow(hub, hub.launchRect) else Transitions.app(hub)
            hub.launchRect.setEmpty()
            // NEXUS's own quick fade covers the opening (instead of the phone's slide-in)
            val veiled = NexusAccessibility.instance?.veilForLaunch(g.pkg, null, 90L) == true
            if (AppActions.open(this, g.pkg, null, opts)) leftForApp = true
            else if (veiled) NexusAccessibility.instance?.cancelVeil()
            return
        }
        // Not installed yet: open its download page
        val tag = when (g.pkg) { "com.zombiedots.app" -> "zombie-build"; "com.ashenvow.app" -> "ashen-build"; "com.torquestreet.app" -> "racer-build"; else -> "synth-build" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/buickdriver2001-gif/countdown-app/releases?q=$tag&expanded=true")))
        } catch (_: Exception) {
            NexusUi.note(this, "Couldn't open the download page")
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
