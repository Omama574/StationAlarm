package com.omama.stationalarm.network

import com.google.gson.annotations.SerializedName

// ── Top-level response ───────────────────────────────────────────────────────

data class MapboxGeocodingResponse(
    val type: String,
    val features: List<MapboxFeature>
)

// ── Feature (each search result) ────────────────────────────────────────────

data class MapboxFeature(
    val type: String,
    val id: String,
    val geometry: MapboxGeometry,
    val properties: MapboxProperties
)

data class MapboxGeometry(
    val type: String,
    val coordinates: List<Double>  // [longitude, latitude]
) {
    val lon: Double get() = coordinates.getOrElse(0) { 0.0 }
    val lat: Double get() = coordinates.getOrElse(1) { 0.0 }
}

// ── Properties ───────────────────────────────────────────────────────────────

data class MapboxProperties(
    val name: String,
    @SerializedName("full_address") val fullAddress: String?,
    @SerializedName("place_formatted") val placeFormatted: String?,
    val context: MapboxContext?,
    @SerializedName("match_code") val matchCode: MapboxMatchCode?
) {
    /** Human-readable subtitle for the result card */
    val subtitle: String
        get() = fullAddress ?: placeFormatted ?: context?.place?.name ?: ""
}

data class MapboxContext(
    val country: MapboxContextEntry?,
    val region: MapboxContextEntry?,
    val district: MapboxContextEntry?,
    val place: MapboxContextEntry?,
    val locality: MapboxContextEntry?,
    val address: MapboxContextEntry?
)

data class MapboxContextEntry(
    val id: String?,
    val name: String?
)

data class MapboxMatchCode(
    val confidence: String?   // "exact" | "high" | "medium" | "low"
)

// ── Convenience extension ─────────────────────────────────────────────────────

/** Map to a UI-ready search result. */
data class GeoSearchResult(
    val id: String,
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val confidence: String  // "exact" | "high" | "medium" | "low"
)

fun MapboxFeature.toSearchResult() = GeoSearchResult(
    id = id,
    name = properties.name,
    subtitle = properties.subtitle,
    lat = geometry.lat,
    lon = geometry.lon,
    confidence = properties.matchCode?.confidence ?: "low"
)
