package com.omama.stationalarm.data

import com.omama.stationalarm.repository.StationRepository

data class ActiveStation(
    val stationId: String,
    val alertDistanceKm: Double,      // 3.0 to 20.0
    val notify: Boolean,
    val vibrate: Boolean,
    val sound: Boolean,
    var currentDistanceKm: Double? = null,
    val customReminder: String? = null,
    val sendReminder: Boolean = false,
    val status: String = "MONITORING",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val stationName: String = ""
) {
    val radiusLevel5Km: Double get() = alertDistanceKm + 60
    val radiusLevel4Km: Double get() = alertDistanceKm + 40
    val radiusLevel3Km: Double get() = alertDistanceKm + 30
    val radiusLevel2Km: Double get() = alertDistanceKm + 5
    val radiusLevel1Km: Double get() = alertDistanceKm

    // Extended geofence tiers for long-haul trip coverage.
    // These act as OS-level wake-up tripwires that survive Doze, app kills,
    // and OEM battery managers — the most reliable component in our architecture.
    val radiusLevel6Km: Double get() = alertDistanceKm + 100
    val radiusLevel7Km: Double get() = alertDistanceKm + 150
    val radiusLevel8Km: Double get() = alertDistanceKm + 200

    /**
     * Resolves the Station object for this active station.
     * First checks hardcoded railway stations, then falls back to
     * the lat/lon persisted directly in the active_stations table.
     */
    fun getStation(): Station? {
        return StationRepository.getStationByIdSync(stationId)
            ?: if (lat != 0.0 || lon != 0.0) {
                Station(id = stationId, name = stationName.ifEmpty { "Custom Location" }, lat = lat, lon = lon)
            } else null
    }
}