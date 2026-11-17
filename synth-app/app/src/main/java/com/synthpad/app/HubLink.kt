package com.synthpad.app

import android.app.Activity
import android.content.Intent
import android.widget.Toast

/** Opens the NEXUS launcher (if it's installed) and closes this game. */
object HubLink {
    const val HUB = "com.gamehub.app"

    fun installed(a: Activity) = a.packageManager.getLaunchIntentForPackage(HUB) != null

    fun open(a: Activity) {
        val i = a.packageManager.getLaunchIntentForPackage(HUB)
        if (i == null) {
            Toast.makeText(a, "NEXUS isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        a.startActivity(i)
        a.finish()
    }
}
