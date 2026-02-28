package com.omama.stationalarm.data

data class ActiveStation(
    val stationId: String,
    val alertDistanceKm: Double,      // 3.0 to 10.0, step 0.5
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    var currentDistanceKm: Double? = null,
    val customReminder: String? = null,
    val sendReminder: Boolean = false
) {
    val radiusLevel5Km: Double get() = alertDistanceKm + 60
    val radiusLevel4Km: Double get() = alertDistanceKm + 40
    val radiusLevel3Km: Double get() = alertDistanceKm + 30
    val radiusLevel2Km: Double get() = alertDistanceKm + 5
    val radiusLevel1Km: Double get() = alertDistanceKm

    // No context needed now
    fun getStation(): Station? = StationData.getStationById(stationId)
}