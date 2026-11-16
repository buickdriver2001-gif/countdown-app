package com.synthpad.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/**
 * The sound engine: 8-voice polyphonic synth plus an effects chain, streamed to the
 * speaker on its own high-priority thread.
 */
class SynthEngine(context: Context) {

    val sampleRate: Int = run {
        val am = context.getSystemService(AudioManager::class.java)
        am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
    }
    private val sr = sampleRate.toFloat()

    /** Knob values 0..1, written by the UI and read by the audio thread. */
    val params = FloatArray(P.COUNT) { P.ALL[it].def }

    private val events = ConcurrentLinkedQueue<Int>()
    @Volatile private var running = false
    private var thread: Thread? = null

    // Oscilloscope ring buffer
    val scope = FloatArray(4096)
    @Volatile var scopePos = 0

    @Volatile var recorder: Recorder? = null

    fun noteOn(note: Int) { events.add(note or 0x1000) }
    fun noteOff(note: Int) { events.add(note) }
    fun allNotesOff() { events.add(-1) }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ audioLoop() }, "synth-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        for (v in voices) v.kill()
    }

    // ------------------------------------------------------------------ audio thread

    private val block = 192

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuf, block * 2 * 4 * 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            running = false
            return
        }

        val out = FloatArray(block * 2)
        track.play()
        while (running) {
            render(out, block)
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        }
        track.pause()
        track.flush()
        track.release()
    }

    // ------------------------------------------------------------------ voices

    private inner class Voice {
        var note = -1
        var gate = false
        var stage = 0 // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release
        var env = 0f
        var age = 0L
        var freq = 440f
        val ph = FloatArray(5)
        var subPh = 0f
        var ic1 = 0f
        var ic2 = 0f

        val active get() = stage != 0

        fun start(n: Int, order: Long) {
            if (stage == 0) {
                ic1 = 0f; ic2 = 0f
                for (i in ph.indices) ph[i] = rand01()
                subPh = 0f
            }
            note = n
            freq = 440f * 2f.pow((n - 69) / 12f)
            gate = true
            stage = 1
            age = order
        }

        fun release() {
            gate = false
            if (stage != 0) stage = 4
        }

        fun kill() {
            stage = 0; env = 0f; note = -1; gate = false
        }
    }

    private val voices = Array(8) { Voice() }
    private var noteCounter = 0L

    private fun drainEvents() {
        while (true) {
            val e = events.poll() ?: break
            if (e == -1) {
                for (v in voices) v.release()
                continue
            }
            val note = e and 0xFFF
            if (e and 0x1000 != 0) {
                val v = voices.firstOrNull { it.note == note && it.active }
                    ?: voices.firstOrNull { !it.active }
                    ?: voices.filter { !it.gate }.minByOrNull { it.age }
                    ?: voices.minByOrNull { it.age }!!
                v.start(note, noteCounter++)
            } else {
                for (v in voices) if (v.note == note && v.gate) v.release()
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private var seed = 22222
    private fun rand01(): Float {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 17)
        seed = seed xor (seed shl 5)
        return (seed ushr 8) / 16777216f
    }

    private fun blep(t: Float, dt: Float): Float = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1f }
        t > 1f - dt -> { val x = (t - 1f) / dt; x * x + x + x + 1f }
        else -> 0f
    }

    private fun saw(p: Float, dt: Float) = 2f * p - 1f - blep(p, dt)

    private fun square(p: Float, dt: Float): Float {
        var s = if (p < 0.5f) 1f else -1f
        s += blep(p, dt)
        var p2 = p + 0.5f
        if (p2 >= 1f) p2 -= 1f
        s -= blep(p2, dt)
        return s
    }

    private val twoPi = (2 * PI).toFloat()
    private val superSpread = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)

    // ------------------------------------------------------------------ effects state

    private var lfoPh = 0f
    private var vibPh = 0f
    private var gatePh = 0f
    private var gateGain = 1f
    private var panPh = 0f
    private var crushCount = 0
    private var crushHold = 0f
    private var volSmooth = 0.5f

    private val chorusLen = (sr * 0.05f).toInt() + 2
    private val chorusBuf = FloatArray(chorusLen)
    private var chorusW = 0
    private var chorusPh = 0f

    private val delayLen = (sr * 1.1f).toInt() + 2
    private val delayL = FloatArray(delayLen)
    private val delayR = FloatArray(delayLen)
    private var delayW = 0
    private var delaySmooth = sr * 0.35f

    // Freeverb-style reverb
    private val combTuning = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491)
    private val apTuning = intArrayOf(556, 441)
    private val scaleSr = sr / 44100f
    private val combL = Array(combTuning.size) { FloatArray((combTuning[it] * scaleSr).toInt() + 1) }
    private val combR = Array(combTuning.size) { FloatArray(((combTuning[it] + 23) * scaleSr).toInt() + 1) }
    private val combIdxL = IntArray(combTuning.size)
    private val combIdxR = IntArray(combTuning.size)
    private val combStoreL = FloatArray(combTuning.size)
    private val combStoreR = FloatArray(combTuning.size)
    private val apL = Array(apTuning.size) { FloatArray((apTuning[it] * scaleSr).toInt() + 1) }
    private val apR = Array(apTuning.size) { FloatArray(((apTuning[it] + 23) * scaleSr).toInt() + 1) }
    private val apIdxL = IntArray(apTuning.size)
    private val apIdxR = IntArray(apTuning.size)

    private val chunk = 32
    private val mono = FloatArray(chunk)

    // ------------------------------------------------------------------ render

    private fun render(out: FloatArray, frames: Int) {
        drainEvents()
        val p = params

        val wave = ParamMath.waveIndex(p[P.WAVE.ordinal])
        val detuneCents = p[P.DETUNE.ordinal] * 50f
        val subLvl = p[P.SUB.ordinal]
        val noiseLvl = p[P.NOISE.ordinal] * 0.5f
        val vibDepth = p[P.VIBRATO.ordinal] * 0.6f // semitones

        val atkInc = 1f / (ParamMath.attackSec(p[P.ATTACK.ordinal]) * sr)
        val decCoef = 1f - exp(-4f / (ParamMath.decaySec(p[P.DECAY.ordinal]) * sr))
        val sustain = p[P.SUSTAIN.ordinal]
        val relCoef = 1f - exp(-4f / (ParamMath.releaseSec(p[P.RELEASE.ordinal]) * sr))

        val cutoffBase = ParamMath.cutoffHz(p[P.CUTOFF.ordinal])
        val k = 2f - 1.94f * p[P.RESO.ordinal]
        val envOct = p[P.ENV_AMT.ordinal] * 6f
        val lfoOct = p[P.LFO_DEPTH.ordinal] * 4f
        val lfoInc = ParamMath.lfoHz(p[P.LFO_RATE.ordinal]) / sr

        val gateDepth = p[P.GATE_DEPTH.ordinal]
        val gateInc = ParamMath.gateHz(p[P.GATE_RATE.ordinal]) / sr
        val panBase = p[P.PAN.ordinal] * 2f - 1f
        val apanDepth = p[P.APAN_DEPTH.ordinal]
        val apanInc = ParamMath.panHz(p[P.APAN_RATE.ordinal]) / sr

        val drive = p[P.DRIVE.ordinal]
        val driveG = 1f + drive * 24f
        val driveNorm = 1f / sqrt(driveG)
        val crush = p[P.CRUSH.ordinal]
        val crushLevels = 2f.pow(16f - crush * 13f)
        val crushFactor = 1 + (crush * 15f).roundToInt()
        val chorusMix = p[P.CHORUS.ordinal] * 0.6f

        val delayMix = p[P.DELAY_MIX.ordinal] * 0.7f
        val delayTarget = ParamMath.delaySec(p[P.DELAY_TIME.ordinal]) * sr
        val delayFb = p[P.DELAY_FB.ordinal] * 0.9f
        val revMix = p[P.REV_MIX.ordinal] * 0.6f
        val revFb = 0.7f + p[P.REV_SIZE.ordinal] * 0.28f
        val revDamp = 0.3f

        val volTarget = p[P.VOLUME.ordinal].let { it * it * 1.6f }

        var start = 0
        while (start < frames) {
            val n = min(chunk, frames - start)

            // Per-chunk modulation
            lfoPh += lfoInc * n; if (lfoPh >= 1f) lfoPh -= 1f
            val lfo = sin(twoPi * lfoPh)
            vibPh += 5.5f / sr * n; if (vibPh >= 1f) vibPh -= 1f
            val pitchMul = if (vibDepth > 0f) 2f.pow(vibDepth * sin(twoPi * vibPh) / 12f) else 1f
            panPh += apanInc * n; if (panPh >= 1f) panPh -= 1f
            val pan = (panBase + apanDepth * sin(twoPi * panPh)).coerceIn(-1f, 1f)
            val theta = (pan + 1f) * (PI.toFloat() / 4f)
            val gl = cos(theta) * 1.4142f
            val gr = sin(theta) * 1.4142f

            for (i in 0 until n) mono[i] = 0f

            for (v in voices) {
                if (!v.active) continue
                val f = v.freq * pitchMul
                val dt = (f / sr).coerceAtMost(0.45f)
                val cutoff = (cutoffBase * 2f.pow(envOct * v.env + lfoOct * lfo))
                    .coerceIn(20f, sr * 0.45f)
                val g = tan(PI.toFloat() * cutoff / sr)
                val a1 = 1f / (1f + g * (g + k))
                val a2 = g * a1
                val a3 = g * a2
                val det = 2f.pow(detuneCents / 1200f)

                for (i in 0 until n) {
                    // Envelope
                    when (v.stage) {
                        1 -> { v.env += atkInc; if (v.env >= 1f) { v.env = 1f; v.stage = 2 } }
                        2 -> {
                            v.env += (sustain - v.env) * decCoef
                            if (abs(v.env - sustain) < 0.0005f) { v.env = sustain; v.stage = 3 }
                        }
                        3 -> v.env = sustain
                        4 -> { v.env -= v.env * relCoef; if (v.env < 0.0001f) { v.kill() } }
                    }
                    if (v.stage == 0) break

                    // Oscillators
                    var s: Float
                    if (wave == 4) {
                        s = 0f
                        val spread = max(detuneCents, 8f)
                        for (j in 0 until 5) {
                            val dtj = dt * 2f.pow(superSpread[j] * spread / 1200f)
                            s += saw(v.ph[j], dtj)
                            v.ph[j] += dtj; if (v.ph[j] >= 1f) v.ph[j] -= 1f
                        }
                        s *= 0.33f
                    } else {
                        val dt2 = dt * det
                        val o1 = osc(wave, v.ph[0], dt)
                        v.ph[0] += dt; if (v.ph[0] >= 1f) v.ph[0] -= 1f
                        s = if (detuneCents > 0.5f) {
                            val o2 = osc(wave, v.ph[1], dt2)
                            v.ph[1] += dt2; if (v.ph[1] >= 1f) v.ph[1] -= 1f
                            (o1 + o2) * 0.6f
                        } else o1
                    }
                    if (subLvl > 0f) {
                        s += sin(twoPi * v.subPh) * subLvl
                        v.subPh += dt * 0.5f; if (v.subPh >= 1f) v.subPh -= 1f
                    }
                    if (noiseLvl > 0f) s += (rand01() * 2f - 1f) * noiseLvl

                    // Filter (state-variable, low-pass)
                    val v3 = s - v.ic2
                    val v1 = a1 * v.ic1 + a2 * v3
                    val v2 = v.ic2 + a2 * v.ic1 + a3 * v3
                    v.ic1 = 2f * v1 - v.ic1
                    v.ic2 = 2f * v2 - v.ic2

                    mono[i] += v2 * v.env * 0.2f
                }
            }

            // Effects, per sample
            for (i in 0 until n) {
                var x = mono[i]

                if (drive > 0.001f) x = tanh(x * driveG) * driveNorm

                if (crush > 0.001f) {
                    if (++crushCount >= crushFactor) {
                        crushCount = 0
                        crushHold = (x * crushLevels).roundToInt() / crushLevels
                    }
                    x = crushHold
                }

                gatePh += gateInc; if (gatePh >= 1f) gatePh -= 1f
                val gTarget = if (gatePh < 0.5f) 1f else 1f - gateDepth
                gateGain += (gTarget - gateGain) * 0.008f
                x *= gateGain

                // Chorus: two modulated taps, one per side
                var l = x
                var r = x
                if (chorusMix > 0.001f) {
                    chorusBuf[chorusW] = x
                    chorusPh += 0.8f / sr; if (chorusPh >= 1f) chorusPh -= 1f
                    val m = sin(twoPi * chorusPh)
                    val dl = readFrac(chorusBuf, chorusW, (0.015f + 0.006f * m) * sr, chorusLen)
                    val dr = readFrac(chorusBuf, chorusW, (0.015f - 0.006f * m) * sr, chorusLen)
                    l = x * (1f - chorusMix * 0.5f) + dl * chorusMix
                    r = x * (1f - chorusMix * 0.5f) + dr * chorusMix
                    chorusW++; if (chorusW >= chorusLen) chorusW = 0
                }

                // Pan
                l *= gl
                r *= gr

                // Ping-pong echo
                delaySmooth += (delayTarget - delaySmooth) * 0.0005f
                val el = readFrac(delayL, delayW, delaySmooth, delayLen)
                val er = readFrac(delayR, delayW, delaySmooth, delayLen)
                delayL[delayW] = (l + r) * 0.5f * (if (delayMix > 0.001f) 1f else 0f) + er * delayFb
                delayR[delayW] = el * delayFb
                delayW++; if (delayW >= delayLen) delayW = 0
                l += el * delayMix
                r += er * delayMix

                // Reverb
                if (revMix > 0.001f) {
                    val input = (l + r) * 0.015f
                    var rl = 0f
                    var rr = 0f
                    for (c in combL.indices) {
                        val bl = combL[c]; val il = combIdxL[c]
                        val ol = bl[il]
                        combStoreL[c] = ol * (1f - revDamp) + combStoreL[c] * revDamp
                        bl[il] = input + combStoreL[c] * revFb
                        combIdxL[c] = if (il + 1 >= bl.size) 0 else il + 1
                        rl += ol

                        val br = combR[c]; val ir = combIdxR[c]
                        val or = br[ir]
                        combStoreR[c] = or * (1f - revDamp) + combStoreR[c] * revDamp
                        br[ir] = input + combStoreR[c] * revFb
                        combIdxR[c] = if (ir + 1 >= br.size) 0 else ir + 1
                        rr += or
                    }
                    for (a in apL.indices) {
                        val bl = apL[a]; val il = apIdxL[a]
                        val ol = bl[il]
                        bl[il] = rl + ol * 0.5f
                        rl = ol - rl
                        apIdxL[a] = if (il + 1 >= bl.size) 0 else il + 1

                        val br = apR[a]; val ir = apIdxR[a]
                        val or = br[ir]
                        br[ir] = rr + or * 0.5f
                        rr = or - rr
                        apIdxR[a] = if (ir + 1 >= br.size) 0 else ir + 1
                    }
                    l += rl * revMix * 3f
                    r += rr * revMix * 3f
                }

                // Volume + soft limiter
                volSmooth += (volTarget - volSmooth) * 0.002f
                l = limit(l * volSmooth)
                r = limit(r * volSmooth)

                val o = (start + i) * 2
                out[o] = l
                out[o + 1] = r

                val sp = scopePos
                scope[sp] = (l + r) * 0.5f
                scopePos = if (sp + 1 >= scope.size) 0 else sp + 1
            }
            start += n
        }

        recorder?.write(out, frames)
    }

    private fun osc(wave: Int, p: Float, dt: Float): Float = when (wave) {
        0 -> sin(twoPi * p)
        1 -> 1f - 4f * abs(p - 0.5f)
        2 -> saw(p, dt)
        else -> square(p, dt)
    }

    private fun readFrac(buf: FloatArray, w: Int, delay: Float, len: Int): Float {
        var pos = w - delay
        while (pos < 0f) pos += len
        val i0 = pos.toInt()
        val frac = pos - i0
        val i1 = if (i0 + 1 >= len) 0 else i0 + 1
        return buf[i0 % len] * (1f - frac) + buf[i1] * frac
    }

    private fun limit(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }
}
