package com.gamehub.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** NEXUS System: live diagnostics, device information, hardware tests and settings shortcuts. */
class SystemActivity : Activity() {

    companion object {
        private var live: java.lang.ref.WeakReference<SystemActivity>? = null
        /** Home pressed while this screen is open (e.g. in Recent Apps over it): it closes, as other screens do. */
        fun closeIfOpen() {
            val s = live?.get() ?: return
            if (!s.isFinishing && !s.isDestroyed) { s.finish(); @Suppress("DEPRECATION") s.overridePendingTransition(0, R.anim.nexus_exit) }
        }
    }

    private val d by lazy { resources.displayMetrics.density }
    private val h = Handler(Looper.getMainLooper())
    private val values = HashMap<String, TextView>()
    private lateinit var gauges: GaugeView
    private lateinit var graph: GraphView
    private lateinit var cores: CoresView
    private lateinit var root: FrameLayout
    private val sfx by lazy { Sfx() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(3, 6, 18), Color.rgb(8, 16, 42), Color.rgb(2, 4, 12)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((16 * d).toInt(), (44 * d).toInt(), (16 * d).toInt(), (40 * d).toInt()) }
        // Header
        col.addView(TextView(this).apply { text = "SYSTEM"; setTextColor(NexusUi.TEXT); textSize = 24f; letterSpacing = 0.45f; typeface = NexusUi.light; gravity = Gravity.CENTER })
        col.addView(TextView(this).apply {
            text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE}"
            setTextColor(NexusUi.MUTED); textSize = 12f; letterSpacing = 0.08f; typeface = NexusUi.light; gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (14 * d).toInt())
        })
        gauges = GaugeView(this)
        col.addView(card("OVERVIEW", gauges, (130 * d).toInt()))
        graph = GraphView(this)
        col.addView(card("LAST FEW MINUTES", graph, (120 * d).toInt()))

        col.addView(section("BATTERY", listOf("Level", "Status", "Temperature", "Voltage", "Current", "Health", "Type")))
        col.addView(section("MEMORY & STORAGE", listOf("Memory used", "Memory free", "Storage used", "Storage free")))
        cores = CoresView(this)
        col.addView(section("PROCESSOR", listOf("Chip", "Cores", "Architecture")).also { (it as LinearLayout).addView(cores, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (70 * d).toInt())) })
        col.addView(section("DISPLAY", listOf("Resolution", "Density", "Refresh rate")))
        col.addView(section("NETWORK", listOf("Connection", "Download speed (est.)", "Upload speed (est.)", "Metered")))
        col.addView(section("DEVICE", listOf("Model", "Android", "Security patch", "Build", "Uptime", "Phone temperature", "NEXUS version")))
        col.addView(tools())
        col.addView(shortcuts())

        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) })
        // NEXUS's Back / Home / Recent buttons along the bottom, as on the home screen (hidden while a full-screen
        // test is open, so they don't get in its way)
        val order = NavBar.order(this)
        if (order != "off") {
            val outer = FrameLayout(this)
            outer.addView(root)
            val nav = NavBar(this,
                onBack = { sfx.tick(); @Suppress("DEPRECATION") onBackPressed() },
                onHome = { sfx.tick(); MainActivity.showHome() },
                onRecent = { sfx.tick(); MainActivity.openRecents(this) })
            outer.addView(nav, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, NavBar.heightPx(this), Gravity.BOTTOM))
            col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, col.paddingBottom + NavBar.heightPx(this))
            val sync = Runnable { nav.visibility = if (root.childCount > 1) View.GONE else View.VISIBLE }
            root.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { root.post(sync) }
                override fun onChildViewRemoved(parent: View?, child: View?) { root.post(sync) }
            })
            setContentView(outer)
        } else setContentView(root)
        live = java.lang.ref.WeakReference(this)
        if (Build.VERSION.SDK_INT >= 30) window.decorView.post {
            try {
                window.insetsController?.hide(android.view.WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } catch (_: Exception) {}
        }
        fillStatic()
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick); if (torchOn) setTorch(false) }
    override fun onDestroy() { super.onDestroy(); sfx.release(); if (live?.get() === this) live = null }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Close a full-screen test first
        if (root.childCount > 1) { root.removeViewAt(root.childCount - 1); return }
        @Suppress("DEPRECATION") super.onBackPressed()
        @Suppress("DEPRECATION") overridePendingTransition(0, R.anim.nexus_exit)
    }

    // ------------------------------------------------------------------ layout helpers

    private fun card(title: String, content: View, height: Int): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = NexusUi.glass(d, 10f)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (12 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() }
        }
        c.addView(label(title))
        c.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        return c
    }

    private fun label(t: String) = TextView(this).apply {
        text = t; setTextColor(NexusUi.BLUE); textSize = 11f; letterSpacing = 0.3f; typeface = NexusUi.light
        setPadding(0, 0, 0, (8 * d).toInt())
    }

    private fun section(title: String, keys: List<String>): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = NexusUi.glass(d, 10f)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() }
        }
        c.addView(label(title))
        for (k in keys) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (5 * d).toInt(), 0, (5 * d).toInt()) }
            row.addView(TextView(this).apply { text = k; setTextColor(NexusUi.MUTED); textSize = 13.5f; typeface = NexusUi.light },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(this).apply { text = "…"; setTextColor(NexusUi.TEXT); textSize = 13.5f; typeface = NexusUi.light; gravity = Gravity.END }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f))
            values[k] = v
            c.addView(row)
        }
        return c
    }

    private fun button(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(NexusUi.TEXT); textSize = 13.5f; typeface = NexusUi.light; letterSpacing = 0.05f
        gravity = Gravity.CENTER; minHeight = (46 * d).toInt(); background = NexusUi.glass(d, 8f, strong = true)
        setOnClickListener { sfx.tick(); onClick() }
    }

    /** Buttons in rows of two. */
    private fun grid(title: String, items: List<Pair<String, () -> Unit>>): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = NexusUi.glass(d, 10f)
            setPadding((14 * d).toInt(), (12 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (12 * d).toInt() }
        }
        c.addView(label(title))
        var i = 0
        while (i < items.size) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in 0..1) {
                val it = items.getOrNull(i + k)
                val v: View = if (it != null) button(it.first, it.second) else View(this)
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (k == 0) rightMargin = (5 * d).toInt() else leftMargin = (5 * d).toInt(); topMargin = (8 * d).toInt()
                })
            }
            c.addView(row)
            i += 2
        }
        return c
    }

    private var torchBtn: TextView? = null

    private fun tools(): View {
        val v = grid("TOOLS", listOf(
            "Flashlight: OFF" to { setTorch(!torchOn) },
            "Screen test" to { screenTest() },
            "Touch test" to { touchTest() },
            "Vibration test" to { vibrationTest() },
            "Speaker test" to { speakerTest() }
        ))
        // Keep a handle on the flashlight button so its label can change
        torchBtn = findFirstButton(v, "Flashlight: OFF")
        return v
    }

    private fun findFirstButton(v: View, t: String): TextView? {
        if (v is TextView && v.text == t) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findFirstButton(v.getChildAt(i), t)?.let { return it }
        return null
    }

    private fun shortcuts(): View {
        fun go(action: String) = { try { startActivity(Intent(action)) } catch (_: Exception) { NexusUi.note(this, "Not available on this phone") } }
        return grid("SETTINGS", listOf(
            "Wi-Fi" to go(android.provider.Settings.ACTION_WIFI_SETTINGS),
            "Bluetooth" to go(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS),
            "Display" to go(android.provider.Settings.ACTION_DISPLAY_SETTINGS),
            "Battery" to go(Intent.ACTION_POWER_USAGE_SUMMARY),
            "Storage" to go(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            "Apps" to go(android.provider.Settings.ACTION_APPLICATION_SETTINGS),
            "About phone" to go(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS),
            "Developer options" to go(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        ))
    }

    // ------------------------------------------------------------------ readings

    private fun set(k: String, v: String) { values[k]?.text = v }

    private fun gb(bytes: Long) = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "%.0f MB".format(bytes / (1024.0 * 1024))

    private fun fillStatic() {
        set("Model", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
        set("Android", "${Build.VERSION.RELEASE}  (API ${Build.VERSION.SDK_INT})")
        set("Security patch", Build.VERSION.SECURITY_PATCH.ifBlank { "Unknown" })
        set("Build", Build.DISPLAY)
        set("NEXUS version", try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" })
        set("Chip", if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE)
        set("Cores", "${Runtime.getRuntime().availableProcessors()}")
        set("Architecture", Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown")
        val dm = resources.displayMetrics
        val real = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(real)
        set("Resolution", "${real.widthPixels} × ${real.heightPixels}")
        set("Density", "${dm.densityDpi} dpi")
        @Suppress("DEPRECATION") set("Refresh rate", "%.0f Hz".format(windowManager.defaultDisplay.refreshRate))
    }

    private val tick = object : Runnable {
        override fun run() {
            readLive()
            h.postDelayed(this, 1000)
        }
    }

    private var batteryPct = 0f; private var memPct = 0f; private var storePct = 0f; private var tempC = 0f

    private fun readLive() {
        // Battery
        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val st = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = st?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = st?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val temp = (st?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val volt = (st?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
            val health = st?.getIntExtra(BatteryManager.EXTRA_HEALTH, 0) ?: 0
            val tech = st?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"
            val currentUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            batteryPct = level / 100f; tempC = temp
            val how = when (plugged) { BatteryManager.BATTERY_PLUGGED_AC -> "wall charger"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; else -> "" }
            set("Level", "$level%")
            set("Status", when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging ($how)"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> if (plugged != 0) "Plugged in, not charging" else "On battery"
                else -> "Unknown"
            })
            set("Temperature", "%.1f °C  (%.0f °F)".format(temp, temp * 9 / 5 + 32))
            set("Voltage", "%.2f V".format(volt))
            set("Current", if (currentUa != Int.MIN_VALUE && currentUa != 0) "%.0f mA".format(kotlin.math.abs(currentUa) / (if (kotlin.math.abs(currentUa) > 20000) 1000f else 1f)) else "—")
            set("Health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> "Unknown"
            })
            set("Type", tech)
        } catch (_: Exception) {}
        // Memory
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val used = mi.totalMem - mi.availMem
            memPct = used.toFloat() / mi.totalMem
            set("Memory used", "${gb(used)} of ${gb(mi.totalMem)}")
            set("Memory free", gb(mi.availMem) + if (mi.lowMemory) "  (low)" else "")
        } catch (_: Exception) {}
        // Storage
        try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.totalBytes; val free = sf.availableBytes
            storePct = (total - free).toFloat() / total
            set("Storage used", "${gb(total - free)} of ${gb(total)}")
            set("Storage free", gb(free))
        } catch (_: Exception) {}
        // Network
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            if (caps == null) { set("Connection", "Offline"); set("Download speed (est.)", "—"); set("Upload speed (est.)", "—"); set("Metered", "—") }
            else {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                    else -> "Connected"
                } + if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " + VPN" else ""
                fun mbps(k: Int) = if (k <= 0) "—" else if (k >= 1000) "%.0f Mbps".format(k / 1000f) else "$k kbps"
                set("Connection", type)
                set("Download speed (est.)", mbps(caps.linkDownstreamBandwidthKbps))
                set("Upload speed (est.)", mbps(caps.linkUpstreamBandwidthKbps))
                set("Metered", if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "No" else "Yes")
            }
        } catch (_: Exception) {}
        // Device
        val up = SystemClock.elapsedRealtime() / 1000
        set("Uptime", "${up / 86400}d ${(up % 86400) / 3600}h ${(up % 3600) / 60}m")
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = getSystemService(PowerManager::class.java)
            set("Phone temperature", when (pm?.currentThermalStatus ?: 0) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"; PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
                PowerManager.THERMAL_STATUS_MODERATE -> "Hot"; PowerManager.THERMAL_STATUS_SEVERE -> "Very hot"
                else -> "Critical"
            })
        } else set("Phone temperature", "—")
        // Processor speeds
        val n = Runtime.getRuntime().availableProcessors()
        val cur = FloatArray(n); val mx = FloatArray(n)
        for (i in 0 until n) {
            cur[i] = readNum("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") / 1000f
            mx[i] = readNum("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") / 1000f
        }
        cores.update(cur, mx)
        gauges.update(batteryPct, memPct, storePct, tempC)
        graph.add(batteryPct, memPct)
    }

    private fun readNum(path: String): Float = try { File(path).readText().trim().toFloat() } catch (_: Exception) { 0f }

    // ------------------------------------------------------------------ tools

    private var torchOn = false
    private var torchId: String? = null

    private fun setTorch(on: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (torchId == null) torchId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            val id = torchId ?: run { NexusUi.note(this, "No flashlight on this phone"); return }
            cm.setTorchMode(id, on)
            torchOn = on
            torchBtn?.text = if (on) "Flashlight: ON" else "Flashlight: OFF"
        } catch (_: Exception) { NexusUi.note(this, "Flashlight is busy") }
    }

    /** Solid colours, tap to change: helps spot dead or stuck pixels. Back to exit. */
    private fun screenTest() {
        val colors = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK, Color.GRAY)
        var i = 0
        val v = View(this).apply {
            setBackgroundColor(colors[0])
            setOnClickListener { i++; if (i >= colors.size) root.removeView(this) else setBackgroundColor(colors[i]) }
        }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        NexusUi.note(this, "Tap to change colour")
    }

    /** Draw with your fingers to check every part of the screen responds. Back to exit. */
    private fun touchTest() {
        root.addView(TouchTestView(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        NexusUi.note(this, "Draw anywhere. Press Back when done.")
    }

    private fun vibrationTest() {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 120, 120, 120, 400), intArrayOf(0, 255, 0, 160, 0, 255), -1))
        } catch (_: Exception) {}
    }

    /** A short rising tone, left to right. */
    private fun speakerTest() {
        Thread {
            try {
                val rate = 22050; val secs = 1.6f; val n = (rate * secs).toInt()
                val buf = ShortArray(n * 2)
                for (i in 0 until n) {
                    val t = i.toFloat() / rate
                    val f = 330f + 330f * (t / secs)
                    val env = min(1f, t / 0.05f) * min(1f, (secs - t) / 0.1f)
                    val v = (sin(2 * PI * f * t).toFloat() * env * 0.35f * Short.MAX_VALUE).toInt().toShort()
                    val pan = t / secs
                    buf[i * 2] = (v * (1f - pan)).toInt().toShort(); buf[i * 2 + 1] = (v * pan).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(buf.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                track.write(buf, 0, buf.size); track.play()
                Thread.sleep((secs * 1000).toLong() + 200); track.release()
            } catch (_: Exception) {}
        }.start()
    }

}

/** Four ring gauges: battery, memory, storage, temperature. */
class GaugeView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private var v = floatArrayOf(0f, 0f, 0f, 0f)
    private var temp = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val r = RectF()

    fun update(bat: Float, mem: Float, store: Float, tempC: Float) {
        v = floatArrayOf(bat, mem, store, (tempC / 50f).coerceIn(0f, 1f)); temp = tempC; invalidate()
    }

    override fun onDraw(c: Canvas) {
        val names = arrayOf("BATTERY", "MEMORY", "STORAGE", "TEMP")
        val w = width / 4f
        val rad = min(w * 0.36f, height * 0.32f)
        for (i in 0 until 4) {
            val cx = w * i + w / 2; val cy = height * 0.42f
            r.set(cx - rad, cy - rad, cx + rad, cy + rad)
            ring.strokeWidth = 5 * d; ring.color = Color.argb(50, 140, 190, 255)
            c.drawArc(r, 135f, 270f, false, ring)
            val warn = (i == 0 && v[0] < 0.15f) || (i in 1..2 && v[i] > 0.9f) || (i == 3 && temp > 42f)
            ring.color = if (warn) Color.rgb(255, 130, 110) else Color.rgb(120, 180, 255)
            c.drawArc(r, 135f, 270f * v[i].coerceIn(0f, 1f), false, ring)
            text.textSize = 15 * d; text.color = Color.rgb(235, 242, 255)
            c.drawText(if (i == 3) "%.0f°".format(temp) else "${(v[i] * 100).toInt()}%", cx, cy + 5 * d, text)
            text.textSize = 9.5f * d; text.color = Color.rgb(140, 190, 255); text.letterSpacing = 0.2f
            c.drawText(names[i], cx, height * 0.95f, text)
            text.letterSpacing = 0f
        }
    }
}

