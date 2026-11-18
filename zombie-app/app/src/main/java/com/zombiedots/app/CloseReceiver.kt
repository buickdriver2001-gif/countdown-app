package com.zombiedots.app

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * NEXUS asks this app to close when you leave it and go back to the NEXUS home screen
 * (only NEXUS can send this: it needs the same signature). Zombie Dots then closes completely:
 * off the recent apps list and stopped, just like its own "Return to NEXUS" button does.
 */
class CloseReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // Never close while it's actually on screen
        val me = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(me)
        if (me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) return
        try { c.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { it.finishAndRemoveTask() } } catch (_: Exception) {}
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 300)
    }
}
