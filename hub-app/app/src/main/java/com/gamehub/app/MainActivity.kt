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
        val input = android.widget.EditText(this).apply {
            setText(g.tagline)
            setSelection(text.length)
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Description for ${g.name}")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("desc_${g.pkg}", input.text.toString().trim()).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Hold an added app: change its description or take it off NEXUS. */
    private fun appOptions(g: GameEntry) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(g.name)
            .setItems(arrayOf("Edit description", "Remove from NEXUS")) { _, which ->
                if (which == 0) editDescription(g) else removeGame(g)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refresh() {
        val list = buildList()
        for (g in list) g.installed = packageManager.getLaunchIntentForPackage(g.pkg) != null
        hub.games = list
    }

    /** Shows the apps on the phone (games first) so the player can add one to the hub. */
    private fun pickGame() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val have = hub.games.map { it.pkg }.toSet() + packageName
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName !in have }
            .map { info ->
                val isGame = android.os.Build.VERSION.SDK_INT >= 26 && info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME
                Triple(info.packageName, packageManager.getApplicationLabel(info).toString(), isGame)
            }
            .sortedWith(compareBy({ !it.third }, { it.second.lowercase() }))
        if (apps.isEmpty()) { Toast.makeText(this, "No other apps found", Toast.LENGTH_SHORT).show(); return }
        val labels = apps.map { (if (it.third) "🎮  " else "      ") + it.second }.toTypedArray()
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Add to NEXUS")
            .setItems(labels) { _, which ->
                val pkg = apps[which].first
                prefs.edit().putString("custom", (customPkgs() + pkg).joinToString(",")).apply()
                refresh()
                Toast.makeText(this, "${apps[which].second} added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeGame(g: GameEntry) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Remove ${g.name} from the hub?")
            .setMessage("This only removes it from NEXUS. The app stays on your phone.")
            .setPositiveButton("Remove") { _, _ ->
                prefs.edit().putString("custom", customPkgs().filter { it != g.pkg }.joinToString(",")).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.crashFile(this).exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        hub = HubView(this, builtIn, { open(it) }, { pickGame() }, { appOptions(it) }, sfx)
        setContentView(hub)
        // Full screen: no status bar, no navigation buttons (swipe from an edge to peek at them)
        if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.decorView.post { hideBars() }
        val fromApp = intent?.getBooleanExtra("fromApp", false) == true
        if (fromApp) { hub.booting = false; leftForApp = true }
        else if (savedInstanceState == null) Thread { playChime() }.start()
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
