package com.gamehub.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

class MainActivity : Activity() {

    private val games = listOf(
        GameEntry("Zombie Dots", "com.zombiedots.app", "Survive the endless horde", Color.rgb(255, 80, 80), 0),
        GameEntry("Ashen Vow", "com.ashenvow.app", "Defeat The Hollow Warden", Color.rgb(230, 150, 60), 1),
        GameEntry("SynthPad", "com.synthpad.app", "Make music with synths", Color.rgb(120, 220, 255), 2)
    )
    private lateinit var hub: HubView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.crashFile(this).exists()) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        hub = HubView(this, games) { open(it) }
        setContentView(hub)
        if (savedInstanceState == null) Thread { playChime() }.start()
    }

    override fun onResume() {
        super.onResume()
        for (g in games) g.installed = packageManager.getLaunchIntentForPackage(g.pkg) != null
        if (::hub.isInitialized) hub.resumed()
    }

    private fun open(g: GameEntry) {
        val launch = packageManager.getLaunchIntentForPackage(g.pkg)
        if (launch != null) {
            startActivity(launch)
            return
        }
        // Not installed yet: open its download page
        val tag = when (g.pkg) { "com.zombiedots.app" -> "zombie-build"; "com.ashenvow.app" -> "ashen-build"; else -> "synth-build" }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/buickdriver2001-gif/countdown-app/releases?q=$tag&expanded=true")))
        } catch (_: Exception) {
            Toast.makeText(this, "Couldn't open the download page", Toast.LENGTH_SHORT).show()
        }
    }

    /** A soft, airy start-up chord that swells in and fades away (generated, no sound files). */
    private fun playChime() {
        try {
            val rate = 22050
            val secs = 4.5f
            val n = (rate * secs).toInt()
            val buf = ShortArray(n)
            val notes = floatArrayOf(220f, 277.18f, 329.63f, 415.3f, 554.37f, 880f)
            val gains = floatArrayOf(0.22f, 0.18f, 0.18f, 0.14f, 0.1f, 0.05f)
            for (i in 0 until n) {
                val t = i.toFloat() / rate
                val env = min(1f, t / 1.3f) * exp(-(t / 2.2f).let { it * it }) * 1.6f
                var v = 0f
                for (k in notes.indices) {
                    val f = notes[k]
                    // Two slightly detuned voices per note for a shimmering pad
                    v += gains[k] * (sin(2 * PI * f * t).toFloat() + sin(2 * PI * f * 1.004 * t + k).toFloat()) * 0.5f
                }
                // A faint high sparkle that arrives a moment later
                v += 0.04f * sin(2 * PI * 1760.0 * t).toFloat() * min(1f, kotlin.math.max(0f, t - 0.8f)) * exp(-t * 1.2f)
                buf[i] = (v * env * 0.45f * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(buf, 0, n)
            track.play()
            Thread.sleep((secs * 1000).toLong() + 200)
            track.release()
        } catch (_: Exception) {
        }
    }
}
