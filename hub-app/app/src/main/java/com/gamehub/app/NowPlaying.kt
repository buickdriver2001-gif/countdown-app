package com.gamehub.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * What's playing in a music app (Apple Music, or any app that shows its music on the phone's own lock screen), for
 * the NEXUS lock screen: the song, the artist, the album art and how far in it is, and the buttons to play, pause
 * and skip. Read from the phone's media sessions through NEXUS's notification access, as the phone's own lock
 * screen does; nothing is kept. [onChange] is told (on the main thread) whenever any of it changes.
 */
class NowPlaying(private val c: Context, private val onChange: () -> Unit) {

    class Info(val pkg: String, val app: String, val title: String, val artist: String, val art: Bitmap?,
               val playing: Boolean, private val position: Long, private val at: Long, private val speed: Float,
               /** The song's length (ms), or 0 if the app doesn't say. */ val duration: Long) {
        /** How far into the song it is now (ms), moving on while it plays. */
        fun positionNow(): Long =
            if (!playing || at <= 0L) position else position + ((SystemClock.elapsedRealtime() - at) * speed).toLong()
    }

    /** What to show, or null (nothing playing, nothing paused lately, or no notification access). */
    @Volatile var info: Info? = null
        private set

    private val h = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var started = false
    private var artFrom: Bitmap? = null
    private var artSmall: Bitmap? = null
    private val msm: MediaSessionManager? get() = try { c.getSystemService(MediaSessionManager::class.java) } catch (_: Throwable) { null }
    private val me get() = ComponentName(c, NexusNotifications::class.java)

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list) }
    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { read() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { read() }
        override fun onSessionDestroyed() { pick(try { msm?.getActiveSessions(me) } catch (_: Throwable) { null }) }
    }

    /** The lock screen is showing: start following the music apps. */
    fun start() {
        if (started) return
        started = true
        try {
            msm?.addOnActiveSessionsChangedListener(sessions, me, h)
            pick(msm?.getActiveSessions(me))
        } catch (_: Throwable) { set(null) } // (no notification access: nothing to show)
    }

    /** The lock screen has gone: stop following them. */
    fun stop() {
        if (!started) return
        started = false
        try { msm?.removeOnActiveSessionsChangedListener(sessions) } catch (_: Throwable) {}
        try { controller?.unregisterCallback(cb) } catch (_: Throwable) {}
        controller = null; info = null; artFrom = null; artSmall = null
    }

    private fun isPlaying(st: PlaybackState?) = st != null && (st.state == PlaybackState.STATE_PLAYING ||
        st.state == PlaybackState.STATE_BUFFERING || st.state == PlaybackState.STATE_CONNECTING)

    /** Paused in the last half hour (as the phone's own lock screen keeps a paused player for a while). */
    private fun pausedLately(st: PlaybackState?) = st != null && st.state == PlaybackState.STATE_PAUSED &&
        (st.lastPositionUpdateTime <= 0L || SystemClock.elapsedRealtime() - st.lastPositionUpdateTime < 30 * 60_000L)

    /** The one to show: one that's playing, or else one paused lately. */
    private fun pick(list: List<MediaController>?) {
        val l = try { list.orEmpty().filter { it.metadata != null } } catch (_: Throwable) { emptyList() }
        val best = l.firstOrNull { isPlaying(it.playbackState) } ?: l.firstOrNull { pausedLately(it.playbackState) }
        if (best?.sessionToken != controller?.sessionToken) {
            try { controller?.unregisterCallback(cb) } catch (_: Throwable) {}
            controller = best
            try { best?.registerCallback(cb, h) } catch (_: Throwable) {}
        }
        read()
    }

    private fun read() {
        try {
            val mc = controller
            val md = mc?.metadata
            if (mc == null || md == null) { set(null); return }
            val st = mc.playbackState
            if (!isPlaying(st) && !pausedLately(st)) { set(null); return }
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            if (title.isBlank()) { set(null); return }
            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            // (A big picture is made small once, so drawing it every frame costs nothing)
            if (art !== artFrom) {
                artFrom = art
                artSmall = art?.let { b ->
                    if (b.width <= 256 && b.height <= 256) b
                    else try { Bitmap.createScaledBitmap(b, 256, (256f * b.height / b.width).toInt().coerceAtLeast(1), true) } catch (_: Throwable) { b }
                }
            }
            val app = try {
                c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(mc.packageName, 0)).toString()
            } catch (_: Throwable) { mc.packageName }
            set(Info(mc.packageName, app, title.trim(), artist.trim(), artSmall, isPlaying(st), st?.position ?: 0L,
                st?.lastPositionUpdateTime ?: 0L, st?.playbackSpeed ?: 1f, md.getLong(MediaMetadata.METADATA_KEY_DURATION)))
        } catch (_: Throwable) { set(null) }
    }

    private fun set(i: Info?) { info = i; onChange() }

    fun playPause() {
        val mc = controller ?: return
        try { if (isPlaying(mc.playbackState)) mc.transportControls.pause() else mc.transportControls.play() } catch (_: Throwable) {}
    }
    fun next() { try { controller?.transportControls?.skipToNext() } catch (_: Throwable) {} }
    fun previous() { try { controller?.transportControls?.skipToPrevious() } catch (_: Throwable) {} }

    /** What tapping the song opens (the music app's own screen for it), once the phone is unlocked. */
    fun sessionActivity(): android.app.PendingIntent? = try { controller?.sessionActivity } catch (_: Throwable) { null }
}
