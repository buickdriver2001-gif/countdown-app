package com.gamehub.app

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File

/** Saves the details of any crash so the app can show them on the next launch. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            saveCrash(this, "thread ${thread.name}", error)
            try {
                // Open the error screen (in its own process) instead of "keeps stopping"
                startActivity(
                    android.content.Intent(this, CrashActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (_: Throwable) {
                previous?.uncaughtException(thread, error)
            }
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")

        fun saveCrash(ctx: android.content.Context, where: String, error: Throwable) {
            try {
                crashFile(ctx).writeText(
                    "NEXUS error ($where)\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n\n" + Log.getStackTraceString(error)
                )
            } catch (_: Exception) {
            }
        }
    }
}
