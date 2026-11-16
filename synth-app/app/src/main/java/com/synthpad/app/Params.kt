package com.synthpad.app

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Every knob on the synth. Values are stored 0..1; the engine maps them to real units. */
enum class P(val label: String, val def: Float, val steps: Int = 0) {
    // Sound
    WAVE("Wave", 0.5f, 5),
    DETUNE("Detune", 0.15f),
    SUB("Sub", 0f),
    NOISE("Noise", 0f),
    VIBRATO("Vibrato", 0f),

    // Envelope
    ATTACK("Attack", 0.05f),
    DECAY("Decay", 0.4f),
    SUSTAIN("Sustain", 0.8f),
    RELEASE("Release", 0.45f),

    // Filter
    CUTOFF("Cutoff", 0.75f),
    RESO("Reso", 0.2f),
    ENV_AMT("Env Amt", 0.2f),
    LFO_DEPTH("Wobble", 0f),
    LFO_RATE("Wob Rate", 0.5f),

    // Motion
    GATE_DEPTH("Gate", 0f),
    GATE_RATE("Gate Rate", 0.6f),
    PAN("Pan", 0.5f),
    APAN_DEPTH("Auto Pan", 0f),
    APAN_RATE("Pan Rate", 0.4f),

    // FX
    DRIVE("Drive", 0f),
    CRUSH("Crush", 0f),
    CHORUS("Chorus", 0f),

    // Space
    DELAY_MIX("Echo", 0f),
    DELAY_TIME("Echo Time", 0.35f),
    DELAY_FB("Feedback", 0.35f),
    REV_MIX("Reverb", 0.15f),
    REV_SIZE("Room Size", 0.6f),

    VOLUME("Volume", 0.7f);

    companion object {
        val ALL = values()
        val COUNT = ALL.size
    }
}

object ParamMath {
    val WAVE_NAMES = arrayOf("Sine", "Triangle", "Saw", "Square", "Super")

    fun waveIndex(v: Float) = (v * 4.999f).toInt().coerceIn(0, 4)
    fun attackSec(v: Float) = 0.001f * 2000f.pow(v)
    fun decaySec(v: Float) = 0.01f * 500f.pow(v)
    fun releaseSec(v: Float) = 0.01f * 400f.pow(v)
    fun cutoffHz(v: Float) = 40f * 500f.pow(v)
    fun lfoHz(v: Float) = 0.1f * 200f.pow(v)
    fun gateHz(v: Float) = 0.5f * 32f.pow(v)
    fun panHz(v: Float) = 0.05f * 200f.pow(v)
    fun delaySec(v: Float) = 0.05f + v * 0.95f

    private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
    private fun time(s: Float) =
        if (s < 1f) "${(s * 1000).roundToInt()}ms" else String.format(Locale.US, "%.1fs", s)
    private fun hz(h: Float) = when {
        h >= 1000f -> String.format(Locale.US, "%.1fk", h / 1000f)
        h >= 10f -> "${h.roundToInt()}Hz"
        else -> String.format(Locale.US, "%.1fHz", h)
    }

    fun display(p: P, v: Float): String = when (p) {
        P.WAVE -> WAVE_NAMES[waveIndex(v)]
        P.DETUNE -> "${(v * 50).roundToInt()}c"
        P.ATTACK -> time(attackSec(v))
        P.DECAY -> time(decaySec(v))
        P.RELEASE -> time(releaseSec(v))
        P.CUTOFF -> hz(cutoffHz(v))
        P.LFO_RATE -> hz(lfoHz(v))
        P.GATE_RATE -> hz(gateHz(v))
        P.APAN_RATE -> hz(panHz(v))
        P.DELAY_TIME -> time(delaySec(v))
        P.PAN -> {
            val x = ((v - 0.5f) * 200).roundToInt()
            when {
                x == 0 -> "C"
                x < 0 -> "L${-x}"
                else -> "R$x"
            }
        }
        else -> pct(v)
    }
}

/** A tab of knobs. */
class Page(val name: String, val params: List<P>)

val PAGES = listOf(
    Page("SOUND", listOf(P.WAVE, P.DETUNE, P.SUB, P.NOISE, P.VIBRATO)),
    Page("SHAPE", listOf(P.ATTACK, P.DECAY, P.SUSTAIN, P.RELEASE)),
    Page("FILTER", listOf(P.CUTOFF, P.RESO, P.ENV_AMT, P.LFO_DEPTH, P.LFO_RATE)),
    Page("MOTION", listOf(P.GATE_DEPTH, P.GATE_RATE, P.PAN, P.APAN_DEPTH, P.APAN_RATE)),
    Page("FX", listOf(P.DRIVE, P.CRUSH, P.CHORUS)),
    Page("SPACE", listOf(P.DELAY_MIX, P.DELAY_TIME, P.DELAY_FB, P.REV_MIX, P.REV_SIZE)),
)

class Preset(val name: String, val values: Map<P, Float>) {
    /** Full set of knob values: anything the preset doesn't mention goes back to default. */
    fun resolved(volume: Float): FloatArray {
        val out = FloatArray(P.COUNT) { P.ALL[it].def }
        for ((p, v) in values) out[p.ordinal] = v
        out[P.VOLUME.ordinal] = volume // keep the player's volume
        return out
    }
}

private fun w(name: String) = ParamMath.WAVE_NAMES.indexOf(name) / 4f

