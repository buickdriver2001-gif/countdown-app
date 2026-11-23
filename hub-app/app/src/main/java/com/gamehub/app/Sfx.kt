package com.gamehub.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Menu sounds, generated in code: a soft glassy tink for moving, a rising two-note chime for opening, both ringing
 * out briefly as if in a big dark space. They play on a thread of their own (never holding up the screen), and
 * quick ticks overlap instead of cutting each other off.
 */
class Sfx {
    private val rate = 22050
    @Volatile private var ticks: Array<AudioTrack?> = emptyArray()
    private var nextTick = 0
    @Volatile private var confirm: AudioTrack? = null
    private val thread = android.os.HandlerThread("nexus-sounds").apply { start() }
    private val h = android.os.Handler(thread.looper)

    init {
        h.post {
            val data = buildTick()
            ticks = Array(4) { make(data) }
            confirm = make(buildConfirm())
        }
    }

    /**
     * Moving between things: a soft glassy "tink" (a bell-like tone with the brighter overtones of glass and a breath
     * of air) that rings out briefly in a big dark space, in the spirit of an old console's menus (an original sound).
     */
    private fun buildTick(): ShortArray {
        val len = 0.5f
        val n = (rate * len).toInt()
        val r = Random(5)
        val dry = FloatArray(n)
        var hp = 0f; var px = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            val att = (t / 0.0015f).coerceAtMost(1f)
            val f = 1720f
            val body = sin(2 * PI * f * t).toFloat() * exp(-t * 48f) * 0.55f
            val glass = sin(2 * PI * f * 2.76f * t).toFloat() * exp(-t * 95f) * 0.22f +
                sin(2 * PI * f * 5.40f * t).toFloat() * exp(-t * 160f) * 0.08f
            val nz = r.nextFloat() * 2f - 1f
            hp = 0.7f * hp + nz - px; px = nz
            val air = hp * exp(-t * 260f) * 0.10f
            dry[i] = (body + glass + air) * att
        }
        return finish(space(dry, 0.70f, 0.4f, 0.36f), len, 0.08f, 0.45f)
    }

    /** Opening something: two soft glass notes a beat apart, rising, ringing out in the same space. */
    private fun buildConfirm(): ShortArray {
        val len = 1.0f
        val n = (rate * len).toInt()
        val dry = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            var v = 0f
            for ((start, f, amp) in listOf(Triple(0f, 1318.5f, 0.5f), Triple(0.075f, 1975.5f, 0.45f))) {
                val tt = t - start
                if (tt < 0f) continue
                val env = (tt / 0.004f).coerceAtMost(1f) * exp(-tt * 6.5f)
                v += (sin(2 * PI * f * tt).toFloat() * 0.55f + sin(2 * PI * f * 2.76f * tt).toFloat() * 0.16f * exp(-tt * 14f) +
                    sin(2 * PI * f * 2f * tt).toFloat() * 0.12f) * env * amp
            }
            dry[i] = v
        }
        return finish(space(dry, 0.76f, 0.4f, 0.42f), len, 0.12f, 0.38f)
    }

    /**
     * A small, dark-sounding room: four echoing delays (each softening as it repeats) and two diffusers, mixed in
     * with the dry sound. [fb] how long it rings, [damp] how quickly the echoes lose their brightness.
     */
    private fun space(x: FloatArray, fb: Float, damp: Float, mix: Float): FloatArray {
        val wet = FloatArray(x.size)
        for (ms in floatArrayOf(29.7f, 37.1f, 41.1f, 43.7f)) {
            val buf = FloatArray((rate * ms / 1000f).toInt()); var idx = 0; var lp = 0f
            for (i in x.indices) {
                val o = buf[idx]; lp = o * (1f - damp) + lp * damp
                buf[idx] = x[i] + lp * fb; idx = (idx + 1) % buf.size
                wet[i] += o / 4f
            }
        }
        for (ms in floatArrayOf(5.0f, 1.7f)) {
            val buf = FloatArray((rate * ms / 1000f).toInt()); var idx = 0
            for (i in wet.indices) {
                val b = buf[idx]; val v = wet[i] + b * 0.5f
                wet[i] = b - v * 0.5f; buf[idx] = v; idx = (idx + 1) % buf.size
            }
        }
        return FloatArray(x.size) { i -> x[i] * (1f - mix * 0.5f) + wet[i] * mix * 2.2f }
    }

    /** Fades the very end out over [fadeS], scales the loudest point to [peak] and turns it into 16-bit sound. */
    private fun finish(y: FloatArray, len: Float, fadeS: Float, peak: Float): ShortArray {
        var mx = 1e-6f
        for (i in y.indices) {
            val t = i.toFloat() / rate
            y[i] *= ((len - t) / fadeS).coerceIn(0f, 1f)
            mx = maxOf(mx, kotlin.math.abs(y[i]))
        }
        val k = peak / mx
        return ShortArray(y.size) { i -> (y[i] * k * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
    }

    private fun make(data: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().also { it.write(data, 0, data.size) }
    } catch (_: Exception) { null }

    private fun play(t: AudioTrack?) {
        try {
            t ?: return
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.play()
        } catch (_: Exception) {}
    }

    fun tick() {
        if (!on) return
        h.post {
            val pool = ticks
            if (pool.isEmpty()) return@post
            nextTick = (nextTick + 1) % pool.size
            play(pool[nextTick])
        }
    }
    fun confirm() { if (on) h.post { play(confirm) } }

    companion object {
        /** Menu sounds on (Settings → Sound). */
        @Volatile var on = true
    }

    fun release() { h.post { ticks.forEach { it?.release() }; confirm?.release(); thread.quitSafely() } }
}
