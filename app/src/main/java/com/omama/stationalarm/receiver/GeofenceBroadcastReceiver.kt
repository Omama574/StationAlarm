package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.omama.stationalarm.repository.StationRepository
import com.omama.stationalarm.service.LocationService
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    private val TAG = "GeofenceBR"

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "No geofencing event in intent")
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofence error: ${geofencingEvent.errorCode}")
            Logger.log("GEOFENCE_ERROR", extra = "errorCode=${geofencingEvent.errorCode}")
            return
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        if (triggeringGeofences.isEmpty()) {
            Log.d(TAG, "No triggering geofences")
            return
        }

        // Use goAsync to allow long-running operation
        val pendingResult = goAsync()

        // Process in background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (geofence in triggeringGeofences) {
                    val requestId = geofence.requestId ?: continue
                    // Parse requestId: expected format "geofence_<stationId>_<layer>"
                    val parts = requestId.split("_")
                    if (parts.size != 3) {
                        Log.w(TAG, "Invalid requestId format: $requestId")
                        continue
                    }
                    val stationId = parts[1]
                    val layer = parts[2]

                    // Verify station is still active
                    if (!StationRepository.isActive(stationId)) {
                        Log.d(TAG, "Station $stationId is no longer active, ignoring geofence")
                        continue
                    }

                    Log.d(TAG, "Geofence triggered: $stationId, layer=$layer")
                    Logger.log("GEOFENCE_TRIGGERED", stationId, layer)

                    if (layer == "alert") {
                        StationRepository.markAlerting(stationId)
                    }

                    // Start LocationService to ensure it reacts to the DB update
                    val serviceIntent = Intent(context, LocationService::class.java).apply {
                        action = if (layer == "alert") {
                            LocationService.ACTION_ALERT_GEOFENCE_TRIGGERED
                        } else {
                            LocationService.ACTION_GEOFENCE_TRIGGERED
                        }
                        putExtra("stationId", stationId)
                        putExtra("layer", layer)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing geofence", e)
                Logger.log("ERROR", extra = "Geofence processing: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}