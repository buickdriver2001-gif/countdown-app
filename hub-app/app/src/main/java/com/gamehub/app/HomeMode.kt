package com.gamehub.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * NEXUS as the Home app.
 * While NEXUS mode is on, Home always shows NEXUS (screen off/on keeps you there).
 * Closing NEXUS on purpose turns the mode off: Home then goes to the normal home screen
 * until NEXUS is opened again from its icon.
 */
object HomeMode {
    private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

    fun isOn(c: Context) = prefs(c).getBoolean("nexusMode", true)
    fun setOn(c: Context, on: Boolean) = prefs(c).edit().putBoolean("nexusMode", on).apply()

    /** Is NEXUS currently the phone's default Home app? */
    fun isDefault(c: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val info = c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return info?.activityInfo?.packageName == c.packageName
    }

    /** Every other home screen app on the phone (label, component). */
    fun otherHomes(c: Context): List<Pair<String, ComponentName>> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return c.packageManager.queryIntentActivities(i, 0)
            .filter { it.activityInfo.packageName != c.packageName && it.activityInfo.packageName != "com.android.settings" }
            .map { it.loadLabel(c.packageManager).toString() to ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    /** The normal home screen NEXUS hands over to (chosen, or the only other one). */
    fun normalHome(c: Context): ComponentName? {
        val prefs = c.getSharedPreferences("hub", Context.MODE_PRIVATE)
        val saved = prefs.getString("normalHome", null)?.let { ComponentName.unflattenFromString(it) }
        val others = otherHomes(c)
        if (saved != null && others.any { it.second == saved }) return saved
        return others.firstOrNull()?.second
    }

    fun setNormalHome(c: Context, cn: ComponentName) {
        c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("normalHome", cn.flattenToString()).apply()
    }

    /** Opens the phone's normal home screen. Returns false if there isn't one. */
    fun goNormalHome(a: Activity): Boolean {
        val cn = normalHome(a) ?: return false
        return try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            a.startActivity(i, Transitions.rise(a))
            true
        } catch (_: Exception) { false }
    }
}
