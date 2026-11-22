package com.torquestreet.app.core

import kotlin.math.sqrt

enum class TimeOfDay(val label: String) { DAY("Day"), SUNSET("Sunset"), NIGHT("Night") }
enum class Weather(val label: String) { CLEAR("Clear"), RAIN("Rain"), FOG("Fog") }

/**
 * The light and air for a time of day and weather: where the sun (or moon) is and its colour, the sky, the
 * ambient light, fog, how wet the roads are, whether the street lights and windows are on, rain and clouds.
 */
class Env(val time: TimeOfDay, val weather: Weather, val scene: Scene) {
    val sunDir = FloatArray(3); val sunCol = FloatArray(3)
    val ambSky = FloatArray(3); val ambGround = FloatArray(3)
    val skyTop = FloatArray(3); val skyHor = FloatArray(3)
    val fogCol = FloatArray(3)
    var fogDens = 0.001f
    /** 0..1: street lights, lit windows and headlights. */
    var night = 0f
    var wet = 0f
    var rain = 0f
    var cloud = 0f
    var stars = 0f
    var exposure = 1.6f
    val lampCol = floatArrayOf(1.0f, 0.74f, 0.42f)
    /** Sand near the water (the coast). */
    val sand = if (scene == Scene.COAST) 1f else 0f

    init {
        when (time) {
            TimeOfDay.DAY -> {
                dir(0.35f, 0.82f, 0.28f); set(sunCol, 1.05f, 1.0f, 0.9f, 1.25f)
                set(ambSky, 0.42f, 0.52f, 0.68f, 1.05f); set(ambGround, 0.34f, 0.31f, 0.27f, 0.7f)
                set(skyTop, 0.22f, 0.42f, 0.78f); set(skyHor, 0.70f, 0.79f, 0.88f)
                fogDens = 0.00085f; night = 0f; exposure = 1.55f
            }
            TimeOfDay.SUNSET -> {
                dir(-0.62f, 0.13f, 0.4f); set(sunCol, 1.25f, 0.66f, 0.34f, 1.1f)
                set(ambSky, 0.40f, 0.34f, 0.46f, 0.8f); set(ambGround, 0.26f, 0.18f, 0.15f, 0.6f)
                set(skyTop, 0.16f, 0.2f, 0.44f); set(skyHor, 1.0f, 0.56f, 0.32f)
                fogDens = 0.0011f; night = 0.45f; exposure = 1.7f
            }
            TimeOfDay.NIGHT -> {
                dir(0.3f, 0.62f, -0.5f); set(sunCol, 0.22f, 0.28f, 0.45f, 0.55f)
                set(ambSky, 0.07f, 0.09f, 0.16f, 1f); set(ambGround, 0.035f, 0.035f, 0.045f, 1f)
                set(skyTop, 0.008f, 0.012f, 0.035f)
                if (scene == Scene.CITY) set(skyHor, 0.17f, 0.11f, 0.09f) else set(skyHor, 0.05f, 0.07f, 0.12f)
                fogDens = 0.0012f; night = 1f; stars = if (scene == Scene.CITY) 0.35f else 1f; exposure = 2.0f
            }
        }
        when (weather) {
            Weather.CLEAR -> { cloud = if (time == TimeOfDay.SUNSET) 0.25f else 0.15f }
            Weather.RAIN -> {
                cloud = 1f; wet = 1f; rain = 1f; stars = 0f
                scale(sunCol, 0.22f); scale(ambSky, 0.85f)
                val grey = when (time) { TimeOfDay.DAY -> floatArrayOf(0.46f, 0.49f, 0.53f); TimeOfDay.SUNSET -> floatArrayOf(0.42f, 0.36f, 0.36f); TimeOfDay.NIGHT -> floatArrayOf(0.06f, 0.065f, 0.08f) }
                mix(skyHor, grey, 0.8f); mix(skyTop, floatArrayOf(grey[0] * 0.7f, grey[1] * 0.72f, grey[2] * 0.78f), 0.85f)
                ambSky[0] = ambSky[0] * 0.8f + skyHor[0] * 0.25f; ambSky[1] = ambSky[1] * 0.8f + skyHor[1] * 0.25f; ambSky[2] = ambSky[2] * 0.8f + skyHor[2] * 0.25f
                fogDens *= 2.3f
                if (time == TimeOfDay.DAY) night = 0.25f
            }
            Weather.FOG -> {
                cloud = 0.7f; wet = 0.35f; stars = 0f
                scale(sunCol, 0.45f)
                val grey = when (time) { TimeOfDay.DAY -> floatArrayOf(0.72f, 0.74f, 0.76f); TimeOfDay.SUNSET -> floatArrayOf(0.7f, 0.56f, 0.48f); TimeOfDay.NIGHT -> floatArrayOf(0.09f, 0.09f, 0.11f) }
                mix(skyHor, grey, 0.9f); mix(skyTop, grey, 0.6f)
                fogDens *= 4.2f
                if (time == TimeOfDay.DAY) night = 0.2f
            }
        }
        fogCol[0] = skyHor[0]; fogCol[1] = skyHor[1]; fogCol[2] = skyHor[2]
    }

    private fun dir(x: Float, y: Float, z: Float) { val l = sqrt(x * x + y * y + z * z); sunDir[0] = x / l; sunDir[1] = y / l; sunDir[2] = z / l }
    private fun set(a: FloatArray, r: Float, g: Float, b: Float, k: Float = 1f) { a[0] = r * k; a[1] = g * k; a[2] = b * k }
    private fun scale(a: FloatArray, k: Float) { a[0] *= k; a[1] *= k; a[2] *= k }
    private fun mix(a: FloatArray, b: FloatArray, t: Float) { for (i in 0..2) a[i] = lerp(a[i], b[i], t) }

    /** Everything above, as name = values (for the preview tool on a computer). */
    fun describe(): String = buildString {
        fun v(n: String, a: FloatArray) = append("$n=${a.joinToString(",")}\n")
        v("sunDir", sunDir); v("sunCol", sunCol); v("ambSky", ambSky); v("ambGround", ambGround); v("skyTop", skyTop); v("skyHor", skyHor); v("fogCol", fogCol)
        v("lampCol", lampCol)
        append("fogDens=$fogDens\nnight=$night\nwet=$wet\nrain=$rain\ncloud=$cloud\nstars=$stars\nexposure=$exposure\nsand=$sand\n")
    }
}