val PRESETS = listOf(
    Preset("Classic Saw", mapOf()),
    Preset("Warm Pad", mapOf(
        P.WAVE to w("Super"), P.DETUNE to 0.35f, P.ATTACK to 0.6f, P.DECAY to 0.6f, P.SUSTAIN to 0.85f,
        P.RELEASE to 0.75f, P.CUTOFF to 0.55f, P.RESO to 0.1f, P.ENV_AMT to 0.1f, P.CHORUS to 0.6f,
        P.REV_MIX to 0.45f, P.REV_SIZE to 0.85f, P.APAN_DEPTH to 0.3f, P.APAN_RATE to 0.25f
    )),
    Preset("Deep Bass", mapOf(
        P.WAVE to w("Square"), P.SUB to 0.8f, P.DETUNE to 0.05f, P.CUTOFF to 0.35f, P.RESO to 0.3f,
        P.ENV_AMT to 0.45f, P.DECAY to 0.35f, P.SUSTAIN to 0.5f, P.RELEASE to 0.25f, P.DRIVE to 0.2f,
        P.REV_MIX to 0f
    )),
    Preset("Pluck", mapOf(
        P.WAVE to w("Saw"), P.DECAY to 0.35f, P.SUSTAIN to 0f, P.RELEASE to 0.4f, P.CUTOFF to 0.35f,
        P.RESO to 0.35f, P.ENV_AMT to 0.65f, P.DELAY_MIX to 0.35f, P.DELAY_TIME to 0.3f,
        P.DELAY_FB to 0.4f, P.REV_MIX to 0.25f
    )),
    Preset("Wobble Bass", mapOf(
        P.WAVE to w("Saw"), P.SUB to 0.6f, P.DETUNE to 0.2f, P.CUTOFF to 0.3f, P.RESO to 0.55f,
        P.ENV_AMT to 0f, P.LFO_DEPTH to 0.75f, P.LFO_RATE to 0.55f, P.DRIVE to 0.45f,
        P.SUSTAIN to 1f, P.RELEASE to 0.3f, P.REV_MIX to 0.05f
    )),
    Preset("Chiptune", mapOf(
        P.WAVE to w("Square"), P.DETUNE to 0f, P.CRUSH to 0.55f, P.CUTOFF to 1f, P.RESO to 0f,
        P.ENV_AMT to 0f, P.ATTACK to 0f, P.SUSTAIN to 0.7f, P.RELEASE to 0.2f, P.VIBRATO to 0.25f,
        P.REV_MIX to 0.05f
    )),
    Preset("Trance Gate", mapOf(
        P.WAVE to w("Super"), P.DETUNE to 0.4f, P.GATE_DEPTH to 1f, P.GATE_RATE to 0.72f,
        P.SUSTAIN to 1f, P.RELEASE to 0.6f, P.CUTOFF to 0.7f, P.DELAY_MIX to 0.3f,
        P.DELAY_TIME to 0.25f, P.REV_MIX to 0.3f, P.APAN_DEPTH to 0.5f, P.APAN_RATE to 0.45f
    )),
    Preset("Glass Bells", mapOf(
        P.WAVE to w("Sine"), P.DETUNE to 0.12f, P.ATTACK to 0f, P.DECAY to 0.7f, P.SUSTAIN to 0f,
        P.RELEASE to 0.7f, P.CUTOFF to 1f, P.ENV_AMT to 0f, P.DELAY_MIX to 0.3f, P.DELAY_TIME to 0.4f,
        P.DELAY_FB to 0.45f, P.REV_MIX to 0.55f, P.REV_SIZE to 0.9f
    )),
    Preset("Organ", mapOf(
        P.WAVE to w("Sine"), P.SUB to 0.7f, P.DETUNE to 0.08f, P.ATTACK to 0.1f, P.SUSTAIN to 1f,
        P.RELEASE to 0.3f, P.CUTOFF to 0.8f, P.ENV_AMT to 0f, P.DRIVE to 0.15f, P.CHORUS to 0.5f,
        P.VIBRATO to 0.2f, P.REV_MIX to 0.3f
    )),
    Preset("Space Drift", mapOf(
        P.WAVE to w("Triangle"), P.DETUNE to 0.25f, P.ATTACK to 0.45f, P.RELEASE to 0.8f,
        P.CUTOFF to 0.6f, P.ENV_AMT to 0f, P.LFO_DEPTH to 0.25f, P.LFO_RATE to 0.2f,
        P.APAN_DEPTH to 0.9f, P.APAN_RATE to 0.3f, P.DELAY_MIX to 0.45f, P.DELAY_TIME to 0.6f,
        P.DELAY_FB to 0.65f, P.REV_MIX to 0.6f, P.REV_SIZE to 0.95f
    )),
    Preset("Dirty Lead", mapOf(
        P.WAVE to w("Square"), P.DETUNE to 0.2f, P.DRIVE to 0.6f, P.CUTOFF to 0.65f, P.RESO to 0.3f,
        P.ENV_AMT to 0.3f, P.VIBRATO to 0.3f, P.SUSTAIN to 0.9f, P.DELAY_MIX to 0.25f,
        P.DELAY_TIME to 0.33f, P.REV_MIX to 0.2f
    )),
    Preset("Lo-Fi Keys", mapOf(
        P.WAVE to w("Triangle"), P.DETUNE to 0.1f, P.DECAY to 0.55f, P.SUSTAIN to 0.4f,
        P.RELEASE to 0.5f, P.CUTOFF to 0.5f, P.ENV_AMT to 0.25f, P.CRUSH to 0.3f, P.NOISE to 0.08f,
        P.CHORUS to 0.4f, P.VIBRATO to 0.12f, P.REV_MIX to 0.3f
    )),
)
