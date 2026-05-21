package com.omama.stationalarm.service.gps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.omama.stationalarm.util.Analytics
import com.omama.stationalarm.util.Logger

/**
 * Central coordinator for GPS reliability layers L2, L3, and L5 from the
 * implementation plan:
 *
 *  - **Cold-Start Hunter (L3):** Aggressive 90s first-fix mode on session start.
 *    Sets polling to 5s and arms the emergency fallback simultaneously to get
 *    *any* location ASAP. Settles into normal gearbox after first fix.
 *
 *  - **Emergency Coarse Fallback (L2):** Uses raw [LocationManager.NETWORK_PROVIDER]
 *    (bypasses Google Play Services entirely) when FLP is stalling. Immune to the
 *    GMS IPC pipe stall that caused the 50-minute blackout.
 *
 *  - **GNSS Status Monitor (L5):** Satellite telemetry for CSV logging and
 *    watchdog hints. Tracks visible vs in-fix satellite counts.
 *
 * LocationService creates this coordinator in `onCreate` and calls its lifecycle
 * hooks. The coordinator never touches alarm logic — it only produces locations
 * and telemetry.
 */
class GpsReliabilityCoordinator(
    private val context: Context,
    /** Called when a coarse fallback fix arrives. LocationService feeds this
     *  into processLocationUpdate (with quality gate inflating accuracy). */
    private val onCoarseFix: (Location) -> Unit,
    /** Called when cold-start gets its first fix or times out. */
    private val onColdStartComplete: () -> Unit
) {

    companion object {
        /** Cold-start hunting duration in milliseconds. */
        private const val COLD_START_DURATION_MS = 90_000L
        /** Coarse fallback polling interval (cell tower). */
        private const val COARSE_INTERVAL_MS = 30_000L
        /** Minimum continuous good FLP fixes to disarm coarse fallback. */
        private const val COARSE_DISARM_AFTER_GOOD_FIXES = 3
    }

    // ========================
    //   COLD-START HUNTER (L3)
    // ========================

    /** True while the 90s aggressive first-fix mode is active. */
    var isColdStartActive = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var coldStartTimeoutRunnable: Runnable? = null

    /**
     * Begins the cold-start hunt. Arms coarse fallback simultaneously.
     * Called by LocationService when `lastLocationTimeMs == 0` (first poll ever).
     */
    fun startColdStartHunt() {
        if (isColdStartActive) return
        isColdStartActive = true
        Logger.breadcrumb("COLD_START_BEGUN", extra = "arming coarse fallback + 90s timer")

        // Arm the coarse fallback immediately so we get a cell-tower fix
        // while the GPS chip is still cold-searching for satellites.
        armCoarseFallback()

        // Set a 90s deadline — after this, cold-start is over regardless.
        // Capture the runnable in a local val before posting: another thread
        // calling completeColdStart() (e.g. from onFirstFixReceived) can
        // null `coldStartTimeoutRunnable` between the assignment and the
        // `!!` deref on the next line — a real NPE in production crash logs.
        val runnable = Runnable {
            Logger.breadcrumb("COLD_START_TIMEOUT", extra = "90s elapsed without a fix")
            // 90s with no fix is a real reliability event — many users will
            // see this on the first session after install on cold devices.
            Analytics.event("gps_cold_start_timeout")
            completeColdStart()
        }
        coldStartTimeoutRunnable = runnable
        handler.postDelayed(runnable, COLD_START_DURATION_MS)
    }

    /**
     * Called when the first GPS fix arrives. Ends cold-start mode.
     */
    fun onFirstFixReceived() {
        if (isColdStartActive) {
            Logger.log("COLD_START_FIRST_FIX", extra = "settled into normal gearbox")
            completeColdStart()
        }
    }

    private fun completeColdStart() {
        isColdStartActive = false
        coldStartTimeoutRunnable?.let { handler.removeCallbacks(it) }
        coldStartTimeoutRunnable = null
        onColdStartComplete()
        // Don't disarm coarse fallback here — let it be disarmed by
        // COARSE_DISARM_AFTER_GOOD_FIXES good FLP fixes naturally.
    }

    // ================================
    //   EMERGENCY COARSE FALLBACK (L2)
    // ================================

    /** True when the raw LocationManager listener is active. */
    var isCoarseArmed = false
        private set

    private var coarseLocationManager: LocationManager? = null
    private var coarseListener: LocationListener? = null
    private var consecutiveGoodFlpFixes = 0

    /**
     * Arms the raw LocationManager NETWORK_PROVIDER listener.
     * This bypasses Google Play Services entirely — immune to IPC pipe stalls.
     */
    fun armCoarseFallback() {
        if (isCoarseArmed) return
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // Check if NETWORK_PROVIDER is available
        if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            Logger.breadcrumb("COARSE_FALLBACK_UNAVAILABLE", extra = "NETWORK_PROVIDER disabled")
            return
        }

        coarseLocationManager = lm
        consecutiveGoodFlpFixes = 0

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                // Floor the accuracy to at least 1000m for cell-tower fixes
                // so the quality gate treats them conservatively.
                val coarseLoc = Location(location).apply {
                    if (accuracy < 1000f) accuracy = 1000f
                }
                Logger.log("COARSE_FIX", extra = "lat=${coarseLoc.latitude}, lon=${coarseLoc.longitude}, acc=${coarseLoc.accuracy}m")
                onCoarseFix(coarseLoc)
            }

            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                Logger.log("COARSE_PROVIDER_DISABLED", extra = provider)
            }
        }

        coarseListener = listener

        try {
            lm.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                COARSE_INTERVAL_MS,
                0f, // No minimum distance — we want every update
                listener,
                Looper.getMainLooper()
            )
            isCoarseArmed = true
            Logger.log("COARSE_FALLBACK_ARMED", extra = "interval=${COARSE_INTERVAL_MS}ms")
        } catch (e: Exception) {
            Logger.error("COARSE_FALLBACK_FAILED", e)
        }
    }

    /**
     * Called on every successful FLP fix (high accuracy or balanced power).
     * After [COARSE_DISARM_AFTER_GOOD_FIXES] consecutive good fixes
     * (accuracy < 50m), the coarse fallback is disarmed.
     */
    fun onGoodFlpFix(location: Location) {
        if (!isCoarseArmed) return

        // We only care that the FLP IPC pipe is alive and delivering fixes.
        // A < 50m requirement is too strict for BALANCED power (cell tower) fixes.
        // As long as the fix isn't comically inaccurate (> 5000m), we count it as a healthy IPC heartbeat.
        if (location.accuracy <= 5000f) {
            consecutiveGoodFlpFixes++
            if (consecutiveGoodFlpFixes >= COARSE_DISARM_AFTER_GOOD_FIXES) {
                disarmCoarseFallback()
            }
        } else {
            // Reset counter if a bad FLP fix arrives
            consecutiveGoodFlpFixes = 0
        }
    }

    /**
     * Disarm the coarse fallback. Called when FLP is healthy again.
     */
    fun disarmCoarseFallback() {
        if (!isCoarseArmed) return

        coarseListener?.let { listener ->
            coarseLocationManager?.removeUpdates(listener)
        }
        coarseListener = null
        coarseLocationManager = null
        isCoarseArmed = false
        consecutiveGoodFlpFixes = 0
        Logger.log("COARSE_FALLBACK_DISARMED", extra = "FLP healthy, $COARSE_DISARM_AFTER_GOOD_FIXES good fixes")
    }

    // ================================
    //   GNSS STATUS MONITOR (L5)
    // ================================

    /** Number of satellites currently visible to the GPS chip. */
    var satellitesVisible = 0
        private set

    /** Number of satellites actually used in the current fix. */
    var satellitesInFix = 0
        private set

    private var gnssCallback: GnssStatus.Callback? = null

    /**
     * Starts monitoring GNSS satellite status for telemetry.
     * Only available on Android N (API 24) and above.
     */
    fun startGnssMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                satellitesVisible = status.satelliteCount
                var usedCount = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedCount++
                }
                satellitesInFix = usedCount
            }
        }

        gnssCallback = callback
        try {
            lm.registerGnssStatusCallback(callback, handler)
            Logger.log("GNSS_MONITOR_STARTED")
        } catch (e: Exception) {
            Logger.error("GNSS_MONITOR_FAILED", e)
        }
    }

    /**
     * Stops GNSS monitoring.
     */
    fun stopGnssMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        gnssCallback?.let {
            lm.unregisterGnssStatusCallback(it)
        }
        gnssCallback = null
    }

    // ================================
    //   WATCHDOG ESCALATION
    // ================================

    /**
     * Classification of stall severity for the Watchdog to act on.
     */
    enum class StallAction {
        /** No action needed — stall is within normal bounds. */
        NORMAL_RETRY,
        /** Arm the coarse fallback — FLP is struggling. */
        ARM_COARSE,
        /** Nuclear reset — FLP pipe is dead. */
        NUKE_FLP
    }

    /**
     * Called by the Watchdog to determine what escalation action to take.
     *
     * @param stallMs Time since last successful fix in milliseconds.
     * @param consecutiveGuardianFailures From FlpClientGuardian.
     * @return Recommended action.
     */
    fun classifyStall(stallMs: Long, consecutiveGuardianFailures: Int): StallAction {
        // If the guardian has already accumulated 3+ failures, nuke is imminent
        // or already happened — don't also arm coarse (guardian handles it).
        if (consecutiveGuardianFailures >= 3) return StallAction.NUKE_FLP

        // If stall exceeds 3 minutes and coarse isn't already armed, arm it
        if (stallMs >= 180_000L && !isCoarseArmed) return StallAction.ARM_COARSE

        return StallAction.NORMAL_RETRY
    }

    // ================================
    //   LIFECYCLE
    // ================================

    fun destroy() {
        // Cold start
        coldStartTimeoutRunnable?.let { handler.removeCallbacks(it) }
        coldStartTimeoutRunnable = null
        isColdStartActive = false

        // Coarse fallback
        disarmCoarseFallback()

        // GNSS monitor
        stopGnssMonitoring()
    }
}
