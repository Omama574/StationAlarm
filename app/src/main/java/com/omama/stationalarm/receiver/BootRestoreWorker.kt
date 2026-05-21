package com.omama.stationalarm.receiver

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omama.stationalarm.MainActivity
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.service.ServiceNotifications
import com.omama.stationalarm.util.Analytics
import com.omama.stationalarm.util.Logger

/**
 * Restores active alarms after device boot. Runs inside WorkManager so that a
 * transient failure (StationRepository not yet initialised, geofencing client
 * temporarily unavailable, IO error) gets retried with backoff instead of
 * silently losing the user's alarms until they next launch the app.
 *
 * Enqueued with `ExistingWorkPolicy.KEEP` from [BootReceiver], so duplicate
 * BOOT_COMPLETED broadcasts do not stack up multiple parallel restores.
 */
class BootRestoreWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val activeStations = StationRepository.getAllActiveStationsList()
            if (activeStations.isEmpty()) {
                Logger.log("BOOT_RESTORE", extra = "No active stations to restore")
                return Result.success()
            }

            // Any station stuck in ALERTING after a reboot is almost certainly
            // stale — the user may have already passed the station. Reset them
            // so they can fire again from MONITORING.
            for (station in activeStations) {
                if (station.status == "ALERTING") {
                    StationRepository.resetToMonitoring(station.stationId)
                }
            }

            val allOk = StationRepository.reRegisterAllGeofencesNow()

            if (!allOk) {
                Logger.breadcrumb(
                    "BOOT_RESTORE",
                    extra = "Partial fail; service NOT started this attempt (runAttempt=$runAttemptCount)"
                )
                if (runAttemptCount >= MAX_ATTEMPTS_BEFORE_USER_NOTICE) {
                    showRestartNeededNotification()
                    Logger.breadcrumb("BOOT_RESTORE_GIVE_UP", extra = "Notified user after $runAttemptCount attempts")
                    Analytics.event("boot_restore_gave_up") {
                        putInt("attempts", runAttemptCount)
                    }
                    return Result.failure()
                }
                return Result.retry()
            }

            // Geofences ready — only now is it safe to start the service. Otherwise
            // the user sees "Monitoring…" with broken geofences during the retry window.
            val serviceIntent = Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
            }
            ContextCompat.startForegroundService(applicationContext, serviceIntent)

            Logger.breadcrumb("BOOT_RESTORE", extra = "Restored ${activeStations.size} stations OK")
            Analytics.event("boot_restore_ok") {
                putInt("restored_count", activeStations.size)
                putInt("attempts", runAttemptCount)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring alarms on boot", e)
            Logger.error("BOOT_RESTORE_ERROR", e)
            Result.retry()
        }
    }

    /** User-visible escalation when boot restore exhausts retries — without this
     *  the alarms remain silently broken until the user next opens the app. */
    private fun showRestartNeededNotification() {
        // Make sure the channel exists; createChannels is idempotent.
        ServiceNotifications(applicationContext).createChannels()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = android.app.PendingIntent.getActivity(
            applicationContext, 0, launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(applicationContext, ServiceNotifications.CHANNEL_ID)
            .setContentTitle("Restart needed")
            .setContentText("Open StationAlarm to re-arm your alarms.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(BOOT_RESTORE_FAILED_NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "BootRestoreWorker"
        const val WORK_NAME = "boot_restore_alarms"
        private const val BOOT_RESTORE_FAILED_NOTIF_ID = 9100
        // BootReceiver schedules backoff up to ~1h20m total wait across 5 retries.
        private const val MAX_ATTEMPTS_BEFORE_USER_NOTICE = 5
    }
}
