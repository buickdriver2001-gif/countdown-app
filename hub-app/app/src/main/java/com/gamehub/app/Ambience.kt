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
import kotlin.math.pow
import kotlin.math.sin

/**
 * The home screen's background sound: a soft, airy wash, slow swells of filtered noise over a low, quiet hum,
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
        private const val GAIN = 0.32f
        @Volatile private var pcm: ShortArray? = null

        fun enabled(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("ambience", true)
        fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("ambience", on).apply()

        /**
         * Makes the loop: two slow swells of band-passed noise sweeping up and down (the wash), a little airy
         * hiss riding on them, a soft rumble, and a faint low hum (110 Hz with its fifth and octave). Every slow
         * movement repeats exactly every 16 s, and the end is blended into the start, so it loops without a seam.
         */
        fun synth(): ShortArray {
            val n = RATE * LOOP_S
            val x = RATE * 2
            val out = FloatArray(n + x)
            val rnd = java.util.Random(7)
            var brown = 0f
            var low = 0f; var band = 0f
            var low2 = 0f; var band2 = 0f
            var lastW = 0f
            val tau = 2.0 * PI
            for (i in 0 until n + x) {
                val t = i.toDouble() / RATE
                val ph = (t % LOOP_S) / LOOP_S
                val w = rnd.nextFloat() * 2f - 1f
                // Soft rumble
                brown = (brown + 0.03f * w) * 0.985f
                // The wash: noise through a band-pass filter whose centre sweeps slowly up and back (twice a loop)
                val sweep = 0.5 - 0.5 * cos(tau * ph * 2)
                val fc = 280.0 + 1100.0 * sweep
                val f = (2.0 * sin(PI * fc / RATE)).toFloat()
                val hp = w - low - 0.55f * band
                band += f * hp; low += f * band
                // A second, slower and lower band, out of step with the first, for depth
                val fc2 = 160.0 + 420.0 * (0.5 - 0.5 * cos(tau * ph + 2.2))
                val f2 = (2.0 * sin(PI * fc2 / RATE)).toFloat()
                val hp2 = w - low2 - 0.7f * band2
                band2 += f2 * hp2; low2 += f2 * band2
                val swell = (0.1 + 0.9 * (0.5 - 0.5 * cos(tau * ph * 2 - 0.5)).pow(1.6)).toFloat()
                // Airy hiss on the swells
                val air = (w - lastW) * 0.5f; lastW = w
                // Faint low hum (whole numbers of cycles per loop, so it loops cleanly), breathing slowly
                val trem = (0.75 + 0.25 * sin(tau * ph * 4)).toFloat()
                val hum = (0.5 * sin(tau * 110.0 * t) + 0.3 * sin(tau * 165.0 * t + 1.1) + 0.18 * sin(tau * 220.0 * t + 2.0)).toFloat() * trem
                out[i] = band * 0.8f * swell + band2 * 0.4f * (1.1f - swell * 0.5f) + brown * 0.6f + air * 0.04f * swell + hum * 0.03f
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
