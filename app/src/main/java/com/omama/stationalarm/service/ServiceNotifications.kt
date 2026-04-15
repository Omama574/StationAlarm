package com.omama.stationalarm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.data.ActiveStation
import com.omama.stationalarm.ui.screens.AlarmActivity

/**
 * Owns every notification the alarm engine emits: the foreground "monitoring"
 * notification, the per-station full-screen alert, and the watchdog warning.
 *
 * All channel IDs, notification IDs and PendingIntent wiring are preserved
 * verbatim from the original LocationService implementation.
 */
internal class ServiceNotifications(private val context: Context) {

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 12345
        const val WATCHDOG_NOTIFICATION_ID = 9999
        const val CHANNEL_ID = "location_channel"
        const val ALARM_CHANNEL_ID = "alarm_channel"
        private const val CHANNEL_NAME = "Station Alarm Service"
        private const val ALARM_CHANNEL_NAME = "Station Alarm Alerts"
    }

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shows tracking status" }
            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                ALARM_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "High priority alarm alerts" }
            manager.createNotificationChannel(channel)
            manager.createNotificationChannel(alarmChannel)
        }
    }

    fun buildForeground(text: String): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Station Alarm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyForeground(notification: Notification) {
        manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    fun showAlert(active: ActiveStation) {
        val fullscreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stationId", active.stationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, active.stationId.hashCode(), fullscreenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_DISMISS_ALARM
            putExtra("stationId", active.stationId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            context, active.stationId.hashCode(), dismissIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val customText = active.customReminder?.takeIf { active.sendReminder }
        val bodyText = if (customText.isNullOrBlank()) "You have arrived at your destination." else "Reminder: $customText"

        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle("Station Reached: ${active.getStation()?.name ?: active.stationId}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setFullScreenIntent(pendingIntent, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dismiss", dismissPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))

        manager.notify(active.stationId.hashCode(), builder.build())
    }

    fun cancelAlert(stationId: String) {
        manager.cancel(stationId.hashCode())
    }

    /** Explicitly remove the foreground notification. stopForeground(REMOVE)
     *  alone sometimes leaves a stale visible notification when the service is
     *  stopping right after a notifyForeground() call. */
    fun cancelForeground() {
        manager.cancel(FOREGROUND_NOTIFICATION_ID)
    }

    fun showWatchdog() {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Location Stalled")
            .setContentText("GPS signal lost. Tap to open app and restart tracking.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        manager.notify(WATCHDOG_NOTIFICATION_ID, builder.build())
    }
}
