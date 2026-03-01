package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.d(TAG, "Device booted, restoring active alarms...")
            Logger.log("SYSTEM_BOOTED", extra = "Restoring alarms")

            // Wait for DB to be accessible
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val activeStations = StationRepository.getAllActiveStationsList()
                    if (activeStations.isNotEmpty()) {
                        StationRepository.reRegisterAllGeofences()
                        
                        val serviceIntent = Intent(context, LocationService::class.java).apply {
                            action = LocationService.ACTION_START_FOR_ACTIVE_STATIONS
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                        Logger.log("BOOT_RESTORE", extra = "Restored ${activeStations.size} stations")
                    } else {
                        Logger.log("BOOT_RESTORE", extra = "No active stations to restore")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring alarms on boot", e)
                }
            }
        }
    }
}
