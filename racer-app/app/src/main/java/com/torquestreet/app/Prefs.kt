package com.torquestreet.app

import android.content.Context
import com.torquestreet.app.core.Mode
import com.torquestreet.app.core.TimeOfDay
import com.torquestreet.app.core.Weather

/** The player's choices and settings, kept between sessions. */
class Prefs(c: Context) {
    private val p = c.getSharedPreferences("torque", Context.MODE_PRIVATE)

    var tilt: Boolean get() = p.getBoolean("tilt", false); set(v) = p.edit().putBoolean("tilt", v).apply()
    var tiltSens: Float get() = p.getFloat("tiltSens", 1f); set(v) = p.edit().putFloat("tiltSens", v).apply()
    var tiltInvert: Boolean get() = p.getBoolean("tiltInvert", false); set(v) = p.edit().putBoolean("tiltInvert", v).apply()
    var autoGas: Boolean get() = p.getBoolean("autoGas", true); set(v) = p.edit().putBoolean("autoGas", v).apply()
    var mph: Boolean get() = p.getBoolean("mph", false); set(v) = p.edit().putBoolean("mph", v).apply()
    var sound: Float get() = p.getFloat("sound", 0.8f); set(v) = p.edit().putFloat("sound", v).apply()
    var vibrate: Boolean get() = p.getBoolean("vibrate", true); set(v) = p.edit().putBoolean("vibrate", v).apply()
    /** 0 smooth (lower resolution, shorter view), 1 balanced, 2 sharp. */
    var quality: Int get() = p.getInt("quality", 1); set(v) = p.edit().putInt("quality", v).apply()
    var camera: Int get() = p.getInt("camera", 0); set(v) = p.edit().putInt("camera", v).apply()

    var mode: Mode get() = Mode.values()[p.getInt("mode", 0).coerceIn(0, Mode.values().size - 1)]; set(v) = p.edit().putInt("mode", v.ordinal).apply()
    var track: Int get() = p.getInt("track", 0).coerceIn(0, 2); set(v) = p.edit().putInt("track", v).apply()
    var car: Int get() = p.getInt("car", 0).coerceIn(0, 2); set(v) = p.edit().putInt("car", v).apply()
    var paint: Int get() = p.getInt("paint", 0).coerceIn(0, 4); set(v) = p.edit().putInt("paint", v).apply()
    var time: TimeOfDay get() = TimeOfDay.values()[p.getInt("time", 2).coerceIn(0, 2)]; set(v) = p.edit().putInt("time", v.ordinal).apply()
    var weather: Weather get() = Weather.values()[p.getInt("weather", 0).coerceIn(0, 2)]; set(v) = p.edit().putInt("weather", v.ordinal).apply()

    fun best(track: Int, mode: Mode): Float = p.getFloat("best_${track}_${mode.ordinal}", 0f)
    fun setBest(track: Int, mode: Mode, t: Float) = p.edit().putFloat("best_${track}_${mode.ordinal}", t).apply()
}
