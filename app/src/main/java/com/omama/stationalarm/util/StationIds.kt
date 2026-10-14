package com.omama.stationalarm.util

/**
 * Normalises station IDs before they are stored or used to build geofence request IDs.
 *
 * Geofence request IDs have the shape `geofence_<stationId>_<layer>` and
 * GeofenceBroadcastReceiver splits them on `_`. An underscore inside the station ID shifts that split,
 * the receiver then looks up the wrong station, and every hardware geofence event for the alarm is
 * silently dropped — no wake-up rings and no alert geofence. Geocoder results use IDs such as
 * `liq_481312177` (and `liq_rev_…` for reverse lookups), so any ID that does not come from the offline
 * railway list has to pass through [sanitize].
 *
 * Railway codes (`NDLS`, `PUNE`, …) are already alphanumeric and are returned unchanged.
 */
object StationIds {

    private val UNSAFE = Regex("[^A-Za-z0-9-]")

    fun sanitize(raw: String): String {
        val cleaned = raw.trim().replace(UNSAFE, "-")
        return cleaned.ifBlank { "place-${System.currentTimeMillis()}" }
    }
}
