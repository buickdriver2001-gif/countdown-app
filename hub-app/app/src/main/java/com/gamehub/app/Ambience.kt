package com.gamehub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The home screen's background sound: a deep, slowly breathing drone with a soft low wash swelling under it,
 * in the spirit of an old console menu (made up on the phone from scratch: no recordings). It loops seamlessly
 * and costs almost nothing to play (the phone loops it by itself).
 *
 * Considerate by design: quiet, on the media volume, only while the NEXUS home screen is in front, and silent
 * while music or a video is playing, during calls, alarms and ringing, and when the phone is on silent or
 * vibrate. Settings → Sound switches it off.
 */
class Ambience(private val c: Context) {
    private val h = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var vol = 0f
    private var target = 0f
    /** The home screen wants it playing. */
    private var wanted = false
    private var building = false
    private var others = 0
    private var listening = false
    private val audio get() = c.getSystemService(AudioManager::class.java)

    /** The home screen is in front: fade in (if it's switched on and nothing else is sounding). */
    fun start() {
        wanted = true
        if (!enabled(c) || !quietNow()) return
        val data = pcm
        if (data == null) {
            if (!building) {
                building = true
                Bg.thread {
                    val made = try { synth() } catch (_: Throwable) { null }
                    h.post { building = false; if (made != null) { pcm = made; if (wanted) start() } }
                }.start()
            }
            return
        }
        val t = track ?: make(data)?.also { track = it } ?: return
        others = otherPlayers()
        listen(true)
        try { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() } catch (_: Throwable) { return }
        fadeTo(GAIN)
    }

    /** Leaving the home screen: fade out (and stop). */
    fun stop() {
        wanted = false
        listen(false)
        fadeTo(0f)
    }

    /** Stops at once and lets go of the sound. */
    fun release() {
        wanted = false
        listen(false)
        h.removeCallbacks(fader)
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    /** Nothing else to be considerate of: not silent/vibrate, no call, no music or video playing. */
    private fun quietNow(): Boolean {
        val am = audio ?: return false
        return am.ringerMode == AudioManager.RINGER_MODE_NORMAL && am.mode == AudioManager.MODE_NORMAL && !am.isMusicActive
    }

    // ------------------------------------------------------------------ stepping aside for other sounds

    /** How many players of the kinds that should silence it (music, video, calls, alarms, ringing...). */
    private fun otherPlayers(): Int = try {
        audio?.activePlaybackConfigurations?.count { quietFor(it) } ?: 0
    } catch (_: Throwable) { 0 }

    private fun quietFor(p: AudioPlaybackConfiguration): Boolean = when (p.audioAttributes.usage) {
        AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_ALARM, AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
        AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioAttributes.USAGE_ASSISTANT,
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> true
        else -> false
    }

    /** Android lists players that are only set up as well, so it's a new one appearing that counts. */
    private val watcher = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val n = configs?.count { quietFor(it) } ?: 0
            if (n > others) { listen(false); fadeTo(0f) } // something else started: step aside (until next time)
            else others = n
        }
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        try { if (on) audio?.registerAudioPlaybackCallback(watcher, h) else audio?.unregisterAudioPlaybackCallback(watcher) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ fading

    private fun fadeTo(v: Float) {
        target = v
        h.removeCallbacks(fader); h.post(fader)
    }

    private val fader = object : Runnable {
        override fun run() {
            val t = track ?: return
            val step = if (target > vol) GAIN / 30f else GAIN / 14f // about 0.9 s in, 0.4 s out
            vol = if (abs(target - vol) <= step) target else vol + if (target > vol) step else -step
            try { t.setVolume(vol) } catch (_: Throwable) {}
            if (vol != target) { h.postDelayed(this, 30); return }
            if (vol == 0f) try { t.pause() } catch (_: Throwable) {}
        }
    }

    private fun make(data: ShortArray): AudioTrack? = try {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(data.size * 2)
            .build()
        t.write(data, 0, data.size)
        t.setLoopPoints(0, data.size, -1)
        t.setVolume(0f); vol = 0f
        t
    } catch (_: Throwable) { null }

    companion object {
        private const val RATE = 22050
        private const val LOOP_S = 16
        /** How loud, at most (of the media volume). */
        private const val GAIN = 0.42f
        @Volatile private var pcm: ShortArray? = null

        fun enabled(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("ambience", true)
        fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("ambience", on).apply()

        /**
         * Makes the loop: a deep drone (a low tone of 55 Hz with its harmonics, played twice a quarter of a
         * hertz apart so it slowly beats and breathes), a soft pad an octave and a fifth above it, and a gentle
         * low wash of filtered noise that swells and recedes twice a loop. Every movement repeats exactly
         * every 16 s and the end is blended into the start, so it loops without a seam.
         */
        fun synth(): ShortArray {
            val n = RATE * LOOP_S
            val x = RATE * 2
            val out = FloatArray(n + x)
            val rnd = java.util.Random(7)
            val lut = FloatArray(8192) { sin(it * 2.0 * PI / 8192).toFloat() }
            // A sine by cycles (not radians): one table look-up
            fun sn(cycles: Double): Float { val f = cycles - kotlin.math.floor(cycles); return lut[(f * 8192).toInt() and 8191] }
            val harm = FloatArray(9) { if (it == 0) 0f else 1f / it.toFloat().pow(1.25f) }
            var lp = 0f; var lp2 = 0f; var brown = 0f
            val tau = 2.0 * PI
            for (i in 0 until n + x) {
                val t = i.toDouble() / RATE
                val ph = (t % LOOP_S) / LOOP_S
                // The drone
                var drone = 0f
                for (k in 1..8) drone += harm[k] * (sn(k * 55.0 * t + k * 0.11) + sn(k * 55.25 * t + k * 0.3)) * 0.5f
                val trem = (0.8 + 0.2 * sin(tau * ph * 2 + 0.3)).toFloat()
                // The pad
                val pad = 0.4f * (sn(110.0 * t) + sn(110.25 * t + 0.16)) * 0.5f + 0.28f * (sn(165.0 * t + 0.06) + sn(165.25 * t + 0.35)) * 0.5f
                // The wash: noise through a soft low-pass that opens (to ~650 Hz) and closes slowly
                val w = rnd.nextFloat() * 2f - 1f
                val cut = 250.0 + 400.0 * (0.5 - 0.5 * cos(tau * ph * 2))
                val a = (1.0 - exp(-tau * cut / RATE)).toFloat()
                lp += a * (w - lp); lp2 += a * (lp - lp2)
                val swell = (0.25 + 0.75 * (0.5 - 0.5 * cos(tau * ph * 2 - 0.5)).pow(1.5)).toFloat()
                brown = (brown + 0.02f * w) * 0.995f
                out[i] = drone * 0.55f * trem + pad * 0.35f + lp2 * 1.6f * swell + brown * 0.5f
            }
            // Blend the extra two seconds back over the start (equal power), so the loop has no seam
            for (i in 0 until x) {
                val k = i.toFloat() / x
                val a = sin(k * PI / 2).toFloat(); val b = cos(k * PI / 2).toFloat()
                out[i] = out[i] * a + out[n + i] * b
            }
            var peak = 1e-6f
            for (i in 0 until n) peak = maxOf(peak, abs(out[i]))
            val g = 0.7f * 32767f / peak
            return ShortArray(n) { (out[it] * g).toInt().coerceIn(-32767, 32767).toShort() }
        }
    }
}
