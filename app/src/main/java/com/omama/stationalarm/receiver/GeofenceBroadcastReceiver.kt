package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.omama.stationalarm.service.LocationService

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
            return
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        if (triggeringGeofences.isEmpty()) {
            Log.d(TAG, "No triggering geofences")
            return
        }

        val transition = geofencingEvent.geofenceTransition
        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d(TAG, "Entered geofence – starting location service")

            val serviceIntent = Intent(context, LocationService::class.java).apply {
                action = "START_ALARM_SERVICE"
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}