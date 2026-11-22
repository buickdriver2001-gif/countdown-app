package com.torquestreet.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What the sound should be doing right now (written by the game each frame, read by the sound thread). */
class SoundState {
    @Volatile var rpm = 0.2f
    @Volatile var throttle = 0f
    @Volatile var speed = 0f
    @Volatile var slide = 0f
    @Volatile var nitro = false
    @Volatile var siren = 0f
    @Volatile var scrape = 0f
    @Volatile var rain = 0f
    @Volatile var gear = 1
    @Volatile var paused = false
    @Volatile var countdown = -1f
    @Volatile var volume = 0.8f
    @Volatile var bumpStrength = 0f
    fun bump(s: Float) { if (s > bumpStrength) bumpStrength = s }
}

/**
 * The race's sound, made up live (no recordings): an engine (harmonics of its firing, rising through the
 * gears), tyre screech, wind, scraping and crashes, the police siren, rain, the nitro whoosh and the start
 * beeps. Runs on its own thread.
 */
class SoundEngine(private val st: SoundState) : Thread("sound") {
    @Volatile private var running = true
    private val rate = 22050
    private val lut = FloatArray(4096) { sin(it / 4096.0 * 2 * PI).toFloat() }
    private fun sn(phase: Float): Float { val i = ((phase - kotlin.math.floor(phase)) * 4096f).toInt() and 4095; return lut[i] }
    private var seed = 0x2545F491
    private fun noise(): Float { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return (seed and 0xffff) / 32768f - 1f }

    fun shutdown() { running = false }

    override fun run() {
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(max(minBuf, 2048 * 2))
                .build()
        } catch (_: Throwable) { return }
        try { track.play() } catch (_: Throwable) { track.release(); return }
        val block = 512
        val out = ShortArray(block)
        // Engine
        var f0 = 50f; var ph = 0f; var eng = 0f; var lastGear = 1; var shiftDip = 0f
        // Filters
        var wind = 0f; var sLow = 0f; var sBand = 0f; var cLow = 0f; var cBand = 0f; var rLow = 0f; var nLow = 0f; var nBand = 0f
        // Siren, crash, beeps
        var sirenPh = 0f; var sirenT = 0f; var crash = 0f; var crashPh = 0f; var crashLow = 0f
        var beep = 0f; var beepF = 880f; var beepPh = 0f; var lastCount = 99
        var master = 0f; var t = 0f
        val dtS = 1f / rate
        try {
            while (running) {
                val paused = st.paused
                val rpm = st.rpm; val thr = st.throttle; val spd = st.speed; val slide = st.slide
                val sirenV = st.siren; val scrape = st.scrape; val rain = st.rain; val nitro = st.nitro
                val vol = st.volume
                if (st.gear != lastGear) { if (st.gear > lastGear) shiftDip = 1f; lastGear = st.gear }
                val b = st.bumpStrength
                if (b > 0f) { crash = max(crash, min(1f, b / 16f)); st.bumpStrength = 0f }
                // Start beeps: 3, 2, 1 then GO
                val cd = st.countdown
                if (cd >= 0f) {
                    val n = kotlin.math.ceil(cd - 0.6f).toInt()
                    if (n != lastCount && n in 0..3) { lastCount = n; beep = 1f; beepF = if (n == 0) 1320f else 880f }
                } else lastCount = 99
                val targetF0 = 34f + rpm * 196f
                val engAmp = 0.16f + 0.22f * thr + 0.08f * rpm
                val windAmp = min(1f, spd / 80f).let { it * it } * 0.32f
                val screech = slide * 0.38f * min(1f, spd / 18f)
                val bright = 0.12f + 0.45f * thr
                val wantMaster = if (paused) 0f else vol
                for (i in 0 until block) {
                    t += dtS
                    master += (wantMaster - master) * 0.0008f
                    f0 += (targetF0 - f0) * 0.0015f
                    ph += f0 * dtS
                    if (ph > 1000f) ph -= 1000f
                    shiftDip = max(0f, shiftDip - dtS * 6f)
                    val nz = noise()
                    // Engine: harmonics of the firing, a sub rumble, and the rasp of each firing
                    var e = sn(ph) + 0.7f * sn(ph * 2f) + 0.5f * sn(ph * 3f) + 0.33f * sn(ph * 4f) + 0.22f * sn(ph * 5f) + 0.14f * sn(ph * 6f)
                    e += 0.4f * sn(ph * 0.5f)
                    e += nz * (0.25f + 0.75f * max(0f, sn(ph * 2f))) * 0.35f
                    eng += bright * (e - eng)
                    var s = eng * engAmp * 0.32f * (1f - 0.55f * shiftDip)
                    // Wind
                    wind += 0.04f * (nz - wind)
                    s += wind * windAmp * 2.2f
                    // Tyre screech: noise ringing around 1.5 kHz, wavering
                    if (screech > 0.01f) {
                        val fc = 1450f + 260f * sin(t * 9f)
                        val ff = 2f * sin(PI.toFloat() * fc / rate)
                        val hi = nz - sLow - 0.12f * sBand; sBand += ff * hi; sLow += ff * sBand
                        s += sBand * screech * 0.5f
                    }
                    // Scraping metal
                    if (scrape > 0.02f) {
                        val ff = 2f * sin(PI.toFloat() * 3100f / rate)
                        val hi = nz - cLow - 0.3f * cBand; cBand += ff * hi; cLow += ff * cBand
                        s += cBand * scrape * 0.3f
                    }
                    // Nitro whoosh
                    if (nitro) {
                        val ff = 2f * sin(PI.toFloat() * 700f / rate)
                        val hi = nz - nLow - 0.5f * nBand; nBand += ff * hi; nLow += ff * nBand
                        s += nBand * 0.35f
                    }
                    // Rain
                    if (rain > 0f) { rLow += 0.3f * (nz - rLow); s += (nz - rLow) * rain * 0.07f }
                    // Police siren: a rising and falling wail
                    if (sirenV > 0.01f) {
                        sirenT += dtS
                        val sf = 680f + 560f * (0.5f - 0.5f * kotlin.math.cos(sirenT * 2.4f))
                        sirenPh += sf * dtS
                        val w = sn(sirenPh)
                        s += max(-0.6f, min(0.6f, w * 2.2f)) * sirenV * sirenV * 0.28f
                    }
                    // Crash: a thump and a burst of noise
                    if (crash > 0.001f) {
                        crashPh += 55f * dtS
                        crashLow += 0.2f * (nz - crashLow)
                        s += (sn(crashPh) * 0.8f + crashLow * 1.4f) * crash * 0.6f
                        crash *= 1f - dtS * 7f
                    }
                    // Start beeps
                    if (beep > 0.001f) {
                        beepPh += beepF * dtS
                        s += (if (sn(beepPh) > 0f) 0.18f else -0.18f) * beep
                        beep = max(0f, beep - dtS * (if (beepF > 1000f) 2.2f else 6f))
                    }
                    s *= master
                    s = s / (1f + abs(s)) // soft limit
                    out[i] = (s * 30000f).toInt().coerceIn(-32767, 32767).toShort()
                }
                track.write(out, 0, block)
            }
        } catch (_: Throwable) {
        } finally {
            try { track.stop() } catch (_: Throwable) {}
            track.release()
        }
    }
}
