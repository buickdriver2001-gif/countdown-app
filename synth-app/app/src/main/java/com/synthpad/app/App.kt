package com.synthpad.app

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
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")

        fun saveCrash(ctx: android.content.Context, where: String, error: Throwable) {
            try {
                crashFile(ctx).writeText(
                    "SynthPad error ($where)\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n\n" + Log.getStackTraceString(error)
                )
            } catch (_: Exception) {
            }
        }
    }
}
