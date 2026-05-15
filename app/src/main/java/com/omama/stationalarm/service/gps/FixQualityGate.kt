package com.omama.stationalarm.service.gps

import android.location.Location

/**
 * Pure function that classifies incoming GPS fixes as [Accept] or [DemoteToHint]
 * based on the fix accuracy relative to the distance from the nearest station.
 *
 * A demoted fix is NEVER thrown away — it still updates the notification, the
 * dead-reckoner anchor, and the gearbox. It just cannot trigger the alarm.
 * The existing accuracy-buffer math in LocationService (distance - accuracyKm)
 * continues to gate the alarm; the gate only *inflates* accuracy for low-quality
 * fixes to make that buffer more conservative.
 */
object FixQualityGate {

    sealed class Decision {
        /**
         * Fix is good enough for alarm evaluation at this distance.
         * [effectiveAccuracyMeters] is the reported accuracy (unchanged).
         */
        data class Accept(val effectiveAccuracyMeters: Float) : Decision()

        /**
         * Fix is too inaccurate for alarm evaluation at this distance.
         * [effectiveAccuracyMeters] is inflated to the demote threshold,
         * making the alert-buffer math in LocationService more conservative.
         * The fix is still used for UI, gearbox, and reckoner updates.
         */
        data class DemoteToHint(val effectiveAccuracyMeters: Float, val reason: String) : Decision()
    }

    /**
     * Evaluates a [location] fix against the [minDistanceKm] to the nearest
     * monitored station.
     *
     * @param location       The incoming GPS/network fix.
     * @param minDistanceKm  Distance to the nearest monitored station in km.
     *                       Pass [Double.MAX_VALUE] if no stations are being monitored.
     * @return [Decision.Accept] or [Decision.DemoteToHint] with an inflated accuracy.
     */
    fun evaluate(location: Location, minDistanceKm: Double): Decision {
        val accuracyMeters = location.accuracy

        // Determine the demote threshold based on distance band
        val demoteThresholdMeters = when {
            minDistanceKm > 60.0  -> Float.MAX_VALUE   // Accept anything at long range
            minDistanceKm > 30.0  -> 2000f             // 30–60 km
            minDistanceKm > 15.0  -> 1000f             // 15–30 km
            minDistanceKm > 5.0   -> 500f              // 5–15 km
            else                  -> 200f              // ≤ 5 km (alarm zone)
        }

        return if (accuracyMeters <= demoteThresholdMeters) {
            Decision.Accept(effectiveAccuracyMeters = accuracyMeters)
        } else {
            // Inflate accuracy to the threshold so the alert-buffer math
            // in LocationService (distance - accuracyKm) becomes more
            // conservative. This makes it harder for a bad fix to trigger
            // an alarm, without discarding the fix entirely.
            Decision.DemoteToHint(
                effectiveAccuracyMeters = maxOf(accuracyMeters, demoteThresholdMeters),
                reason = "accuracy=${accuracyMeters}m > threshold=${demoteThresholdMeters}m at dist=${String.format("%.1f", minDistanceKm)}km"
            )
        }
    }
}
