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

    /** Metronome click on each beat (not recorded). */
    @Volatile var metronome = false
    private var beatPh = 0f
    private var beatCount = 0
    private var clickT = -1f
    private var clickAccent = false

    /** Called (on the audio thread) if the sound engine hits an error. */
    @Volatile var onError: ((Throwable) -> Unit)? = null

    fun noteOn(note: Int) { events.add(note or 0x1000) }
    fun noteOff(note: Int) { events.add(note) }
    fun allNotesOff() { events.add(-1) }

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                audioLoop()
            } catch (t: Throwable) {
                running = false
                onError?.invoke(t)
            }
        }, "synth-audio").also { it.start() }
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
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (e: Exception) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val track = buildTrack(minBuf, true) ?: buildTrack(minBuf, false) ?: run {
            running = false
            throw IllegalStateException("This phone wouldn't open an audio output")
        }

        val out = FloatArray(block * 2)
        track.play()
        while (running) {
            render(out, block)
            track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
        }
        try { track.pause(); track.flush() } catch (_: Exception) {}
        track.release()
    }

    private fun buildTrack(minBuf: Int, lowLatency: Boolean): AudioTrack? {
        return try {
            val builder = AudioTrack.Builder()
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
                .setBufferSizeInBytes(max(minBuf, block * 2 * 4 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
            if (lowLatency) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            val t = builder.build()
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else t
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------ voices

    private inner class Voice {
        var note = -1
        var gate = false
        var stage = 0 // 0 idle, 1 attack, 2 decay, 3 sustain, 4 release
        var env = 0f
        var age = 0L
        var freq = 440f
        var curFreq = 440f
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
            // Glide: slide in from the last note played
            curFreq = if (glideOn && lastFreq > 0f) lastFreq else freq
            lastFreq = freq
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
    private var glideOn = false
    private var lastFreq = 0f

    // Arpeggiator
    private val held = ArrayList<Int>()
    private var arpOn = false
    private var arpTimer = 0f
    private var arpStep = 0
    private var arpNote = -1
    private var arpDir = 1

    private fun startNote(note: Int) {
        val v = voices.firstOrNull { it.note == note && it.active }
            ?: voices.firstOrNull { !it.active }
            ?: voices.filter { !it.gate }.minByOrNull { it.age }
            ?: voices.minByOrNull { it.age }!!
        v.start(note, noteCounter++)
    }

    private fun stopNote(note: Int) {
        for (v in voices) if (v.note == note && v.gate) v.release()
    }

    private fun drainEvents() {
        while (true) {
            val e = events.poll() ?: break
            if (e == -1) {
                for (v in voices) v.release()
                held.clear()
                arpNote = -1
                continue
            }
            val note = e and 0xFFF
            if (e and 0x1000 != 0) {
                if (!held.contains(note)) { held.add(note); held.sort() }
                if (!arpOn) startNote(note)
                else if (held.size == 1) { arpTimer = 0f; arpStep = 0; arpDir = 1 } // start the pattern right away
            } else {
                held.remove(note)
                if (!arpOn) stopNote(note)
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

    private var ringPh = 0f
    private var phaserPh = 0f
    private var phaserFb = 0f
    private val apZ = FloatArray(4)
    private var toneL = 0f
    private var toneR = 0f
    private var lowL = 0f
    private var lowR = 0f
    private var hiL = 0f
    private var hiR = 0f
    private val eqLowCoef = 1f - exp(-2f * PI.toFloat() * 250f / sr)
    private val eqHighCoef = 1f - exp(-2f * PI.toFloat() * 3500f / sr)

    private val chunk = 32
    private val mono = FloatArray(chunk)

    // ------------------------------------------------------------------ render

    private fun render(out: FloatArray, frames: Int) {
        val p = params
        glideOn = p[P.GLIDE.ordinal] > 0.01f
        val arpMode = ParamMath.arpMode(p[P.ARP.ordinal])
        if (arpMode == 0 && arpOn) { arpOn = false; if (arpNote >= 0) stopNote(arpNote); arpNote = -1 }
        if (arpMode != 0 && !arpOn) { arpOn = true; for (v in voices) v.release(); arpTimer = 0f }
        drainEvents()

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

        val volTarget = p[P.VOLUME.ordinal].let { it * it * 1.6f }

        val octMul = 2f.pow(ParamMath.octaves(p[P.PITCH.ordinal]).toFloat())
        val glideSec = ParamMath.glideSec(p[P.GLIDE.ordinal])
        val filterType = ParamMath.filterType(p[P.FILTER_TYPE.ordinal])
        val phaserMix = p[P.PHASER.ordinal]
        val phaserInc = ParamMath.phaserHz(p[P.PHASER_RATE.ordinal]) / sr
        val ringMix = p[P.RING.ordinal]
        val ringInc = ParamMath.ringHz(p[P.RING_FREQ.ordinal]) / sr
        val toneCoef = 1f - exp(-2f * PI.toFloat() * min(ParamMath.delayToneHz(p[P.DELAY_TONE.ordinal]), sr * 0.45f) / sr)
        val revDampV = 0.05f + p[P.REV_DAMP.ordinal] * 0.7f
        val bassGain = 10f.pow(ParamMath.eqDb(p[P.BASS.ordinal]) / 20f) - 1f
        val trebleGain = 10f.pow(ParamMath.eqDb(p[P.TREBLE.ordinal]) / 20f) - 1f
        val width = p[P.WIDTH.ordinal] * 2f
        val arpRate = ParamMath.arpRate(p[P.ARP_RATE.ordinal])
        val arpOct = ParamMath.arpOct(p[P.ARP_OCT.ordinal])

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

            // Arpeggiator clock
            if (arpOn) {
                arpTimer -= n / sr
                if (held.isEmpty()) {
                    if (arpNote >= 0) { stopNote(arpNote); arpNote = -1 }
                } else if (arpTimer <= 0f) {
                    arpTimer += 1f / arpRate
                    if (arpTimer < 0f) arpTimer = 1f / arpRate
                    val seq = ArrayList<Int>()
                    for (o in 0 until arpOct) for (hn in held) { val nn = hn + 12 * o; if (nn < 128) seq.add(nn) }
                    if (arpNote >= 0) stopNote(arpNote)
                    val next = when (arpMode) {
                        2 -> seq[(seq.size - 1 - arpStep.mod(seq.size))]
                        3 -> {
                            if (seq.size == 1) seq[0] else {
                                val period = (seq.size - 1) * 2
                                val pos = arpStep.mod(period)
                                seq[if (pos < seq.size) pos else period - pos]
                            }
                        }
                        4 -> seq[(rand01() * seq.size).toInt().coerceIn(0, seq.size - 1)]
                        else -> seq[arpStep.mod(seq.size)]
                    }
                    arpStep++
                    startNote(next)
                    arpNote = next
                }
            }

            for (i in 0 until n) mono[i] = 0f
            // Phaser sweep position for this chunk
            phaserPh += phaserInc * n; if (phaserPh >= 1f) phaserPh -= 1f
            val sweepHz = 250f * 8f.pow(0.5f + 0.5f * sin(twoPi * phaserPh))
            val tw = tan(PI.toFloat() * min(sweepHz, sr * 0.45f) / sr)
            val apA = (tw - 1f) / (tw + 1f)

            for (v in voices) {
                if (!v.active) continue
                if (glideSec > 0.001f && v.curFreq != v.freq) {
                    v.curFreq += (v.freq - v.curFreq) * (1f - exp(-n / (glideSec * sr)))
                    if (abs(v.curFreq - v.freq) < 0.01f) v.curFreq = v.freq
                } else v.curFreq = v.freq
                val f = v.curFreq * octMul * pitchMul
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

                    val filtered = when (filterType) {
                        1 -> v1 * 1.6f                 // band-pass
                        2 -> s - k * v1 - v2           // high-pass
                        else -> v2                     // low-pass
                    }
                    mono[i] += filtered * v.env * 0.2f
                }
            }

            // Effects, per sample
            for (i in 0 until n) {
                var x = mono[i]

                // Ring modulator: metallic / robotic
                if (ringMix > 0.001f) {
                    ringPh += ringInc; if (ringPh >= 1f) ringPh -= 1f
                    x = x * (1f - ringMix) + x * sin(twoPi * ringPh) * ringMix * 1.4f
                }

                // Phaser: 4 sweeping all-pass stages mixed back in
                if (phaserMix > 0.001f) {
                    var y = x + phaserFb * 0.5f
                    for (st in 0 until 4) {
                        val o = apA * y + apZ[st]
                        apZ[st] = y - apA * o
                        y = o
                    }
                    phaserFb = y
                    x = x * (1f - phaserMix * 0.5f) + y * phaserMix * 0.7f
                }

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
                toneL += (er * delayFb - toneL) * toneCoef
                toneR += (el * delayFb - toneR) * toneCoef
                delayL[delayW] = (l + r) * 0.5f * (if (delayMix > 0.001f) 1f else 0f) + toneL
                delayR[delayW] = toneR
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
                        combStoreL[c] = ol * (1f - revDampV) + combStoreL[c] * revDampV
                        bl[il] = input + combStoreL[c] * revFb
                        combIdxL[c] = if (il + 1 >= bl.size) 0 else il + 1
                        rl += ol

                        val br = combR[c]; val ir = combIdxR[c]
                        val or = br[ir]
                        combStoreR[c] = or * (1f - revDampV) + combStoreR[c] * revDampV
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

                // EQ: bass and treble shelves
                if (abs(bassGain) > 0.01f || abs(trebleGain) > 0.01f) {
                    lowL += (l - lowL) * eqLowCoef; lowR += (r - lowR) * eqLowCoef
                    l += lowL * bassGain; r += lowR * bassGain
                    hiL += (l - hiL) * eqHighCoef; hiR += (r - hiR) * eqHighCoef
                    l += (l - hiL) * trebleGain; r += (r - hiR) * trebleGain
                }
                // Stereo width
                if (abs(width - 1f) > 0.01f) {
                    val m = (l + r) * 0.5f
                    val sd = (l - r) * 0.5f * width
                    l = m + sd; r = m - sd
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

        // Metronome is added after recording, so it never ends up in saved songs
        val beatInc = ParamMath.bpm / 60f / sr
        for (i in 0 until frames) {
            beatPh += beatInc
            if (beatPh >= 1f) {
                beatPh -= 1f
                beatCount = (beatCount + 1) % 4
                if (metronome) { clickT = 0f; clickAccent = beatCount == 0 }
            }
            if (clickT >= 0f) {
                val env = exp(-clickT * 90f)
                val c = sin(twoPi * (if (clickAccent) 2000f else 1400f) * clickT) * env * (if (clickAccent) 0.35f else 0.22f)
                out[i * 2] += c
                out[i * 2 + 1] += c
                clickT += 1f / sr
                if (clickT > 0.06f) clickT = -1f
            }
        }
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
