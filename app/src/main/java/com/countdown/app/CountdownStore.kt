package com.countdown.app

import android.content.Context
import java.util.Locale

/** Saves the countdown name and target date on the phone. */
object CountdownStore {
    private const val PREFS = "countdown"
    private const val KEY_NAME = "name"
    private const val KEY_TARGET = "target"
    const val DEFAULT_NAME = "My Countdown"

    fun name(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME

    /** Target time in milliseconds, or 0 if none has been set yet. */
    fun target(ctx: Context): Long = prefs(ctx).getLong(KEY_TARGET, 0L)

    fun save(ctx: Context, name: String, target: Long) {
        prefs(ctx).edit()
            .putString(KEY_NAME, name.trim())
            .putLong(KEY_TARGET, target)
            .apply()
    }

    /** Formats milliseconds as total HH:MM:SS (hours keep counting past 24, no days). */
    fun format(ms: Long): String {
        val totalSeconds = (if (ms < 0) 0 else ms) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
