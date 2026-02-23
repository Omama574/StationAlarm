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

    /**
     * Register three geofences for a station with the given radii (in meters).
     */
    fun addGeofencesForStation(
        context: Context,
        stationId: String,
        outerRadiusM: Float,
        midRadiusM: Float,
        innerRadiusM: Float
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
        val station = com.omama.stationalarm.repository.StationRepository.getStationById(stationId)
        if (station == null) {
            Log.e(TAG, "Station not found: $stationId")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Station not found")
            return
        }

        val geofences = listOf(
            buildGeofence(stationId, "outer", station.lat, station.lon, outerRadiusM),
            buildGeofence(stationId, "mid", station.lat, station.lon, midRadiusM),
            buildGeofence(stationId, "inner", station.lat, station.lon, innerRadiusM)
        )

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient(context)
            .addGeofences(request, getGeofencePendingIntent(context))
            .addOnSuccessListener {
                Log.d(TAG, "Geofences added for $stationId")
                Logger.log("GEOFENCE_REGISTERED", stationId, "radii=${outerRadiusM}m,${midRadiusM}m,${innerRadiusM}m")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to add geofences for $stationId", e)
                Logger.log("GEOFENCE_REG_FAILED", stationId, e.message)
            }
    }

    /**
     * Remove all three geofences for a station.
     */
    fun removeGeofencesForStation(context: Context, stationId: String) {
        val requestIds = listOf(
            "geofence_${stationId}_outer",
            "geofence_${stationId}_mid",
            "geofence_${stationId}_inner"
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
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
    }
}