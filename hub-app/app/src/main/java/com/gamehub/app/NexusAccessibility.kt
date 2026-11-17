package com.gamehub.app

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.io.FileOutputStream

/**
 * NEXUS's helper service (turned on by the user in Accessibility settings). It:
 *  - keeps a small screenshot of the last screen of each app you use (stored only inside NEXUS)
 *  - notices when the phone's recent-apps screen opens (the square button) and shows NEXUS Open Apps instead
 */
class NexusAccessibility : AccessibilityService() {

    private val h = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var lastSwitcher = 0L
    private val homePkgs = HashSet<String>()

    companion object {
        @Volatile var running = false
        fun snapDir(c: Context) = File(c.filesDir, "snaps").apply { mkdirs() }
        fun snapFile(c: Context, pkg: String) = File(snapDir(c), "$pkg.jpg")
    }

    override fun onServiceConnected() {
        running = true
        refreshHomes()
        h.postDelayed(tick, 2000)
    }

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    private fun refreshHomes() {
        homePkgs.clear()
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (r in packageManager.queryIntentActivities(i, 0)) homePkgs.add(r.activityInfo.packageName)
    }

    /** A "real" app worth remembering (not the keyboard, system bars, home screens or NEXUS). */
    private fun isRealApp(pkg: String): Boolean {
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") return false
        if (pkg in homePkgs) return false
        if ("inputmethod" in pkg || "keyboard" in pkg) return false
        return packageManager.getLaunchIntentForPackage(pkg) != null
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        val cls = e.className?.toString() ?: ""
        // The phone's own recent-apps screen opened (square button): swap in NEXUS Open Apps
        // Only the system's dedicated recent-apps screen counts (e.g. ...quickstep.RecentsActivity or
        // ...systemui.recents.RecentsActivity). Never a launcher's home screen, never another app.
        val systemScreen = pkg in homePkgs || pkg == "com.android.systemui"
        val isRecents = systemScreen && pkg != packageName &&
            cls.contains("Recents", ignoreCase = true) && !cls.contains("Launcher", ignoreCase = true)
        if (isRecents && takeOverRecents() && HomeMode.isOn(this) && HomeMode.isDefault(this)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSwitcher > 800) {
                lastSwitcher = now
                // Cover the system screen straight away (no delay, no animation)
                try {
                    startActivity(Intent(this, SwitcherActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra("fromSquare", true), SwitcherActivity.noIconSplash(this))
                } catch (_: Exception) {}
            }
            return
        }
        if (isRealApp(pkg)) {
            if (pkg != current) {
                current = pkg
                Recents.touch(this, pkg)
                // Grab its screen shortly after it appears
                h.postDelayed({ snap() }, 700)
            }
        } else if (pkg in homePkgs || pkg == packageName) current = null
    }

    private fun takeOverRecents() = getSharedPreferences("hub", MODE_PRIVATE).getBoolean("replaceRecents", true)

    /** Every couple of seconds, refresh the current app's screenshot. */
    private val tick = object : Runnable {
        override fun run() {
            snap()
            h.postDelayed(this, 3000)
        }
    }

    private fun snap() {
        val pkg = current ?: return
        if (Build.VERSION.SDK_INT < 30) return
        val pm = getSystemService(PowerManager::class.java)
        val km = getSystemService(KeyguardManager::class.java)
        if (pm?.isInteractive != true || km?.isKeyguardLocked == true) return
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(r: ScreenshotResult) {
                    val buf = r.hardwareBuffer
                    Thread {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buf, r.colorSpace)
                            if (hw != null && current == pkg) {
                                val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                val w = 400; val hh = (soft.height * (w.toFloat() / soft.width)).toInt()
                                val small = Bitmap.createScaledBitmap(soft, w, hh, true)
                                val tmp = File(snapDir(this@NexusAccessibility), "$pkg.tmp")
                                FileOutputStream(tmp).use { small.compress(Bitmap.CompressFormat.JPEG, 78, it) }
                                tmp.renameTo(snapFile(this@NexusAccessibility, pkg))
                                soft.recycle(); small.recycle()
                            }
                        } catch (_: Throwable) {
                        } finally { buf.close() }
                    }.start()
                }
                override fun onFailure(code: Int) {}
            })
        } catch (_: Throwable) {}
    }
}

/** The order apps were last used in (most recent first), kept in NEXUS's settings. */
object Recents {
    fun list(c: Context): List<Pair<String, Long>> =
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("recents", "")!!
            .split(",").mapNotNull { e -> val p = e.split("|"); if (p.size == 2) p[0] to (p[1].toLongOrNull() ?: 0L) else null }

    fun touch(c: Context, pkg: String) {
        val l = list(c).filter { it.first != pkg }.toMutableList()
        l.add(0, pkg to System.currentTimeMillis())
        save(c, l.take(20))
    }

    fun remove(c: Context, pkg: String) {
        save(c, list(c).filter { it.first != pkg })
        NexusAccessibility.snapFile(c, pkg).delete()
    }

    fun clear(c: Context) {
        for ((p, _) in list(c)) NexusAccessibility.snapFile(c, p).delete()
        save(c, emptyList())
    }

    private fun save(c: Context, l: List<Pair<String, Long>>) =
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("recents", l.joinToString(",") { "${it.first}|${it.second}" }).apply()

    /** Is NEXUS's helper switched on in Accessibility settings? */
    fun serviceOn(c: Context): Boolean {
        val s = android.provider.Settings.Secure.getString(c.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("${c.packageName}/") && s.contains("NexusAccessibility")
    }
}