/** Battery and memory over the last few minutes. */
class GraphView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val bat = ArrayList<Float>(); private val mem = ArrayList<Float>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val path = Path()

    fun add(b: Float, m: Float) {
        bat.add(b); mem.add(m)
        if (bat.size > 180) { bat.removeAt(0); mem.removeAt(0) }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val top = 18 * d; val bottom = height - 4f; val hh = bottom - top
        line.strokeWidth = 1f; line.color = Color.argb(30, 140, 190, 255)
        for (k in 0..4) { val y = top + hh * k / 4f; c.drawLine(0f, y, width.toFloat(), y, line) }
        line.strokeWidth = 2f * d
        for ((series, col) in listOf(bat to Color.rgb(110, 230, 150), mem to Color.rgb(120, 180, 255))) {
            if (series.size < 2) continue
            path.reset()
            for ((i, v) in series.withIndex()) {
                val x = width * i / 179f; val y = bottom - hh * v
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.color = col; c.drawPath(path, line)
        }
        text.textSize = 10 * d
        text.color = Color.rgb(110, 230, 150); c.drawText("● Battery", 0f, 11 * d, text)
        text.color = Color.rgb(120, 180, 255); c.drawText("● Memory", 80 * d, 11 * d, text)
    }
}

/** One bar per processor core showing its current speed. */
class CoresView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private var cur = FloatArray(0); private var mx = FloatArray(0)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val r = RectF()

    fun update(c: FloatArray, m: FloatArray) { cur = c; mx = m; invalidate() }

    override fun onDraw(c: Canvas) {
        val n = cur.size
        if (n == 0) return
        if (cur.all { it <= 0f }) {
            text.textSize = 11 * d; text.color = Color.rgb(160, 185, 230)
            c.drawText("Core speeds aren't shared on this phone", width / 2f, height / 2f, text); return
        }
        val bw = width / n.toFloat()
        for (i in 0 until n) {
            val frac = if (mx[i] > 0f) (cur[i] / mx[i]).coerceIn(0f, 1f) else 0f
            val x = i * bw + bw * 0.2f
            r.set(x, 6 * d, x + bw * 0.6f, height - 18 * d)
            fill.color = Color.argb(40, 140, 190, 255); c.drawRoundRect(r, 3 * d, 3 * d, fill)
            r.top = r.bottom - (r.bottom - 6 * d) * frac
            fill.color = Color.rgb(120, 180, 255); c.drawRoundRect(r, 3 * d, 3 * d, fill)
            text.textSize = 9 * d; text.color = Color.rgb(170, 200, 240)
            c.drawText(if (cur[i] > 0f) "${(cur[i]).toInt()}" else "off", i * bw + bw / 2, height - 4 * d, text)
        }
    }
}

