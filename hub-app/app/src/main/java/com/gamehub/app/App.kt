package com.gamehub.app

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File

/** Saves the details of any crash so the app can show them on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Which NEXUS screens are in front (so a problem in the background never pops up over what you're doing)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { screensInFront++ }
            override fun onActivityPaused(a: android.app.Activity) { screensInFront = (screensInFront - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val onScreen = screensInFront > 0
            LockTrace.saveNow(this) // the last notes before a crash matter most
            saveCrash(this, "thread ${thread.name}" + (if (onScreen) ", on screen" else ", in the background"), error)
            try {
                // On screen: open the error screen (in its own process) instead of "keeps stopping".
                // In the background: don't interrupt; the details wait under Settings > Report a problem.
                if (onScreen) startActivity(
                    android.content.Intent(this, CrashActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                else crashFile(this).delete() // nothing to show on the next start; it's kept in the report
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        @Volatile var screensInFront = 0

        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
        /** Every problem noted (crashes and helper problems), newest first, for Settings > Report a problem. */
        fun reportFile(c: android.content.Context) = File(c.filesDir, "problem_report.txt")

        private fun addToReport(ctx: android.content.Context, text: String) {
            try {
                val f = reportFile(ctx)
                val old = if (f.exists()) f.readText() else ""
                val stamp = java.text.SimpleDateFormat("MMM d, h:mm:ss a", java.util.Locale.US).format(java.util.Date())
                f.writeText(("[$stamp] " + text.take(6000) + "\n\n" + old).take(20000))
            } catch (_: Exception) {}
        }

        /** A problem NEXUS caught and kept going after (noted for Report a problem). */
        fun noteProblem(ctx: android.content.Context, where: String, error: Throwable) {
            addToReport(ctx, "NEXUS problem in $where (kept running)\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, NEXUS ${version(ctx)}\n" + Log.getStackTraceString(error))
        }

        private fun version(c: android.content.Context) = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Exception) { "?" }
        /** A problem inside the Recent Apps helper: noted (for "Report a problem"), but NEXUS keeps running. */
        fun saveHelperError(ctx: android.content.Context, error: Throwable) = noteProblem(ctx, "the Recent Apps helper", error)

        fun saveCrash(ctx: android.content.Context, where: String, error: Throwable) {
            val text = "NEXUS error ($where)\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                "${Build.MANUFACTURER} ${Build.MODEL}, NEXUS ${version(ctx)}\n\n" + Log.getStackTraceString(error)
            try { crashFile(ctx).writeText(text) } catch (_: Exception) {}
            addToReport(ctx, text)
        }
    }
}
