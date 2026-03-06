package com.omama.stationalarm.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.receiver.GeofenceBroadcastReceiver
import com.omama.stationalarm.util.Logger

object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val ACTION_GEOFENCE = "com.omama.stationalarm.ACTION_GEOFENCE"

    private fun geofencingClient(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    private fun getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun addGeofencesForStation(
        context: Context,
        stationId: String,
        radiusLevel5M: Float,
        radiusLevel4M: Float,
        radiusLevel3M: Float,
        radiusLevel2M: Float,
        radiusLevel1M: Float,
        alertDistanceM: Float
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing location permission")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Missing permission")
            return
        }

        // Get station coordinates from repository
        val station = com.omama.stationalarm.repository.StationRepository.getStationByIdSync(stationId)
        if (station == null) {
            Log.e(TAG, "Station not found: $stationId")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Station not found")
            return
        }

        // Clamp alert geofence radius to Android's minimum of 100m
        val clampedAlertM = maxOf(alertDistanceM, 100f)

        val geofences = listOf(
            buildGeofence(stationId, "level5", station.lat, station.lon, radiusLevel5M),
            buildGeofence(stationId, "level4", station.lat, station.lon, radiusLevel4M),
            buildGeofence(stationId, "level3", station.lat, station.lon, radiusLevel3M),
            buildGeofence(stationId, "level2", station.lat, station.lon, radiusLevel2M),
            buildGeofence(stationId, "level1", station.lat, station.lon, radiusLevel1M),
            buildGeofence(stationId, "alert", station.lat, station.lon, clampedAlertM)
        )

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient(context)
            .addGeofences(request, getGeofencePendingIntent(context))
            .addOnSuccessListener {
                Log.d(TAG, "Geofences added for $stationId")
                Logger.log("GEOFENCE_REGISTERED", stationId, "radii=[$radiusLevel5M, $radiusLevel4M, $radiusLevel3M, $radiusLevel2M, $radiusLevel1M, alert=$clampedAlertM]")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to add geofences for $stationId", e)
                Logger.log("GEOFENCE_REG_FAILED", stationId, e.message)
            }
    }

    /**
     * Remove all five geofences for a station.
     */
    fun removeGeofencesForStation(context: Context, stationId: String) {
        val requestIds = listOf(
            "geofence_${stationId}_level5",
            "geofence_${stationId}_level4",
            "geofence_${stationId}_level3",
            "geofence_${stationId}_level2",
            "geofence_${stationId}_level1",
            "geofence_${stationId}_alert"
        )
        geofencingClient(context)
            .removeGeofences(requestIds)
            .addOnSuccessListener {
                Log.d(TAG, "Geofences removed for $stationId")
                Logger.log("GEOFENCE_REMOVED", stationId)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences for $stationId", e)
                Logger.log("GEOFENCE_REMOVE_FAILED", stationId, e.message)
            }
    }

    /**
     * Remove all geofences registered with this app.
     */
    fun removeAllGeofences(context: Context) {
        geofencingClient(context)
            .removeGeofences(getGeofencePendingIntent(context))
            .addOnSuccessListener {
                Log.d(TAG, "All geofences removed")
                Logger.log("GEOFENCE_ALL_REMOVED")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove all geofences", e)
                Logger.log("GEOFENCE_ALL_REMOVE_FAILED", extra = e.message)
            }
    }

    private fun buildGeofence(
        stationId: String,
        layer: String,
        lat: Double,
        lon: Double,
        radiusM: Float
    ): Geofence {
        return Geofence.Builder()
            .setRequestId("geofence_${stationId}_$layer")
            .setCircularRegion(lat, lon, radiusM)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(0) // Minimize latency (0ms is as fast as possible)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
    }
}