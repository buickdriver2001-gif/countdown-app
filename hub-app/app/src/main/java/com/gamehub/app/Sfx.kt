package com.gamehub.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Menu sounds, generated in code: a soft glassy tick for moving, a rising chime for opening. They play on a
 * thread of their own (never holding up the screen), and quick ticks overlap instead of cutting each other off.
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
            ticks = Array(3) { make(data) }
            confirm = make(buildConfirm())
        }
    }

    private fun buildTick(): ShortArray {
        val n = (rate * 0.09f).toInt()
        val r = Random(5)
        return ShortArray(n) { i ->
            val t = i.toFloat() / rate
            val f = 2100f - 900f * (t / 0.09f)
            val v = sin(2 * PI * f * t).toFloat() * exp(-t * 55f) * 0.6f +
                sin(2 * PI * 4200 * t).toFloat() * exp(-t * 120f) * 0.15f +
                (r.nextFloat() - 0.5f) * exp(-t * 400f) * 0.25f
            (v * 0.5f * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    private fun buildConfirm(): ShortArray {
        val len = 0.55f
        val n = (rate * len).toInt()
        return ShortArray(n) { i ->
            val t = i.toFloat() / rate
            // Two notes a beat apart, each with a shimmer an octave up, and a soft airy tail
            var v = 0f
            for ((start, f) in listOf(0f to 660f, 0.07f to 990f)) {
                val tt = t - start
                if (tt < 0f) continue
                val env = (tt / 0.008f).coerceAtMost(1f) * exp(-tt * 7f)
                v += (sin(2 * PI * f * tt).toFloat() * 0.5f + sin(2 * PI * f * 2.003 * tt).toFloat() * 0.18f) * env
            }
            (v * 0.42f * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
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
        h.post {
            val pool = ticks
            if (pool.isEmpty()) return@post
            nextTick = (nextTick + 1) % pool.size
            play(pool[nextTick])
        }
    }
    fun confirm() { h.post { play(confirm) } }

    fun release() { h.post { ticks.forEach { it?.release() }; confirm?.release(); thread.quitSafely() } }
}
