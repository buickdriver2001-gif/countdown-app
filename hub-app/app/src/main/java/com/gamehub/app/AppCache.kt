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
    /** Apps that can't be opened (uninstalled, or no screen to open). */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Name and icon for [pkg] (null if it can't be opened). Looks it up now if it isn't cached yet. */
    fun get(c: Context, pkg: String): Info? {
        infos[pkg]?.let { return it }
        if (pkg in missing) return null
        return load(c, pkg)
    }

    private fun load(c: Context, pkg: String): Info? {
        val pm = c.packageManager
        if (pm.getLaunchIntentForPackage(pkg) == null) { missing.add(pkg); return null }
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { missing.add(pkg); return null }
        val icon = try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        return Info(label, icon).also { infos[pkg] = it; missing.remove(pkg) }
    }

    /** Looks up these apps in the background (so they're ready next time). */
    fun warm(c: Context, pkgs: List<String>) {
        val todo = pkgs.filter { !infos.containsKey(it) }
        if (todo.isEmpty()) return
        val app = c.applicationContext
        Thread { for (p in todo) { missing.remove(p); load(app, p) } }.start()
    }

    /** Forget an app (it was uninstalled or updated). */
    fun forget(pkg: String) { infos.remove(pkg); missing.remove(pkg) }
}
