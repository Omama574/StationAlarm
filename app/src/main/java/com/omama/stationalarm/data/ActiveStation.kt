package com.omama.stationalarm.data

data class ActiveStation(
    val stationId: String,
    val alertDistanceKm: Double,      // 3.0 to 10.0, step 0.5
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    var currentDistanceKm: Double? = null
) {
    val outerRadiusKm: Double get() = alertDistanceKm + 60
    val midRadiusKm: Double get() = alertDistanceKm + 40
    val innerRadiusKm: Double get() = alertDistanceKm + 30

    // No context needed now
    fun getStation(): Station? = StationData.getStationById(stationId)
}