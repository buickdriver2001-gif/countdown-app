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

    @Synchronized
    fun log(c: Context, msg: String) {
        try {
            if (!loaded) { loaded = true; val f = file(c); if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) } }
            lines.addLast(fmt.format(Date()) + "  " + msg)
            while (lines.size > MAX_LINES) lines.removeFirst()
            file(c).writeText(lines.joinToString("\n"))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun text(c: Context): String = try { val f = file(c); if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
}
