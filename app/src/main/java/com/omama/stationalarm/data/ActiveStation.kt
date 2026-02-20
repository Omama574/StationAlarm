package com.omama.stationalarm.data

data class ActiveStation(
    val stationId: String,
    val alertDistanceKm: Double,
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    var currentDistanceKm: Double? = null,
    var lastPollTime: Long? = null
) {
    val outerRadius: Double get() = alertDistanceKm + 60
    val midRadius: Double get() = alertDistanceKm + 40
    val innerRadius: Double get() = alertDistanceKm + 30
}