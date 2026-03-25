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
    val name: String?,
    @SerializedName("full_address") val fullAddress: String?,
    @SerializedName("place_formatted") val placeFormatted: String?,
    val context: MapboxContext?,
    @SerializedName("match_code") val matchCode: MapboxMatchCode?,
    @SerializedName("feature_type") val featureType: String? = null
) {
    /** Human-readable subtitle for the result card */
    val subtitle: String
        get() = fullAddress ?: placeFormatted ?: context?.place?.name ?: ""

    /**
     * Builds a structured address from context fields, similar to
     * Google Maps' formatted_address. Falls back gracefully through
     * each level of specificity.
     */
    val structuredAddress: String
        get() {
            val parts = listOfNotNull(
                context?.address?.name,        // house number: "42"
                context?.street?.name,         // street: "MG Road"
                context?.neighborhood?.name,   // area: "Camp Area"
                context?.locality?.name,       // locality: "Shivajinagar"
                context?.place?.name,          // city: "Pune"
                context?.district?.name,       // district: "Pune District"
                context?.region?.name          // state: "Maharashtra"
            )
            if (parts.isNotEmpty()) return parts.joinToString(", ")

            // Fallback chain
            return fullAddress ?: placeFormatted ?: name ?: ""
        }

    /**
     * Best display name: prefer the feature name (street/place name),
     * then structured address, then raw name field.
     */
    val displayName: String
        get() {
            // For street features, the name IS the street name — perfect
            // For address features, name is just the house number — useless alone
            val streetName = context?.street?.name
            val placeName = context?.place?.name

            return when (featureType) {
                "address" -> streetName ?: name ?: "Dropped Pin"
                "street"  -> name ?: "Dropped Pin"
                else      -> name ?: streetName ?: placeName ?: "Dropped Pin"
            }
        }
}

data class MapboxContext(
    val country: MapboxContextEntry?,
    val region: MapboxContextEntry?,
    val district: MapboxContextEntry?,
    val place: MapboxContextEntry?,
    val locality: MapboxContextEntry?,
    val neighborhood: MapboxContextEntry?,
    val street: MapboxContextEntry?,
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
    name = properties.name ?: properties.displayName,
    subtitle = properties.subtitle,
    lat = geometry.lat,
    lon = geometry.lon,
    confidence = properties.matchCode?.confidence ?: "low"
)
