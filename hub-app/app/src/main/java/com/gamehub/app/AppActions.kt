package com.gamehub.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Process
import android.widget.Toast

/**
 * The things a launcher does with an app: open it, show its shortcuts, uninstall it.
 * Everything goes through Android's own launcher APIs and confirmation screens.
 */
object AppActions {

    /**
     * Opens an app the way launchers do: its own launcher screen, in its own task, carrying on where the
     * person left it. Never crashes NEXUS if the app can't be opened (e.g. it's being updated right now).
     */
    fun open(a: Activity, pkg: String, component: ComponentName? = null, options: android.os.Bundle? = null): Boolean {
        val i = (if (component != null) Intent.makeMainActivity(component) else a.packageManager.getLaunchIntentForPackage(pkg))
            ?: return cantOpen(a, pkg)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { a.startActivity(i, options); true } catch (_: Exception) {
            // That screen is gone (app updated): fall back to the app's main screen
            if (component != null) open(a, pkg, null, options) else cantOpen(a, pkg)
        }
    }

    private fun cantOpen(a: Activity, pkg: String): Boolean {
        Toast.makeText(a, "Couldn't open ${label(a, pkg)} right now", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun label(a: Activity, pkg: String) = try { a.packageManager.getApplicationLabel(a.packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "that app" }

    /**
     * The app's shortcuts (like "New incognito tab" or "Subscriptions"), as long-press options.
     * Android only shares these with the phone's Home app, so they appear once NEXUS is the Home app.
     */
    fun shortcutOptions(a: Activity, pkg: String): List<NexusUi.Option> {
        val la = a.getSystemService(LauncherApps::class.java) ?: return emptyList()
        val list: List<ShortcutInfo> = try {
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery().setPackage(pkg)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
            la.getShortcuts(q, Process.myUserHandle()) ?: emptyList()
        } catch (_: Exception) { emptyList() }
        // Like other launchers: the app's fixed shortcuts first, then its recent ones, in the app's order; at most 4
        return list.filter { it.isEnabled && !it.shortLabel.isNullOrBlank() }
            .sortedWith(compareBy<ShortcutInfo>({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
            .take(4)
            .map { s ->
                NexusUi.Option("›  " + s.shortLabel.toString().trim()) {
                    try { la.startShortcut(s, null, Transitions.app(a.window.decorView)) }
                    catch (_: Exception) { Toast.makeText(a, "That shortcut isn't available right now", Toast.LENGTH_SHORT).show() }
                }
            }
    }

    /** "App info" (Android's page for the app). */
    fun infoOption(a: Activity, pkg: String) = NexusUi.Option("App info") {
        try { a.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))) } catch (_: Exception) {}
    }

    /** "Uninstall", for apps the person installed (Android asks to confirm; built-in phone apps can't be removed). */
    fun uninstallOption(a: Activity, pkg: String): NexusUi.Option? {
        val info = try { a.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { return null }
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 || pkg == a.packageName) return null
        return NexusUi.Option("Uninstall", danger = true) {
            try { a.startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))) }
            catch (_: Exception) { Toast.makeText(a, "Couldn't open uninstall", Toast.LENGTH_SHORT).show() }
        }
    }
}
