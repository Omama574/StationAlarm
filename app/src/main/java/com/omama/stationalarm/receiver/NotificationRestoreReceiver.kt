package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Fires when the user clears the ongoing monitoring notification
 * (setDeleteIntent target in [com.omama.stationalarm.service.ServiceNotifications]).
 *
 * If any station is still armed (MONITORING or ALERTING), restart the
 * foreground service — which re-posts the notification — so the trip stays
 * visibly active. If everything has been dismissed, do nothing (the guard
 * prevents a restart loop during normal teardown).
 */
class NotificationRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val armed = StationRepository.getAllActiveStationsList()
                    .any { it.status != "PAUSED" }
                if (!armed) {
                    Logger.log("NOTIFICATION_CLEARED_NO_ACTIVE")
                    return@launch
                }

                Logger.breadcrumb("NOTIFICATION_CLEARED_RESTORING")
                val restartIntent = Intent(context, LocationService::class.java).apply {
                    action = LocationService.ACTION_RESTORE_NOTIFICATION
                }
                ContextCompat.startForegroundService(context, restartIntent)
            } catch (e: Exception) {
                Logger.error("NOTIFICATION_RESTORE_FAILED", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
