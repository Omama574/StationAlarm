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
        const val AUDIO_FAILURE_NOTIFICATION_ID = 12346
        const val EXTRA_STATION_NAME = "stationName"
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
            ).apply {
                description = "High priority alarm alerts"
                // Without this, Do Not Disturb suppresses the full-screen alert
                // intent that's the only way the user sees the alarm screen on
                // a locked device. CATEGORY_ALARM alone isn't enough on stock
                // Pixels and most OEM ROMs.
                setBypassDnd(true)
            }
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

        // Fires when the user clears the ongoing notification. On Android 14+
        // even setOngoing(true) notifications are swipe-dismissible in some
        // cases, so we catch the clear and repost the notification from a
        // broadcast receiver — keeping the trip visibly armed.
        val restoreIntent = Intent("com.omama.stationalarm.ACTION_RESTORE_NOTIFICATION").apply {
            setPackage(context.packageName)
        }
        val restorePendingIntent = PendingIntent.getBroadcast(
            context, 0, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Explicit user-cancel path — since swipe is now self-healing, this
        // action is how users actually end a trip without opening the app or
        // waiting for the alarm to ring.
        val stopAllIntent = Intent(context, LocationService::class.java).apply {
            action = LocationService.ACTION_STOP_ALL_MONITORING
        }
        val stopAllPendingIntent = PendingIntent.getService(
            context, 0, stopAllIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Station Alarm")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setDeleteIntent(restorePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop all", stopAllPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun notifyForeground(notification: Notification) {
        manager.notify(FOREGROUND_NOTIFICATION_ID, notification)
    }

    fun showAlert(active: ActiveStation) {
        // stationName extra lets AlarmActivity render the right title on its
        // first frame instead of briefly showing the raw stationId while the
        // async DB lookup loads.
        val resolvedName = active.getStation()?.name ?: active.stationId
        val fullscreenIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("stationId", active.stationId)
            putExtra(EXTRA_STATION_NAME, resolvedName)
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

    fun showWatchdog(
        title: String = "Searching for GPS…",
        body: String = "Still tracking — will resume when a fix returns."
    ) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        manager.notify(WATCHDOG_NOTIFICATION_ID, builder.build())
    }

    fun cancelWatchdog() {
        manager.cancel(WATCHDOG_NOTIFICATION_ID)
    }

    /**
     * Posts when both the user-picked alarm URI AND the system default URI
     * fail to play (the only remaining "silent alarm" path). The full-screen
     * AlarmActivity still launches and vibration still runs — this just tells
     * the user *why* they didn't hear a sound, so they don't think the alarm
     * silently failed.
     */
    fun showAudioFailureNotification(stationName: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, ALARM_CHANNEL_ID)
            .setContentTitle("Alarm fired silently")
            .setContentText("Audio system error at $stationName — vibration only.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
        manager.notify(AUDIO_FAILURE_NOTIFICATION_ID, builder.build())
    }

    fun cancelAudioFailureNotification() {
        manager.cancel(AUDIO_FAILURE_NOTIFICATION_ID)
    }
}
