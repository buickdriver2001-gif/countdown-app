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
 * Lets the NEXUS lock screen show your notifications. Needs Notification access (NEXUS settings → NEXUS lock
 * screen). Follows the same privacy rules as the phone's own lock screen:
 *  - an app's or channel's "don't show on the lock screen" is respected (those never appear),
 *  - "sensitive" notifications show only the app's name unless you choose to show all content
 *    (by default NEXUS follows your phone's lock screen setting),
 *  - silent notifications, ongoing ones (music, downloads) and anything Do Not Disturb hides are left out.
 * Nothing is stored or sent anywhere: the list lives in memory while the phone is on.
 */
class NexusNotifications : NotificationListenerService() {

    /** One app's notifications, as a card on the lock screen (its newest notification, and how many). */
    class Item(val key: String, val pkg: String, val app: String, val title: String, val text: String,
               val count: Int, val time: Long, val intent: PendingIntent?, val autoCancel: Boolean)

    companion object {
        /** What the lock screen shows right now (newest first). */
        @Volatile var items: List<Item> = emptyList()
            private set
        @Volatile private var instance: NexusNotifications? = null

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
    override fun onListenerDisconnected() { if (instance === this) instance = null; items = emptyList() }
    override fun onDestroy() { if (instance === this) { instance = null; items = emptyList() }; super.onDestroy() }
    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) = rebuild()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = rebuild()

    private fun rebuild() {
        try {
            val mode = mode(this)
            if (mode == "off") { items = emptyList(); return }
            val all = activeNotifications ?: emptyArray()
            val ranking = currentRanking
            val r = Ranking()
            // Groups that have their own notifications showing (their summary card would only repeat them)
            val withChildren = all.filter { it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }.map { it.groupKey }.toHashSet()

            class Acc(val sbn: StatusBarNotification, val content: Boolean) { var count = 1 }
            val byApp = LinkedHashMap<String, Acc>()
            for (sbn in all) {
                val n = sbn.notification ?: continue
                if (sbn.packageName == packageName) continue
                if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) continue
                if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && sbn.groupKey in withChildren) continue
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

    private fun appName(pkg: String): String = AppCache.get(this, pkg)?.label
        ?: try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
}
