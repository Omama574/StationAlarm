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
import com.omama.stationalarm.repository.StationRepository

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
        radiusMeters: Float
    ) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing location permission")
            return
        }

        val station = StationRepository.getStationById(stationId)
        if (station == null) {
            Log.e(TAG, "Station not found: $stationId")
            return
        }

        // Create a single geofence (you can add multiple if needed)
        val geofence = Geofence.Builder()
            .setRequestId("geofence_${stationId}")
            .setCircularRegion(station.lat, station.lon, radiusMeters)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        geofencingClient(context)
            .addGeofences(request, getGeofencePendingIntent(context))
            .addOnSuccessListener {
//                Log.d(TAG, "Geofence added for $stationId (radius ${radiusMeters}m)")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to add geofence for $stationId", e)
            }
    }
    fun removeGeofencesForStation(context: Context, stationId: String) {
        val requestId = "geofence_${stationId}"

        geofencingClient(context)
            .removeGeofences(listOf(requestId))
            .addOnSuccessListener {
                Log.d(TAG, "Geofence removed for $stationId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofence for $stationId", e)
            }
    }
    fun removeAllGeofences(context: Context) {
        geofencingClient(context)
            .removeGeofences(getGeofencePendingIntent(context))
            .addOnSuccessListener {
                Log.d(TAG, "All geofences removed")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to remove geofences", e)
            }
    }
}