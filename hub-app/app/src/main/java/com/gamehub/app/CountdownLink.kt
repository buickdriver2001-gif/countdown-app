package com.gamehub.app

import android.content.Context
import android.net.Uri

/**
 * Reads the countdown from your Countdown app (its name and end time), for the live Countdown tile.
 * The Countdown app only shares it with apps signed with the same key (your own apps), read-only.
 */
object CountdownLink {
    const val PKG = "com.countdown.app"
    private val URI: Uri = Uri.parse("content://com.countdown.app.countdown/countdown")

    class Info(val name: String, val target: Long)

    /** The countdown, or null if there isn't one (none set, Countdown not installed, or an older Countdown). */
    fun read(c: Context): Info? = try {
        c.contentResolver.query(URI, null, null, null, null)?.use { cur ->
            if (!cur.moveToFirst()) return@use null
            val target = cur.getLong(cur.getColumnIndexOrThrow("target"))
            val name = cur.getString(cur.getColumnIndexOrThrow("name"))?.trim().orEmpty()
            if (target <= 0L) null else Info(name.ifEmpty { "Countdown" }, target)
        }
    } catch (_: Exception) { null }
}
