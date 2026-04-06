package com.omama.stationalarm.network

import com.google.gson.annotations.SerializedName

// ── Shared domain model ───────────────────────────────────────────────────────

data class GeoSearchResult(
    val id: String,
    val name: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    val confidence: String  // "exact" | "high" | "medium" | "low"
)

// ── LocationIQ models ─────────────────────────────────────────────────────────
// These map to the JSON returned by our Cloudflare Worker (which proxies LocationIQ).

data class LocationIqAutocompleteResult(
    @SerializedName("place_id")      val placeId: String,
    @SerializedName("osm_id")        val osmId: String?,
    val lat: String,
    val lon: String,
    @SerializedName("display_place") val displayPlace: String?,
    @SerializedName("display_address") val displayAddress: String?,
    @SerializedName("display_name")  val displayName: String?,
    val importance: Double = 0.5
)

fun LocationIqAutocompleteResult.toSearchResult(): GeoSearchResult {
    val name = displayPlace?.takeIf { it.isNotBlank() }
        ?: displayName?.split(",")?.firstOrNull()?.trim()
        ?: "Unknown"
    val subtitle = displayAddress?.takeIf { it.isNotBlank() }
        ?: displayName?.takeIf { it.isNotBlank() }
        ?: ""
    val confidence = when {
        importance >= 0.7 -> "high"
        importance >= 0.4 -> "medium"
        else              -> "low"
    }
    return GeoSearchResult(
        id         = "liq_${osmId ?: placeId}",
        name       = name,
        subtitle   = subtitle,
        lat        = lat.toDoubleOrNull() ?: 0.0,
        lon        = lon.toDoubleOrNull() ?: 0.0,
        confidence = confidence
    )
}

data class LocationIqReverseResult(
    @SerializedName("place_id") val placeId: String,
    @SerializedName("osm_id")   val osmId: String?,
    val lat: String,
    val lon: String,
    @SerializedName("display_name") val displayName: String?,
    val address: LocationIqAddress?
)

data class LocationIqAddress(
    val road: String?,
    val suburb: String?,
    val city: String?,
    val town: String?,
    val village: String?,
    val county: String?,
    val state: String?,
    val country: String?
)

fun LocationIqReverseResult.toSearchResult(lat: Double, lon: Double): GeoSearchResult {
    val addr   = address
    val name   = addr?.road
        ?: displayName?.split(",")?.firstOrNull()?.trim()
        ?: "Dropped Pin"
    val city   = addr?.city ?: addr?.town ?: addr?.village ?: addr?.county ?: ""
    val state  = addr?.state ?: ""
    val subtitle = listOfNotNull(
        city.takeIf  { it.isNotBlank() },
        state.takeIf { it.isNotBlank() }
    ).joinToString(", ").ifBlank {
        displayName?.takeIf { it.isNotBlank() } ?: ""
    }
    return GeoSearchResult(
        id         = "liq_rev_${osmId ?: placeId}",
        name       = name,
        subtitle   = subtitle,
        lat        = lat,
        lon        = lon,
        confidence = "high"
    )
}

// ── Photon models (GeoJSON) ───────────────────────────────────────────────────
// Photon returns a GeoJSON FeatureCollection.
// IMPORTANT: geometry.coordinates order is [lon, lat] — reversed from standard!

data class PhotonResponse(
    val type: String,
    val features: List<PhotonFeature>
)

data class PhotonFeature(
    val type: String,
    val geometry: PhotonGeometry,
    val properties: PhotonProperties
) {
    val lon: Double get() = geometry.coordinates.getOrElse(0) { 0.0 }
    val lat: Double get() = geometry.coordinates.getOrElse(1) { 0.0 }
}

data class PhotonGeometry(
    val type: String,
    val coordinates: List<Double>  // [longitude, latitude]
)

data class PhotonProperties(
    val name: String?,
    val street: String?,
    val city: String?,
    val state: String?,
    val country: String?,
    val countrycode: String?,
    @SerializedName("osm_id")    val osmId: Long?,
    @SerializedName("osm_key")   val osmKey: String?,
    @SerializedName("osm_value") val osmValue: String?
)

fun PhotonFeature.toSearchResult(): GeoSearchResult {
    val props    = properties
    val name     = props.name ?: props.street ?: "Dropped Pin"
    val subtitle = listOfNotNull(
        props.city?.takeIf    { it.isNotBlank() },
        props.state?.takeIf   { it.isNotBlank() },
        props.country?.takeIf { it.isNotBlank() }
    ).joinToString(", ")
    return GeoSearchResult(
        id         = "photon_${props.osmId ?: System.currentTimeMillis()}",
        name       = name,
        subtitle   = subtitle,
        lat        = lat,
        lon        = lon,
        confidence = "medium"
    )
}
