package com.omama.stationalarm.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.*
import com.omama.stationalarm.receiver.GeofenceBroadcastReceiver
import com.omama.stationalarm.repository.StationRepository
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val ACTION_GEOFENCE = "com.omama.stationalarm.ACTION_GEOFENCE"
    private const val MAX_RADIUS_METERS = 100_000.0f // 100 km cap

    private fun geofencingClient(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    private fun fusedClient(context: Context): FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    fun getTestPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            9999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    private fun getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        return PendingIntent.getBroadcast(
            context.applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    /**
     * Public entry — warms up location provider then registers geofences.
     */
    fun addGeofencesForStation(context: Context, stationId: String, alertDistanceKm: Double) {
        // permission check
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            safeLog("addGeofencesForStation - missing ACCESS_FINE_LOCATION permission")
            return
        }

        // find station via repository
        val station = StationRepository.getStationById(stationId)
        if (station == null) {
            safeLog("addGeofencesForStation - station not found: $stationId")
            return
        }

        // Warm up fused provider first. This avoids GEOFENCE_NOT_AVAILABLE in many cases.
        try {
            ensureFusedInitialized(context) {
                // after warmed-up, proceed to register
                registerGeofencesInternal(context, stationId, alertDistanceKm, station.lat, station.lon)
            }
        } catch (t: Throwable) {
            safeLog("addGeofencesForStation - warmup failed: ${t.message}")
            // still try registering once (best-effort)
            registerGeofencesInternal(context, stationId, alertDistanceKm, station.lat, station.lon)
        }
    }

    // Internal registration (assumes permission + station available)
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun registerGeofencesInternal(context: Context, stationId: String, alertDistanceKm: Double, lat: Double, lon: Double) {
        val outer = (((alertDistanceKm + 60.0) * 1000.0).coerceAtMost(MAX_RADIUS_METERS.toDouble())).toFloat()
        val mid = (((alertDistanceKm + 40.0) * 1000.0).coerceAtMost(MAX_RADIUS_METERS.toDouble())).toFloat()
        val inner = (((alertDistanceKm + 30.0) * 1000.0).coerceAtMost(MAX_RADIUS_METERS.toDouble())).toFloat()

        try {
            // try registering only the outer fence first if you still see failures later (uncomment that block)
            val geofences = listOf(
                buildGeofence(stationId, "outer", lat, lon, outer),
                buildGeofence(stationId, "mid", lat, lon, mid),
                buildGeofence(stationId, "inner", lat, lon, inner)
            )

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(0) // don't use INITIAL_TRIGGER_ENTER while debugging
                .addGeofences(geofences)
                .build()

            geofencingClient(context)
                .addGeofences(request, getGeofencePendingIntent(context))
                .addOnSuccessListener {
                    safeLog("GEOFENCE_REGISTERED $stationId outer=$outer mid=$mid inner=$inner")
                }
                .addOnFailureListener { e ->
                    val code = if (e is ApiException) e.statusCode else -1
                    val statusText = if (code >= 0) GeofenceStatusCodes.getStatusCodeString(code) else "N/A"
                    safeLog("GEOFENCE_REG_FAILED $stationId -> code=$code (${statusText}) message=${e.message}")
                }
        } catch (e: Exception) {
            safeLog("registerGeofencesInternal exception: ${e.message ?: e.toString()}")
        }
    }

    private fun buildGeofence(stationId: String, layer: String, lat: Double, lon: Double, radius: Float): Geofence {
        return Geofence.Builder()
            .setRequestId("geofence_${stationId}_$layer")
            .setCircularRegion(lat, lon, radius)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()
    }

    fun removeGeofencesForStation(context: Context, stationId: String) {
        val ids = listOf(
            "geofence_${stationId}_outer",
            "geofence_${stationId}_mid",
            "geofence_${stationId}_inner"
        )
        try {
            geofencingClient(context).removeGeofences(ids)
                .addOnSuccessListener { safeLog("GEOFENCE_REMOVED: $stationId") }
                .addOnFailureListener { e ->
                    val code = if (e is ApiException) e.statusCode else -1
                    safeLog("GEOFENCE_REMOVE_FAILED: $stationId -> code=$code message=${e.message}")
                }
        } catch (e: Exception) {
            safeLog("removeGeofencesForStation exception: ${e.message ?: e.toString()}")
        }
    }

    fun removeAllGeofences(context: Context) {
        try {
            geofencingClient(context).removeGeofences(getTestPendingIntent(context))
                .addOnSuccessListener { safeLog("GEOFENCE_ALL_REMOVED") }
                .addOnFailureListener { e -> safeLog("GEOFENCE_ALL_REMOVE_FAILED -> ${e.message ?: e.toString()}") }
        } catch (e: Exception) {
            safeLog("removeAllGeofences exception: ${e.message ?: e.toString()}")
        }
    }

    /**
     * Ensure fused provider is warmed up: try lastLocation, otherwise request a single high-accuracy update.
     * When ready, calls onReady().
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun ensureFusedInitialized(context: Context, onReady: () -> Unit) {
        val client = fusedClient(context)

        // try lastLocation first
        try {
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    safeLog("ensureFusedInitialized: lastLocation available, lat=${loc.latitude},lon=${loc.longitude}")
                    onReady(); return@addOnSuccessListener
                } else {
                    // lastLocation null -> request single quick update
                    safeLog("ensureFusedInitialized: lastLocation null, requesting one update")
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                        .setMinUpdateIntervalMillis(500L)
                        .setMaxUpdates(1)
                        .build()
                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            try {
                                client.removeLocationUpdates(this)
                            } catch (_: Exception) {}
                            safeLog("ensureFusedInitialized: single update received")
                            onReady()
                        }
                    }
                    try {
                        client.requestLocationUpdates(req, callback, Looper.getMainLooper())
                    } catch (se: SecurityException) {
                        throw se
                    }
                }
            }.addOnFailureListener @androidx.annotation.RequiresPermission(allOf = [android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION]) { e ->
                safeLog("ensureFusedInitialized.lastLocation failed: ${e.message}")
                // fallback: attempt to request update anyway
                try {
                    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
                        .setMinUpdateIntervalMillis(500L)
                        .setMaxUpdates(1)
                        .build()
                    val callback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            try { client.removeLocationUpdates(this) } catch (_: Exception) {}
                            safeLog("ensureFusedInitialized: single update received (fallback)")
                            onReady()
                        }
                    }
                    client.requestLocationUpdates(req, callback, Looper.getMainLooper())
                } catch (t: Throwable) {
                    safeLog("ensureFusedInitialized fallback failed: ${t.message}")
                    // still call onReady to attempt registration anyway
                    onReady()
                }
            }
        } catch (t: Throwable) {
            safeLog("ensureFusedInitialized top-level exception: ${t.message}")
            // try direct onReady so registration still attempted
            onReady()
        }
    }

    private fun safeLog(message: String) {
        try {
            val loggerClass = Class.forName("com.omama.stationalarm.util.Logger")
            val method = loggerClass.getMethod("log", String::class.java)
            method.invoke(null, message)
        } catch (_: Throwable) {
            Log.d(TAG, message)
        }
    }
}