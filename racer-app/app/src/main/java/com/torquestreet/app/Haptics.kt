package com.torquestreet.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Vibration for bumps and crashes (works even when the phone's touch feedback is turned off). */
object Haptics {
    var enabled = true

    /** A bump: [strength] in m/s of impact (light taps barely, hard crashes strongly). */
    fun bump(ctx: Context, strength: Float) {
        if (!enabled || strength < 3f) return
        val ms = (25 + strength * 4).toLong().coerceAtMost(140)
        val amp = (60 + strength * 9).toInt().coerceIn(1, 255)
        vibrate(ctx, longArrayOf(0, ms), intArrayOf(0, amp))
    }

    fun tick(ctx: Context) { if (enabled) vibrate(ctx, longArrayOf(0, 25), intArrayOf(0, 140)) }

    private fun vibrate(ctx: Context, timings: LongArray, amps: IntArray) {
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            if (v == null || !v.hasVibrator()) return
            val a = if (v.hasAmplitudeControl()) amps else IntArray(amps.size) { if (amps[it] > 0) VibrationEffect.DEFAULT_AMPLITUDE else 0 }
            v.vibrate(VibrationEffect.createWaveform(timings, a, -1))
        } catch (_: Exception) {}
    }
}
