package com.omama.stationalarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
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

    companion object {
        // Bridge wake lock: held from the start of onReceive() through
        // startForegroundService(). Without this, the CPU can suspend in the
        // window between goAsync()'s finish and LocationService acquiring its
        // own wake lock — under Doze / MIUI Deep Sleep this drops geofence
        // events entirely. Timeout-based so it self-releases even if our code
        // crashes between acquire and release.
        private const val STARTUP_LOCK_TIMEOUT_MS = 10_000L
        private var startupWakeLock: PowerManager.WakeLock? = null

        @Synchronized
        fun acquireStartupLock(context: Context) {
            val pm = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as PowerManager
            try { startupWakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
            startupWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::GeofenceStartup"
            ).also { it.acquire(STARTUP_LOCK_TIMEOUT_MS) }
        }

        @Synchronized
        fun releaseStartupLock() {
            try {
                startupWakeLock?.takeIf { it.isHeld }?.release()
            } catch (_: Exception) {}
            startupWakeLock = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // Acquire BEFORE any other work so the CPU stays awake through goAsync,
        // the IO coroutine, and startForegroundService. Auto-released after
        // 10s by the timeout if our explicit release in the finally fails.
        acquireStartupLock(context)

        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent == null) {
            Log.e(TAG, "No geofencing event in intent")
            releaseStartupLock()
            return
        }

        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofence error: ${geofencingEvent.errorCode}")
            Logger.log("GEOFENCE_ERROR", extra = "errorCode=${geofencingEvent.errorCode}")
            releaseStartupLock()
            return
        }

        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: emptyList()
        if (triggeringGeofences.isEmpty()) {
            Log.d(TAG, "No triggering geofences")
            releaseStartupLock()
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
                    val parts = requestId.split("_", limit = 3)
                    if (parts.size != 3) {
                        Log.w(TAG, "Invalid requestId format: $requestId")
                        continue
                    }
                    val stationId = parts[1]
                    val layer = parts[2]

                    // Verify station is still armed (not deleted, not PAUSED).
                    // PAUSED rows linger after dismissal while geofence removal is
                    // in flight — events delivered in that window must be dropped,
                    // otherwise the alarm re-fires seconds after being dismissed.
                    if (!StationRepository.isArmed(stationId)) {
                        Log.d(TAG, "Station $stationId is not armed, ignoring geofence")
                        continue
                    }

                    Log.d(TAG, "Geofence triggered: $stationId, layer=$layer")
                    Logger.log("GEOFENCE_TRIGGERED", stationId, layer)

                    if (layer == "alert") {
                        // Suspending variant: ensures the DB write + geofence
                        // removal complete before we move on, so a subsequent
                        // resetToMonitoring on the failure path can't race the
                        // markAlerting write and resurrect a stale ALERTING row.
                        StationRepository.markAlertingSync(stationId)
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
                    try {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } catch (e: Exception) {
                        // BackgroundServiceStartNotAllowed or vendor security
                        // exceptions can leave DB in ALERTING with no service
                        // running. Reset so a later trigger can retry cleanly.
                        Log.e(TAG, "startForegroundService failed for $stationId", e)
                        Logger.log("SERVICE_START_FAILED", stationId, e.message)
                        if (layer == "alert") {
                            try { StationRepository.resetToMonitoring(stationId) } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing geofence", e)
                Logger.log("ERROR", extra = "Geofence processing: ${e.message}")
            } finally {
                pendingResult.finish()
                releaseStartupLock()
            }
        }
    }
}