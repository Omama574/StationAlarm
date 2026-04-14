package com.omama.stationalarm.receiver

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
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

            val serviceIntent = Intent(applicationContext, LocationService::class.java).apply {
                action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
            }
            ContextCompat.startForegroundService(applicationContext, serviceIntent)

            Logger.log("BOOT_RESTORE", extra = "Restored ${activeStations.size} stations, allOk=$allOk")

            // If any geofence failed to register, WorkManager retries with
            // exponential backoff per the policy set in BootReceiver.
            if (allOk) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring alarms on boot", e)
            Logger.log("BOOT_RESTORE_ERROR", extra = e.message ?: "unknown")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BootRestoreWorker"
        const val WORK_NAME = "boot_restore_alarms"
    }
}
