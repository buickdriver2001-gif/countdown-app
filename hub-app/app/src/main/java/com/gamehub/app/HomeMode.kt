package com.gamehub.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build

/**
 * NEXUS as the Home app: it shows on the first Home press after the screen turns on,
 * and otherwise passes Home straight through to the phone's normal home screen.
 */
object HomeMode {
    /** True after the screen turns off: the next Home press shows NEXUS. */
    @Volatile var showNexus = true

    fun watchScreen(app: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) showNexus = true
            }
        }
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(r, f)
    }

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
            a.startActivity(i, android.app.ActivityOptions.makeCustomAnimation(a, android.R.anim.fade_in, android.R.anim.fade_out).toBundle())
            true
        } catch (_: Exception) { false }
    }
}
