package com.gamehub.app

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short record of what the lock screen did recently (screen off/on, shown, closed, unlocked), kept on the
 * phone so a problem can be diagnosed from Settings > Report a problem. No personal data: only events and times.
 */
object LockTrace {
    private const val MAX_LINES = 80
    private val lines = ArrayDeque<String>()
    private var loaded = false
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun file(c: Context) = File(c.filesDir, "lock_trace.txt")

    /** Saving happens on its own background thread, a moment after the last note (several notes, one save). */
    private val saver by lazy { android.os.HandlerThread("nexus-log", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() } }
    private val saveH by lazy { android.os.Handler(saver.looper) }
    private var saveQueued = false

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            if (!loaded) { loaded = true; val f = file(c); if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
            lines.addLast(fmt.format(Date()) + "  " + msg)
            while (lines.size > MAX_LINES) lines.removeFirst()
            if (!saveQueued) {
                saveQueued = true
                val app = c.applicationContext
                saveH.postDelayed({ save(app) }, 400)
            }
        } catch (_: Exception) {}
    }

    /** Saves straight away (used when NEXUS is about to stop). */
    fun saveNow(c: Context) = save(c)

    private fun save(c: Context) {
        val text = synchronized(this) { saveQueued = false; lines.joinToString("\n") }
        try { file(c).writeText(text) } catch (_: Exception) {}
    }

    @Synchronized
    fun text(c: Context): String = try {
        if (loaded) lines.joinToString("\n") else { val f = file(c); if (f.exists()) f.readText() else "" }
    } catch (_: Exception) { "" }
}
