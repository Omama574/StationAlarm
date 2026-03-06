package com.omama.stationalarm.data

/**
 * A user-defined favorite location created from the Map Search tab.
 * These are stored in the local Room DB and can be used to set an alarm
 * without making another API call.
 */
data class SavedPlace(
    val id: String,           // UUID e.g. "custom-550e8400-e29b..."
    val name: String,         // User-defined, e.g. "Coimbatore Bus Stand"
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,     // User-selected radius for the alarm (1–10 km)
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Convert to a Station so it can plug into the existing alarm pipeline. */
    fun toStation(): Station = Station(id = id, name = name, lat = lat, lon = lon)
}
