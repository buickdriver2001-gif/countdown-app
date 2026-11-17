package com.zombiedots.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Real vibration patterns (works even when the phone's touch feedback is turned off). */
object Haptics {
    var enabled = true

    enum class Kind(val timings: LongArray, val amps: IntArray) {
        TICK(longArrayOf(0, 30), intArrayOf(0, 160)),
        COUNT(longArrayOf(0, 70), intArrayOf(0, 230)),
        NUKE(longArrayOf(0, 160, 60, 450), intArrayOf(0, 255, 0, 190)),
        PLANE(longArrayOf(0, 45, 70, 45, 70, 45, 70, 45), intArrayOf(0, 210, 0, 210, 0, 210, 0, 210)),
        HEALTH(longArrayOf(0, 35, 70, 35), intArrayOf(0, 150, 0, 230)),
        CURE(longArrayOf(0, 110), intArrayOf(0, 130)),
        WAVE(longArrayOf(0, 60), intArrayOf(0, 200)),
        BOSS(longArrayOf(0, 350), intArrayOf(0, 255)),
        TURNED(longArrayOf(0, 90), intArrayOf(0, 210)),
    }

    fun play(ctx: Context, kind: Kind) {
        if (!enabled) return
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            val amps = if (v.hasAmplitudeControl()) kind.amps
                else IntArray(kind.amps.size) { if (kind.amps[it] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0 }
            v.vibrate(VibrationEffect.createWaveform(kind.timings, amps, -1))
        } catch (_: Exception) {
        }
    }
}
