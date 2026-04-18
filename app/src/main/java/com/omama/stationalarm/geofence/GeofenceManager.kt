package com.omama.stationalarm.geofence

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.omama.stationalarm.receiver.GeofenceBroadcastReceiver
import com.omama.stationalarm.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val ACTION_GEOFENCE = "com.omama.stationalarm.ACTION_GEOFENCE"
    const val MAX_ACTIVE_STATIONS = 10

    /** Typed failure reasons so the UI can show specific messages. */
    sealed class GeofenceRegistrationException(message: String) : Exception(message) {
        class MissingPermission : GeofenceRegistrationException("Location permission is required.")
        class StationNotFound(id: String) : GeofenceRegistrationException("Station $id not found.")
        class MaxStationsReached : GeofenceRegistrationException(
            "You have reached the maximum of $MAX_ACTIVE_STATIONS alarms. Please delete some before adding more."
        )
        class RegistrationFailed(cause: Throwable?) : GeofenceRegistrationException(
            cause?.message ?: "Failed to register geofence. Try again."
        )
    }

    private fun geofencingClient(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    private fun getGeofencePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_GEOFENCE
        }
        // GeofencingClient requires FLAG_MUTABLE for geofence PendingIntents
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Registers the 6-tier geofence stack for [stationId]. Returns a [Result]
     * whose failure carries a typed [GeofenceRegistrationException] so callers
     * can show the right user-facing message (e.g. snackbar). Previously this
     * failed silently, leaving the UI showing a "registered" alarm that would
     * never actually fire.
     */
    suspend fun addGeofencesForStation(
        context: Context,
        stationId: String,
        radiusLevel5M: Float,
        radiusLevel4M: Float,
        radiusLevel3M: Float,
        radiusLevel2M: Float,
        radiusLevel1M: Float,
        alertDistanceM: Float
    ): Result<Unit> {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Missing location permission")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Missing permission")
            return Result.failure(GeofenceRegistrationException.MissingPermission())
        }

        // Use the suspend version to ensure we hit the DB if memory cache is cold (e.g. on boot)
        val station = com.omama.stationalarm.repository.StationRepository.getStationById(stationId)
        if (station == null) {
            Log.e(TAG, "Station not found: $stationId")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Station not found")
            return Result.failure(GeofenceRegistrationException.StationNotFound(stationId))
        }

        // Clamp alert geofence radius to Android's minimum of 100m
        val clampedAlertM = maxOf(alertDistanceM, 100f)

        // Hardcap: max 10 destinations
        val activeStations = com.omama.stationalarm.repository.StationRepository.getAllActiveStationsList()
        if (activeStations.size >= MAX_ACTIVE_STATIONS && !activeStations.any { it.stationId == stationId }) {
            Log.e(TAG, "Maximum $MAX_ACTIVE_STATIONS destinations allowed")
            Logger.log("GEOFENCE_REG_FAILED", stationId, "Max $MAX_ACTIVE_STATIONS destinations reached")
            return Result.failure(GeofenceRegistrationException.MaxStationsReached())
        }

        val geofences1To5 = listOf(
            buildGeofence(stationId, "level5", station.lat, station.lon, radiusLevel5M),
            buildGeofence(stationId, "level4", station.lat, station.lon, radiusLevel4M),
            buildGeofence(stationId, "level3", station.lat, station.lon, radiusLevel3M),
            buildGeofence(stationId, "level2", station.lat, station.lon, radiusLevel2M),
            buildGeofence(stationId, "level1", station.lat, station.lon, radiusLevel1M)
        )
        val alertGeofence = buildGeofence(stationId, "alert", station.lat, station.lon, clampedAlertM)

        val requestLevel = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences1To5)
            .build()
            
        val requestAlert = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(listOf(alertGeofence))
            .build()

        var retryCount = 0
        val maxRetries = 3
        var delayMs = 2000L
        var lastError: Throwable? = null

        while (retryCount < maxRetries) {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    geofencingClient(context)
                        .addGeofences(requestLevel, getGeofencePendingIntent(context))
                        .addOnSuccessListener {
                            geofencingClient(context)
                                .addGeofences(requestAlert, getGeofencePendingIntent(context))
                                .addOnSuccessListener {
                                    Log.d(TAG, "Geofences added for $stationId")
                                    Logger.log("GEOFENCE_REGISTERED", stationId, "radii=[..., alert=$clampedAlertM]")
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                .addOnFailureListener { e ->
                                    Log.e(TAG, "Failed to add alert geofence for $stationId", e)
                                    Logger.log("GEOFENCE_REG_FAILED", stationId, e.message)
                                    if (cont.isActive) cont.resumeWithException(e)
                                }
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "Failed to add level geofences for $stationId", e)
                            Logger.log("GEOFENCE_REG_FAILED", stationId, e.message)
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                }
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
                retryCount++
                if (retryCount < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                } else {
                    Log.e(TAG, "Geofence registration permanently failed for $stationId after $maxRetries attempts")
                    Logger.log("GEOFENCE_REG_FAILED_PERMANENT", stationId, e.message)
                }
            }
        }
        return Result.failure(GeofenceRegistrationException.RegistrationFailed(lastError))
    }

    /**
     * Remove all six geofences for a station with bounded retry. On terminal
     * failure, schedules a persistent [GeofenceCleanupWorker] so leaked
     * geofences don't accumulate against GMS's 100/app cap over time.
     *
     * The previous fire-and-forget implementation could leave 6 dead geofences
     * per failed teardown; combined with the 10-station cap that's up to 60
     * leaks before symptoms surface as registration failures.
     */
    suspend fun removeGeofencesForStation(context: Context, stationId: String) {
        val result = attemptRemoveGeofencesForStation(context, stationId)
        if (result.isFailure) {
            Logger.log("GEOFENCE_REMOVE_FALLBACK", stationId, "enqueueing cleanup worker")
            GeofenceCleanupWorker.enqueue(context, stationId)
        }
    }

    /**
     * Single removal attempt with 3 retries (500ms → 1s → 2s backoff). Returns
     * Result.failure on terminal failure so [GeofenceCleanupWorker] can call
     * this directly without re-triggering the fallback enqueue (which would
     * spawn an infinite chain).
     */
    internal suspend fun attemptRemoveGeofencesForStation(
        context: Context,
        stationId: String
    ): Result<Unit> {
        val requestIds = listOf(
            "geofence_${stationId}_level5",
            "geofence_${stationId}_level4",
            "geofence_${stationId}_level3",
            "geofence_${stationId}_level2",
            "geofence_${stationId}_level1",
            "geofence_${stationId}_alert"
        )

        var attempt = 0
        val maxAttempts = 3
        var delayMs = 500L
        var lastError: Throwable? = null

        while (attempt < maxAttempts) {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    geofencingClient(context)
                        .removeGeofences(requestIds)
                        .addOnSuccessListener {
                            Log.d(TAG, "Geofences removed for $stationId")
                            Logger.log("GEOFENCE_REMOVED", stationId)
                            if (cont.isActive) cont.resume(Unit)
                        }
                        .addOnFailureListener { e ->
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                }
                return Result.success(Unit)
            } catch (e: Exception) {
                lastError = e
                attempt++
                Log.e(TAG, "Failed to remove geofences for $stationId (attempt $attempt)", e)
                if (attempt < maxAttempts) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }

        Logger.log("GEOFENCE_REMOVE_FAILED", stationId, lastError?.message)
        return Result.failure(lastError ?: Exception("Unknown geofence removal failure"))
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