/** Touch test: each finger leaves a glowing trail; shows how many fingers are down. */
@SuppressLint("ViewConstructor")
class TouchTestView(c: Context) : View(c) {
    private val d = resources.displayMetrics.density
    private val trails = HashMap<Int, Path>()
    private val done = ArrayList<Path>()
    private var fingers = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * resources.displayMetrics.density; strokeCap = Paint.Cap.ROUND; color = Color.rgb(120, 190, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.rgb(200, 220, 255) }

    init { setBackgroundColor(Color.rgb(4, 8, 20)) }

    override fun onDraw(c: Canvas) {
        p.alpha = 150; for (pt in done) c.drawPath(pt, p)
        p.alpha = 255; for (pt in trails.values) c.drawPath(pt, p)
        text.textSize = 16 * d
        c.drawText(if (fingers > 0) "$fingers finger${if (fingers > 1) "s" else ""}" else "Draw anywhere", width / 2f, 60 * d, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                trails[e.getPointerId(i)] = Path().apply { moveTo(e.getX(i), e.getY(i)) }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) trails[e.getPointerId(i)]?.lineTo(e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = e.getPointerId(e.actionIndex)
                trails.remove(id)?.let { done.add(it) }
                if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) { trails.values.forEach { done.add(it) }; trails.clear() }
            }
        }
        fingers = if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) 0
            else e.pointerCount - (if (e.actionMasked == MotionEvent.ACTION_POINTER_UP) 1 else 0)
        invalidate()
        return true
    }
}
