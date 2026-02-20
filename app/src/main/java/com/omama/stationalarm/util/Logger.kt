package com.omama.stationalarm.util

import android.util.Log

object Logger {
    fun log(eventType: String, stationId: String? = null, extra: String? = null, errorCode: Int? = null) {
        // This is a placeholder for the full CSV logging system.
        // For now, it logs to Android's Logcat.
        val message = "Event: $eventType, Station: ${stationId ?: "N/A"}, Extra: ${extra ?: "N/A"}, ErrorCode: ${errorCode ?: "N/A"}"
        Log.d("StationAlarmLogger", message)
    }
}
