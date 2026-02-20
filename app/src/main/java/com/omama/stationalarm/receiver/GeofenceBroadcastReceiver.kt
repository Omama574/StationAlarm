    package com.omama.stationalarm.receiver

    import android.content.BroadcastReceiver
    import android.content.Context
    import android.content.Intent
    import android.util.Log
    import androidx.core.content.ContextCompat
    import com.google.android.gms.location.GeofencingEvent
    import com.omama.stationalarm.repository.StationRepository
    import com.omama.stationalarm.service.LocationService

    class GeofenceBroadcastReceiver : BroadcastReceiver() {

        private val TAG = "GeofenceBR"

        override fun onReceive(context: Context, intent: Intent) {
            val geofencingEvent = GeofencingEvent.fromIntent(intent)
            if (geofencingEvent == null) {
                Log.d(TAG, "No geofencing event in intent")
                return
            }

            if (geofencingEvent.hasError()) {
                val errorCode = geofencingEvent.errorCode
                Log.d(TAG, "GEOFENCE_ERROR code=$errorCode")
                return
            }

            val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
            if (triggeringGeofences.isEmpty()) {
                Log.d(TAG, "GEOFENCE: no triggering geofences")
                return
            }

            // Extend receiver lifetime while we process (goAsync)
            val pendingResult = goAsync()

            // Use a background thread (small, self-contained work)
            Thread {
                try {
                    for (geofence in triggeringGeofences) {
                        val requestId = geofence.requestId ?: continue
                        // expected format: geofence_<stationId>_<layer>
                        val parts = requestId.split("_")
                        if (parts.size < 3) {
                            Log.d(TAG, "Invalid geofence requestId: $requestId")
                            continue
                        }
                        val stationId = parts[1]
                        val layer = parts[2]

                        if (!StationRepository.isActive(stationId)) {
                            Log.d(TAG, "Station not active: $stationId (skipping)")
                            continue
                        }

                        Log.d(TAG, "GEOFENCE_TRIGGERED: $stationId / $layer")

                        val serviceIntent = Intent(context.applicationContext, LocationService::class.java).apply {
                            action = "com.omama.stationalarm.ACTION_GEOFENCE_TRIGGERED"
                            putExtra("stationId", stationId)
                            putExtra("layer", layer)
                        }

                        try {
                            ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
                        } catch (e: Exception) {
                            Log.d(TAG, "Failed to start LocationService: ${e.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Exception processing geofence: ${t.message}")
                } finally {
                    pendingResult.finish()
                }
            }.start()
        }
    }
