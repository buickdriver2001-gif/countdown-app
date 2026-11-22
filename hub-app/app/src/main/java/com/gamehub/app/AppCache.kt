package com.gamehub.app

import android.content.Context
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * App names and icons for NEXUS Recent Apps, looked up once and kept in memory, so the square button
 * opens Recent Apps without stopping to ask the phone about every app first.
 * The helper fills it in quietly in the background as you use apps.
 */
object AppCache {
    class Info(val label: String, val icon: Drawable?)
    private val infos = ConcurrentHashMap<String, Info>()

    /** Name and icon for [pkg] (null if it can't be opened). Looks it up now if it isn't cached yet. */
    fun get(c: Context, pkg: String): Info? {
        infos[pkg]?.let { return it }
        return load(c, pkg) // (checked again each time, so an app that was mid-update comes back)
    }

    private fun load(c: Context, pkg: String): Info? {
        val pm = c.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null) return null
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { return null }
        val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        return Info(label, icon).also { infos[pkg] = it }
    }

    /** Looks up these apps in the background (so they're ready next time). */
    fun warm(c: Context, pkgs: List<String>) {
        val todo = pkgs.filter { !infos.containsKey(it) }
        if (todo.isEmpty()) return
        val app = c.applicationContext
        Bg.thread { for (p in todo) load(app, p) }.start()
    }

    /** Forget an app (it was uninstalled or updated). */
    fun forget(pkg: String) { infos.remove(pkg) }
}
