package com.gamehub.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * NEXUS's menu sounds: one for scrolling (each app passing the middle, the chosen one changing), one for tapping
 * (buttons, choosing), and one for opening an app. Each is picked in Settings → Sound from NEXUS's own sounds
 * (made up in code, see [SoundBank]), with a volume of its own. They play on a thread of their own (never holding
 * up the screen), and quick ones overlap instead of cutting each other off.
 */
class Sfx {
    private val thread = android.os.HandlerThread("nexus-sounds").apply { start() }
    private val h = android.os.Handler(thread.looper)
    private var scrolls: Array<AudioTrack?> = emptyArray()
    private var taps: Array<AudioTrack?> = emptyArray()
    private var opens: Array<AudioTrack?> = emptyArray()
    private var nextScroll = 0
    private var nextTap = 0
    private var nextOpen = 0
    private var preview: AudioTrack? = null
    /** Which choices the sounds were made for (they're made again when Settings changes them). */
    private var built = -1

    init { h.post { rebuild() } }

    private fun rebuild() {
        val v = version
        if (v == built) return
        built = v
        (scrolls + taps + opens).forEach { try { it?.release() } catch (_: Exception) {} }
        scrolls = pool(SoundBank.click(scrollId), 4)
        taps = pool(SoundBank.click(tapId), 3)
        opens = pool(SoundBank.open(openId), 2)
    }

    private fun pool(data: ShortArray?, n: Int): Array<AudioTrack?> = if (data == null) emptyArray() else Array(n) { make(data) }

    private fun make(data: ShortArray): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SoundBank.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().also { it.write(data, 0, data.size) }
    } catch (_: Exception) { null }

    private fun play(t: AudioTrack?, vol: Float) {
        try {
            t ?: return
            if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop()
            t.reloadStaticData()
            t.setVolume(vol)
            t.play()
        } catch (_: Exception) {}
    }

    private fun fire(kind: Int) {
        if (!on) return
        h.post {
            if (built != version) rebuild()
            when (kind) {
                SCROLL -> if (scrolls.isNotEmpty()) { nextScroll = (nextScroll + 1) % scrolls.size; play(scrolls[nextScroll], volume) }
                TAP -> if (taps.isNotEmpty()) { nextTap = (nextTap + 1) % taps.size; play(taps[nextTap], volume) }
                else -> if (opens.isNotEmpty()) { nextOpen = (nextOpen + 1) % opens.size; play(opens[nextOpen], volume) }
            }
        }
    }

    /** Scrolling: an app passing the middle, or another one chosen. */
    fun scroll() = fire(SCROLL)
    /** Tapping a button or choosing something. */
    fun tick() = fire(TAP)
    /** Confirming something that isn't opening an app (it sounds like a tap). */
    fun confirm() = fire(TAP)
    /** Opening an app. */
    fun open() = fire(OPEN)

    /** Settings → Sound: plays one of the choices once (even with menu sounds off), at [vol]. */
    fun preview(kind: Int, id: String, vol: Float = volume) {
        h.post {
            try { preview?.release() } catch (_: Exception) {}
            val data = if (kind == OPEN) SoundBank.open(id) else SoundBank.click(id)
            preview = data?.let { make(it) }
            play(preview, vol)
        }
    }

    companion object {
        const val SCROLL = 0
        const val TAP = 1
        const val OPEN = 2

        /** Menu sounds on (Settings → Sound). */
        @Volatile var on = true
        @Volatile var scrollId = "glass"
        @Volatile var tapId = "glass"
        @Volatile var openId = "rise"
        /** Menu sounds' volume (of the media volume), 0.2 to 1. */
        @Volatile var volume = 1f
        /** Goes up whenever a choice changes, so each player makes its sounds again. */
        @Volatile var version = 0

        /** Volume steps for Settings, quietest first. */
        val LEVELS = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
        val LEVEL_NAMES = arrayOf("Very low", "Low", "Medium", "High", "Full")
        /** The nearest step to [v]. */
        fun level(v: Float): Int = LEVELS.indices.minByOrNull { kotlin.math.abs(LEVELS[it] - v) } ?: 4
        /** A volume as dots, filled up to its step: ●●●○○. */
        fun dots(v: Float): String { val k = level(v); return "●".repeat(k + 1) + "○".repeat(LEVELS.size - k - 1) }

        private fun prefs(c: Context) = c.getSharedPreferences("hub", Context.MODE_PRIVATE)

        /** Reads the choices from Settings (at start-up, and after a change). */
        fun load(c: Context) {
            val p = prefs(c)
            on = p.getBoolean("menuSounds", true)
            scrollId = p.getString("sfxScroll", "glass") ?: "glass"
            tapId = p.getString("sfxTap", "glass") ?: "glass"
            openId = p.getString("sfxOpen", "rise") ?: "rise"
            volume = p.getFloat("sfxVol", 1f).coerceIn(0.1f, 1f)
            version++
        }

        fun setChoice(c: Context, kind: Int, id: String) {
            prefs(c).edit().putString(when (kind) { SCROLL -> "sfxScroll"; TAP -> "sfxTap"; else -> "sfxOpen" }, id).apply()
            load(c)
        }

        fun choice(kind: Int): String = when (kind) { SCROLL -> scrollId; TAP -> tapId; else -> openId }

        fun setVolume(c: Context, v: Float) { prefs(c).edit().putFloat("sfxVol", v).apply(); load(c) }
        fun setOn(c: Context, v: Boolean) { prefs(c).edit().putBoolean("menuSounds", v).apply(); load(c) }
    }

    fun release() {
        h.post {
            (scrolls + taps + opens).forEach { try { it?.release() } catch (_: Exception) {} }
            try { preview?.release() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }
}
