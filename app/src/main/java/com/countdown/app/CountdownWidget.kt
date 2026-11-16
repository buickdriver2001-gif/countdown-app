package com.countdown.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget. It uses Android's built-in Chronometer, which ticks every
 * second on its own without draining the battery, counting down in H:MM:SS.
 */
class CountdownWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) update(context, manager, id)
        scheduleFinish(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }

    companion object {
        private const val ACTION_REFRESH = "com.countdown.app.REFRESH"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CountdownWidget::class.java))
            for (id in ids) update(context, manager, id)
            scheduleFinish(context)
        }

        private fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)
            val target = CountdownStore.target(context)
            val remaining = target - System.currentTimeMillis()

            views.setTextViewText(R.id.widget_title, CountdownStore.name(context))
            val glow = CountdownStore.glow(context).resolve(remaining)
            views.setInt(R.id.widget_root, "setBackgroundResource", glow.drawable)
            views.setTextColor(R.id.widget_chrono, glow.color)
            views.setTextColor(R.id.widget_message, glow.color)

            when {
                target == 0L -> showMessage(views, "Tap to set a date")
                remaining <= 0 -> showMessage(views, "Time's up! 🎉")
                else -> {
                    views.setViewVisibility(R.id.widget_chrono, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_units, View.VISIBLE)
                    views.setViewVisibility(R.id.widget_message, View.GONE)
                    views.setChronometerCountDown(R.id.widget_chrono, true)
                    views.setChronometer(
                        R.id.widget_chrono,
                        SystemClock.elapsedRealtime() + remaining,
                        null,
                        true
                    )
                }
            }

            val openApp = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openApp)

            manager.updateAppWidget(id, views)
        }

        private fun showMessage(views: RemoteViews, message: String) {
            views.setChronometer(R.id.widget_chrono, SystemClock.elapsedRealtime(), null, false)
            views.setViewVisibility(R.id.widget_chrono, View.GONE)
            views.setViewVisibility(R.id.widget_units, View.GONE)
            views.setViewVisibility(R.id.widget_message, View.VISIBLE)
            views.setTextViewText(R.id.widget_message, message)
        }

        /** Wakes the widget when the countdown hits zero so it can show "Time's up!". */
        private fun scheduleFinish(context: Context) {
            val alarm = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = Intent(context, CountdownWidget::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
            val target = CountdownStore.target(context)
            if (target > System.currentTimeMillis()) {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC, target + 500, pending)
            }
        }
    }
}
