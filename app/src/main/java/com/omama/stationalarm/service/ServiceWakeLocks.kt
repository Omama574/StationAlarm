package com.omama.stationalarm.service

import android.content.Context
import android.os.PowerManager
import com.omama.stationalarm.util.Logger

/**
 * Owns the two wake locks the alarm engine relies on:
 *
 *  - **alarm wake lock** — held only while an alarm is ringing, capped at 10 min.
 *  - **gps wake lock**   — held while GPS polling is active. Capped at 4 hours
 *    so the CPU never enters Light Doze between polls; without this, a
 *    5–10 minute nap at 200 km/h would mean missing the destination by
 *    10–15 km.
 *
 * Behaviour preserved verbatim from the original LocationService implementation.
 */
internal class ServiceWakeLocks(private val context: Context) {

    private var alarmLock: PowerManager.WakeLock? = null
    private var gpsLock: PowerManager.WakeLock? = null

    private fun powerManager() =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun acquireAlarm() {
        if (alarmLock == null) {
            alarmLock = powerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::AlarmWakeLock"
            )
        }
        alarmLock?.acquire(10 * 60 * 1000L)
    }

    fun releaseAlarm() {
        if (alarmLock?.isHeld == true) {
            alarmLock?.release()
        }
    }

    fun acquireGps() {
        if (gpsLock == null) {
            gpsLock = powerManager().newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StationAlarm::GpsWakeLock"
            )
            gpsLock?.setReferenceCounted(false)
        }
        if (gpsLock?.isHeld == false) {
            gpsLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours maximum
            Logger.log("WAKELOCK_ACQUIRED", extra = "GpsWakeLock")
        }
    }

    fun releaseGps() {
        if (gpsLock?.isHeld == true) {
            gpsLock?.release()
            Logger.log("WAKELOCK_RELEASED", extra = "GpsWakeLock")
        }
    }
}
