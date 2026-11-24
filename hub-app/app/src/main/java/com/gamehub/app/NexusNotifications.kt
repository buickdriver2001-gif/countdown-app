package com.gamehub.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets NEXUS show which apps have notifications in its status bar on the home screen (icons only, like the phone's
 * own status bar), and lets the NEXUS lock screen show your notifications. Needs Notification access (NEXUS settings
 * → Home screen, or → Lock screen). The lock screen follows the same privacy rules as the phone's own lock screen:
 *  - an app's or channel's "don't show on the lock screen" is respected (those never appear),
 *  - "sensitive" notifications show only the app's name unless you choose to show all content
 *    (by default NEXUS follows your phone's lock screen setting),
 *  - silent notifications, ongoing ones (music, downloads) and anything Do Not Disturb hides are left out.
 * Nothing is stored or sent anywhere: the list lives in memory while the phone is on.
 */
class NexusNotifications : NotificationListenerService() {

    /** One app's notifications, as a card on the lock screen (its newest notification, and how many). */
    class Item(val key: String, val pkg: String, val app: String, val title: String, val text: String,
               val count: Int, val time: Long, val intent: PendingIntent?, val autoCancel: Boolean,
               /** Every notification of this app the card stands for (so a swipe clears them all), and whether it can be cleared. */
               val keys: List<String> = listOf(key), val clearable: Boolean = true)

    /**
     * One app's icon in the NEXUS status bar: its own small notification symbol, as the phone's status bar shows it
     * ([glyph]), or else the app's icon.
     */
    class BarIcon(val pkg: String, val drawable: android.graphics.drawable.Drawable, val glyph: Boolean)

    companion object {
        /** NEXUS's own test notification (Gear → NEXUS lock screen → Send a test notification). */
        const val TEST_CHANNEL = "nexus_test"

        /** What the lock screen shows right now (newest first). */
        @Volatile var items: List<Item> = emptyList()
            private set
        @Volatile private var instance: NexusNotifications? = null
        /**
         * The boxes at the top of the NEXUS home screen (phone unlocked, so what they say shows, as in the phone's own
         * notification shade): one per app, newest first. Not silent ones, ongoing ones (music, downloads) or what
         * Do Not Disturb hides.
         */
        @Volatile var homeItems: List<Item> = emptyList()
            private set

        /** A music player is put away after this long without playing (as the phone's own panel does). */
        const val STALE_MS = 15 * 60_000L

        /** Swiped away in NEXUS but kept by the app: key → when it was posted (it shows again when it's posted anew). */
        private var hidden: HashMap<String, Long>? = null
        private fun hiddenAt(c: Context): HashMap<String, Long> = hidden ?: HashMap<String, Long>().also { m ->
            c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("hiddenNotifs", "").orEmpty().lineSequence().forEach { l ->
                val i = l.lastIndexOf('\t'); if (i > 0) l.substring(i + 1).toLongOrNull()?.let { m[l.substring(0, i)] = it }
            }
            hidden = m
        }

        /** Notification boxes at the top of the home screen (Settings → Notifications). */
        fun boxesOn(c: Context): Boolean = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("homeNotifs", true)
        fun setBoxesOn(c: Context, on: Boolean) { c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("homeNotifs", on).apply() }
        /** Clears notifications (a home screen box swiped away), as swiping them away in the notification shade does. */
        fun dismissAll(keys: List<String>) { if (keys.isNotEmpty()) try { instance?.cancelNotifications(keys.toTypedArray()) } catch (_: Exception) {} }

        /** The icons for the NEXUS status bar (home screen only, phone unlocked), one per app, newest first. */
        @Volatile var barIcons: List<BarIcon> = emptyList()
            private set
        /** The colour of the NEXUS status bar's symbols (the notification icons are tinted to match). */
        val BAR_TINT = android.graphics.Color.rgb(190, 210, 245)

        /** Notification icons in the NEXUS status bar (Settings → Home screen; on once Notification access is allowed). */
        fun barOn(c: Context): Boolean = c.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean("barNotifs", true)
        fun setBarOn(c: Context, on: Boolean) { c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putBoolean("barNotifs", on).apply() }

        fun granted(c: Context): Boolean = try {
            c.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(ComponentName(c, NexusNotifications::class.java))
        } catch (_: Exception) { false }

        /** "content" (show what they say), "private" (hide sensitive content) or "off". */
        fun mode(c: Context): String =
            c.getSharedPreferences("hub", Context.MODE_PRIVATE).getString("lockNotifs", null) ?: phoneMode(c)

        fun setMode(c: Context, m: String) {
            c.getSharedPreferences("hub", Context.MODE_PRIVATE).edit().putString("lockNotifs", m).apply()
            refresh()
        }

        /** Your phone's own lock screen setting, if Android shares it (otherwise: hide sensitive content). */
        private fun phoneMode(c: Context): String {
            fun read(key: String): Int? = try { Settings.Secure.getInt(c.contentResolver, key) } catch (_: Exception) { null }
            if (read("lock_screen_show_notifications") == 0) return "off"
            return if (read("lock_screen_allow_private_notifications") == 1) "content" else "private"
        }

        /** Re-reads the notifications (e.g. as the lock screen appears, or when the setting changes). */
        fun refresh() { instance?.rebuild() }

        /** Takes a notification away once it's been opened (only ones the app marked to go away when tapped). */
        fun dismiss(key: String) { try { instance?.cancelNotification(key) } catch (_: Exception) {} }
    }

    override fun onListenerConnected() { instance = this; rebuild() }
    override fun onListenerDisconnected() { if (instance === this) instance = null; items = emptyList(); barIcons = emptyList(); homeItems = emptyList() }
    override fun onDestroy() { if (instance === this) { instance = null; items = emptyList(); barIcons = emptyList(); homeItems = emptyList() }; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = rebuild()

    private fun rebuild() {
        try {
            val all = activeNotifications ?: emptyArray()
            val ranking = currentRanking
            val r = Ranking()
            // Status bar icons (home screen, phone unlocked): what the phone's own status bar would show. Every app
            // with a notification that isn't silent, ongoing ones too (a call, a recording, directions); not what
            // Do Not Disturb keeps out of the status bar
            barIcons = try { buildBarIcons(all, ranking) } catch (_: Throwable) { emptyList() }
            homeItems = try { buildHomeItems(all, ranking) } catch (_: Throwable) { emptyList() }
            val mode = mode(this)
            if (mode == "off") { items = emptyList(); return }
            // Groups that have their own notifications showing (their summary card would only repeat them)
            val withChildren = all.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.groupKey }.toHashSet()

            class Acc(val sbn: StatusBarNotification, val content: Boolean) { var count = 1 }
            val byApp = LinkedHashMap<String, Acc>()
            for (sbn in all) {
                val n = sbn.notification ?: continue
                if (sbn.packageName == packageName && n.channelId != TEST_CHANNEL) continue
                if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.groupKey in withChildren) continue
                if (leftOut(sbn)) continue
                var vis = n.visibility
                if (ranking != null && ranking.getRanking(sbn.key, r)) {
                    if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) continue // silent
                    if (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0) continue // Do Not Disturb
                    if (Build.VERSION.SDK_INT >= 28 && r.isSuspended) continue
                    val override = r.lockscreenVisibilityOverride
                    if (override != Ranking.VISIBILITY_NO_OVERRIDE) vis = override
                    else r.channel?.lockscreenVisibility?.let { if (it != Ranking.VISIBILITY_NO_OVERRIDE) vis = it }
                }
                if (vis == Notification.VISIBILITY_SECRET) continue // the app or you said: not on the lock screen
                val content = vis == Notification.VISIBILITY_PUBLIC || mode == "content"
                val acc = byApp[sbn.packageName]
                if (acc == null) byApp[sbn.packageName] = Acc(sbn, content)
                else {
                    acc.count++
                    if (sbn.postTime > acc.sbn.postTime) byApp[sbn.packageName] = Acc(sbn, content).also { it.count = acc.count }
                }
            }

            items = byApp.values.sortedByDescending { it.sbn.postTime }.map { a ->
                val n = a.sbn.notification
                val app = appName(a.sbn.packageName)
                // Sensitive notifications: only what the app chose to show publicly, or just "New notification"
                val src = if (a.content) n else n.publicVersion
                val ex = src?.extras
                val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
                val text = (ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT))
                    ?.toString()?.trim()?.lineSequence()?.firstOrNull().orEmpty()
                val (t1, t2) = when {
                    title.isEmpty() && text.isEmpty() -> "" to (if (a.count > 1) "${a.count} new notifications" else "New notification")
                    else -> title to text
                }
                Item(a.sbn.key, a.sbn.packageName, app, t1, t2, a.count, a.sbn.postTime, n.contentIntent,
                    n.flags and Notification.FLAG_AUTO_CANCEL != 0)
            }
        } catch (_: Throwable) { /* never let a strange notification break anything */ }
    }

    /**
     * Left out of everything NEXUS shows: a music player that hasn't played for a while (the phone's own panel
     * puts old players away too), and anything you swiped away in NEXUS that the app wouldn't let go of (until the
     * app posts it again, e.g. the music plays again).
     */
    private fun leftOut(sbn: StatusBarNotification): Boolean {
        if (hiddenAt(this)[sbn.key] == sbn.postTime) return true
        return staleMusic(sbn.notification ?: return false, sbn.postTime)
    }

    /** A music player (a media notification) that isn't playing and hasn't been for [STALE_MS]. */
    private fun staleMusic(n: Notification, postTime: Long): Boolean {
        val token = try {
            @Suppress("DEPRECATION") n.extras?.getParcelable<android.media.session.MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        } catch (_: Throwable) { null } ?: return false
        val st = try { android.media.session.MediaController(this, token).playbackState } catch (_: Throwable) { null }
        val playing = st != null && st.state in intArrayOf(android.media.session.PlaybackState.STATE_PLAYING,
            android.media.session.PlaybackState.STATE_BUFFERING, android.media.session.PlaybackState.STATE_CONNECTING,
            android.media.session.PlaybackState.STATE_FAST_FORWARDING, android.media.session.PlaybackState.STATE_REWINDING)
        if (playing) return false
        // Paused or stopped: how long since it last played (or since it was posted, if the app doesn't say)
        val since = if (st != null && st.lastPositionUpdateTime > 0) android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
                    else System.currentTimeMillis() - postTime
        return since > STALE_MS
    }

    /** The home screen's boxes: like the lock screen's cards, but showing what they say (the phone is unlocked). */
    private fun buildHomeItems(all: Array<StatusBarNotification>, ranking: RankingMap?): List<Item> {
        val r = Ranking()
        val withChildren = all.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.groupKey }.toHashSet()
        class Acc(var newest: StatusBarNotification) { var count = 0; val keys = ArrayList<String>(); var clearable = true }
        val byApp = LinkedHashMap<String, Acc>()
        for (sbn in all) {
            val n = sbn.notification ?: continue
            if (sbn.packageName == packageName && n.channelId != TEST_CHANNEL) continue
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
            if (leftOut(sbn)) continue
            if (ranking != null && ranking.getRanking(sbn.key, r)) {
                if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) continue // silent
                if (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST != 0) continue // Do Not Disturb
                if (Build.VERSION.SDK_INT >= 28 && r.isSuspended) continue
            }
            val a = byApp.getOrPut(sbn.packageName) { Acc(sbn) }
            a.keys.add(sbn.key)
            // (A group's summary only repeats its notifications: it's cleared with them, but doesn't count or show)
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.groupKey in withChildren) continue
            if (a.count == 0 || sbn.postTime > a.newest.postTime) a.newest = sbn
            a.count++
            if (n.flags and Notification.FLAG_NO_CLEAR != 0) a.clearable = false
        }
        return byApp.values.filter { it.count > 0 }.sortedByDescending { it.newest.postTime }.map { a ->
            val n = a.newest.notification
            val ex = n.extras
            val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (ex?.getCharSequence(Notification.EXTRA_TEXT) ?: ex?.getCharSequence(Notification.EXTRA_BIG_TEXT))
                ?.toString()?.trim()?.lineSequence()?.firstOrNull().orEmpty()
            val (t1, t2) = if (title.isEmpty() && text.isEmpty()) "" to (if (a.count > 1) "${a.count} new notifications" else "New notification") else title to text
            Item(a.newest.key, a.newest.packageName, appName(a.newest.packageName), t1, t2, a.count, a.newest.postTime,
                n.contentIntent, n.flags and Notification.FLAG_AUTO_CANCEL != 0, a.keys.toList(), a.clearable)
        }
    }

    private fun buildBarIcons(all: Array<StatusBarNotification>, ranking: RankingMap?): List<BarIcon> {
        val r = Ranking()
        val shown = all.filter { sbn ->
            val n = sbn.notification ?: return@filter false
            if (sbn.packageName == packageName && n.channelId != TEST_CHANNEL) return@filter false
            if (leftOut(sbn)) return@filter false
            if (ranking != null && ranking.getRanking(sbn.key, r)) {
                if (r.importance < NotificationManager.IMPORTANCE_DEFAULT) return@filter false // silent
                if (r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR != 0) return@filter false
                if (Build.VERSION.SDK_INT >= 28 && r.isSuspended) return@filter false
            }
            true
        }.sortedByDescending { it.postTime }
        val out = ArrayList<BarIcon>()
        val seen = HashSet<String>()
        for (sbn in shown) {
            if (!seen.add(sbn.packageName)) continue
            barIcon(sbn)?.let { out.add(it) }
            if (out.size >= 8) break
        }
        return out
    }

    /** Icons already read (the same few come back with every update), by app and symbol. */
    private val iconCache = HashMap<String, BarIcon>()

    private fun barIcon(sbn: StatusBarNotification): BarIcon? {
        val ic = sbn.notification?.smallIcon
        val key = sbn.packageName + "|" + (ic?.toString() ?: "app")
        iconCache[key]?.let { return it }
        if (iconCache.size > 48) iconCache.clear()
        // The app's small notification symbol, tinted to match NEXUS's status bar (the phone's bar does the same)
        val glyph = try { ic?.loadDrawable(this)?.mutate() } catch (_: Throwable) { null }
        val made = if (glyph != null) {
            glyph.setTint(BAR_TINT)
            BarIcon(sbn.packageName, glyph, true)
        } else {
            // (Can't read it: the app's own icon instead)
            val app = AppCache.get(this, sbn.packageName)?.icon?.constantState?.newDrawable()?.mutate() ?: return null
            BarIcon(sbn.packageName, app, false)
        }
        iconCache[key] = made
        return made
    }

    private fun appName(pkg: String): String = AppCache.get(this, pkg)?.label
        ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
}
