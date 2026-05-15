package com.omama.stationalarm.service.gps

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.omama.stationalarm.util.Logger

/**
 * Wraps the [FusedLocationProviderClient] and monitors its health.
 *
 * When [consecutiveFailures] reaches [NUKE_THRESHOLD] (3), or a single recovery
 * attempt times out after [SINGLE_TIMEOUT_MS] (15 s), the Guardian performs a
 * **nuclear reset**: it tears down the old FLP client, creates a brand-new one
 * from scratch (fresh IPC handle), and re-registers the location callback.
 *
 * This directly fixes the 50-minute blackout bug where the IPC pipe between
 * the app and Google Play Services went stale and all recovery attempts on the
 * same dead client handle failed silently.
 *
 * After a nuclear reset, further resets are suppressed for [COOLDOWN_MS] (60 s)
 * to prevent rapid-fire client recreation that could cause GMS to throttle
 * the new client.
 */
class FlpClientGuardian(
    private val context: Context,
    /** Called with the new client when a nuclear reset creates a fresh one. */
    private val onClientRecreated: (FusedLocationProviderClient) -> Unit,
    /** Called when a successful one-shot fix arrives during recovery. */
    private val onRecoveryFix: (android.location.Location) -> Unit,
    /** Called when recovery confirms no fix (null or failure). */
    private val onRecoveryFailed: (reason: String) -> Unit
) {

    companion object {
        /** Number of consecutive failures before nuking the FLP client. */
        private const val NUKE_THRESHOLD = 3
        /** Suppress further nukes for this long after one fires. */
        private const val COOLDOWN_MS = 60_000L
        /** Timeout for a single recovery attempt. If no response after this,
         *  it counts as a failure AND triggers an immediate nuke. */
        private const val SINGLE_TIMEOUT_MS = 15_000L
    }

    /** Current FLP client — swapped on nuclear reset. */
    var client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
        private set

    private var consecutiveFailures = 0
    private var lastNukeTimestamp = 0L
    private var recoveryToken: CancellationTokenSource? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pendingTimeoutRunnable: Runnable? = null

    /** The current location request — saved so it can be replayed after a nuke. */
    private var savedRequest: LocationRequest? = null
    /** The current location callback — re-created after a nuke. */
    private var savedCallbackFactory: (() -> LocationCallback)? = null
    private var currentCallback: LocationCallback? = null

    /**
     * Saves the request and callback factory so the Guardian can replay them
     * after a nuclear reset. Must be called before [startLocationUpdates].
     */
    fun configure(
        request: LocationRequest,
        callbackFactory: () -> LocationCallback,
        callback: LocationCallback
    ) {
        savedRequest = request
        savedCallbackFactory = callbackFactory
        currentCallback = callback
    }

    /**
     * Register location updates on the current FLP client.
     * Requires [configure] to have been called first.
     */
    fun startLocationUpdates() {
        val req = savedRequest ?: return
        val cb = currentCallback ?: return
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
    }

    /**
     * Remove location updates from the current FLP client.
     */
    fun stopLocationUpdates() {
        currentCallback?.let { client.removeLocationUpdates(it) }
    }

    /**
     * Called on every successful location fix (from onLocationResult OR
     * from a successful one-shot recovery). Resets the failure counter.
     */
    fun onFixReceived() {
        consecutiveFailures = 0
        cancelPendingTimeout()
    }

    /**
     * Attempt a one-shot GPS recovery. Tracks success/failure and escalates
     * to nuclear reset when thresholds are breached.
     */
    fun attemptRecovery() {
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        recoveryToken?.cancel()
        val token = CancellationTokenSource()
        recoveryToken = token

        // Start the 15s deadline timer
        cancelPendingTimeout()
        val timeoutRunnable = Runnable {
            // No response at all from GMS within 15s — IPC pipe is very likely dead.
            consecutiveFailures++
            Logger.log("FLP_GUARDIAN_TIMEOUT", extra = "15s timeout, consecutive=$consecutiveFailures")
            onRecoveryFailed("15s timeout")
            // A single 15s timeout is strong enough evidence to nuke immediately
            performNuclearResetIfNeeded(forceNuke = true)
        }
        pendingTimeoutRunnable = timeoutRunnable
        handler.postDelayed(timeoutRunnable, SINGLE_TIMEOUT_MS)

        try {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                .addOnSuccessListener { loc ->
                    cancelPendingTimeout()
                    if (loc != null) {
                        consecutiveFailures = 0
                        Logger.log("FLP_GUARDIAN_RECOVERY_OK", extra = "fix received")
                        onRecoveryFix(loc)
                    } else {
                        consecutiveFailures++
                        Logger.log("FLP_GUARDIAN_RECOVERY_NULL", extra = "consecutive=$consecutiveFailures")
                        onRecoveryFailed("null location")
                        performNuclearResetIfNeeded(forceNuke = false)
                    }
                }
                .addOnFailureListener { e ->
                    cancelPendingTimeout()
                    consecutiveFailures++
                    Logger.log("FLP_GUARDIAN_RECOVERY_FAILED", extra = "reason=${e.message}, consecutive=$consecutiveFailures")
                    onRecoveryFailed("GMS failure: ${e.message}")
                    performNuclearResetIfNeeded(forceNuke = false)
                }
        } catch (e: SecurityException) {
            cancelPendingTimeout()
            Logger.log("FLP_GUARDIAN_SECURITY_ERROR", extra = e.message ?: "security")
        }
    }

    /**
     * Get the current number of consecutive failures (for telemetry/CSV).
     */
    fun getConsecutiveFailures(): Int = consecutiveFailures

    /**
     * Get the age of the current FLP client in seconds (for telemetry/CSV).
     * Returns 0 if the client was just created.
     */
    fun getClientAgeSeconds(): Long {
        return if (lastNukeTimestamp == 0L) 0L
        else (System.currentTimeMillis() - lastNukeTimestamp) / 1000
    }

    private fun performNuclearResetIfNeeded(forceNuke: Boolean) {
        val now = System.currentTimeMillis()
        val cooldownElapsed = now - lastNukeTimestamp >= COOLDOWN_MS

        if (!cooldownElapsed) {
            Logger.log("FLP_GUARDIAN_NUKE_SUPPRESSED", extra = "cooldown active, age=${(now - lastNukeTimestamp) / 1000}s")
            return
        }

        if (!forceNuke && consecutiveFailures < NUKE_THRESHOLD) {
            return // Not enough failures yet
        }

        // === NUCLEAR RESET ===
        Logger.log("FLP_NUCLEAR_RESET", extra = "consecutive_failures=$consecutiveFailures, forced=$forceNuke")

        // 1. Tear down old client
        currentCallback?.let { client.removeLocationUpdates(it) }

        // 2. Create fresh FLP client (new IPC handle from GMS)
        val newClient = LocationServices.getFusedLocationProviderClient(context)

        // 3. Create fresh callback (identical body, fresh object reference)
        val newCallback = savedCallbackFactory?.invoke()

        // 4. Swap references
        client = newClient
        if (newCallback != null) {
            currentCallback = newCallback
        }
        lastNukeTimestamp = now
        consecutiveFailures = 0

        // 5. Notify LocationService to update its reference
        onClientRecreated(newClient)

        // 6. Re-register location updates on the new client
        val req = savedRequest
        val cb = currentCallback
        if (req != null && cb != null) {
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
            ) {
                newClient.requestLocationUpdates(req, cb, Looper.getMainLooper())
                Logger.log("FLP_GUARDIAN_REREGISTERED", extra = "new client active")
            }
        }
    }

    private fun cancelPendingTimeout() {
        pendingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
    }

    fun destroy() {
        cancelPendingTimeout()
        recoveryToken?.cancel()
        stopLocationUpdates()
    }
}
