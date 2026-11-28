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
 * The home screen's background music: one of NEXUS's own loops (the deep drone, a warm pad, night wind, the
 * ocean...; made up on the phone from scratch, see [SoundBank]), chosen in Settings → Sound. It loops seamlessly
 * and costs almost nothing to play (the phone loops it by itself).
 *
 * Plays by itself whenever the NEXUS home screen is in front (on the media volume, so silent or vibrate
 * don't stop it), and steps aside while music or a video is playing and during calls, alarms and ringing.
 * Settings → Sound sets its volume or switches it off.
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
        h.removeCallbacks(retry)
        if (!enabled(c)) return
        val t0 = track
        // Back on the home screen while it's still fading out from leaving: just fade it back in. (Asking Android whether
        // anything is playing would count this sound itself and wrongly hold it back.)
        if (t0 != null && !steppedAside && isPlaying(t0)) {
            others = otherPlayers(); listen(true); tries = 0
            fadeTo(gain()); note("playing")
            return
        }
        // Something else sounding (music, a video, a call, or the app you've just left finishing its sound): look again
        // soon, then less often
        if (!quietNow()) {
            note("waiting: other sound playing")
            h.postDelayed(retry, retryDelay()); return
        }
        val want = style(c)
        val data = pcm?.takeIf { pcmStyle == want }
        if (data == null) {
            if (!building) {
                building = true
                Bg.thread {
                    val made = try { SoundBank.background(want) } catch (_: Throwable) { null }
                    h.post {
                        building = false
                        if (made != null && want == style(c)) { pcm = made; pcmStyle = want }
                        if (wanted) start()
                    }
                }.start()
            }
            return
        }
        val t = track ?: make(data)?.also { track = it } ?: return
        others = otherPlayers()
        listen(true)
        steppedAside = false; tries = 0
        try { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() } catch (_: Throwable) { return }
        fadeTo(gain()); note("playing")
    }

    /** Leaving the home screen: fade out (and stop). */
    fun stop() {
        wanted = false
        h.removeCallbacks(retry)
        listen(false)
        steppedAside = false; tries = 0
        fadeTo(0f)
    }

    /** It faded out for another sound (not because the home screen was left): it comes back once that's over. */
    private var steppedAside = false
    /** Times it has looked for quiet since it last played: soon at first, then less often. */
    private var tries = 0
    private fun retryDelay(): Long { val d = when (tries) { 0 -> 700L; 1 -> 1500L; 2 -> 3000L; else -> RETRY_MS }; tries++; return d }

    private fun isPlaying(t: AudioTrack): Boolean = try { t.playState == AudioTrack.PLAYSTATE_PLAYING } catch (_: Throwable) { false }

    /** What it last did, for "Report a problem" (each change once, not every look). */
    private var lastNote = ""
    private fun note(what: String) { if (what != lastNote) { lastNote = what; try { LockTrace.log(c, "background sound: $what") } catch (_: Throwable) {} } }

    /** Settings → Sound picked other music: the old loop goes, and the new one is made and fades in (if wanted). */
    fun restyle() {
        h.removeCallbacks(fader); h.removeCallbacks(retry)
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null; vol = 0f; target = 0f
        if (wanted) start()
    }

    /** Stops at once and lets go of the sound. */
    fun release() {
        wanted = false
        h.removeCallbacks(retry)
        listen(false)
        h.removeCallbacks(fader)
        try { track?.stop() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }

    /** Nothing else is sounding: no call, no music or video playing. (Silent and vibrate don't matter: it's on the media volume.) */
    private fun quietNow(): Boolean {
        val am = audio ?: return false
        return am.mode == AudioManager.MODE_NORMAL && !am.isMusicActive
    }

    private val retry = Runnable { if (wanted) start() }

    /** How loud it plays now (Settings > Sound > Background volume). */
    private fun gain() = MAX_GAIN * volume(c)

    /** The volume setting changed: fade to the new level (if it's playing). */
    fun refreshVolume() { if (wanted && track != null && target > 0f) fadeTo(gain()) }

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
            // Something else may have started: step aside, and look again as soon as this sound has faded out (then
            // Android can tell whether anything else is really playing; if not, it comes straight back)
            if (n > others) {
                listen(false); steppedAside = true; tries = 0; fadeTo(0f); note("stepped aside: another sound started")
                h.removeCallbacks(retry); h.postDelayed(retry, 900L)
            } else others = n
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
            val step = if (target > vol) MAX_GAIN / 40f else MAX_GAIN / 18f // a second or so in, half a second out
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
        /** How loud, at most (of the media volume), with the volume setting at High. */
        private const val MAX_GAIN = 0.7f
        /** How often it checks whether it can come back after stepping aside. */
        private const val RETRY_MS = 5000L
        @Volatile private var pcm: ShortArray? = null
        /** Which music [pcm] is. */
        @Volatile private var pcmStyle = ""

        fun enabled(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("ambience", true)
        fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("ambience", on).apply()
        /** Background volume, 0.2 to 1 (Medium, 0.6, to start with). */
        fun volume(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getFloat("ambienceVol", 0.6f)
        fun setVolume(c: Context, v: Float) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putFloat("ambienceVol", v).apply()
        /** Which music plays (one of [SoundBank.BG_IDS]). */
        fun style(c: Context): String = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("ambienceStyle", "drone")
            ?.takeIf { it in SoundBank.BG_IDS } ?: "drone"
        fun setStyle(c: Context, id: String) = c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("ambienceStyle", id).apply()
        fun styleName(c: Context): String = SoundBank.name(SoundBank.BG_IDS, SoundBank.BG_NAMES, style(c))

    }
}
