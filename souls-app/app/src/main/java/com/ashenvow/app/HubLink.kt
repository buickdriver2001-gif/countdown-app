package com.ashenvow.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/** Goes back to the NEXUS launcher (after asking) and fully closes this app. */
object HubLink {
    const val HUB = "com.gamehub.app"

    fun installed(a: Activity) = a.packageManager.getLaunchIntentForPackage(HUB) != null

    /** Asks first, then opens NEXUS and closes Ashen Vow completely. */
    fun open(a: Activity) {
        if (a.isFinishing) return
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Return to NEXUS?")
            .setMessage("Ashen Vow will close. Anything not saved will be lost.")
            .setPositiveButton("Return to NEXUS") { _, _ -> go(a) }
            .setNegativeButton("Stay", null)
            .show()
    }

    private fun go(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage(HUB)
        if (i == null) {
            Toast.makeText(a, "NEXUS isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        a.startActivity(i)
        // Close this app for good: off the recent apps list, then stop it entirely
        a.finishAndRemoveTask()
        Handler(Looper.getMainLooper()).postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 400)
    }
}
