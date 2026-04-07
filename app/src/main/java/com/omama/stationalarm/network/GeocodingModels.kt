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
    @SerializedName("place_id")        val placeId: String,
    @SerializedName("osm_id")          val osmId: String?,
    val lat: String,
    val lon: String,
    @SerializedName("display_place")   val displayPlace: String?,
    @SerializedName("display_address") val displayAddress: String?,
    @SerializedName("display_name")    val displayName: String?,
    val importance: Double = 0.5,
    // Structured address object — more reliable than the raw display_address string
    val address: LocationIqAddress?
)

fun LocationIqAutocompleteResult.toSearchResult(): GeoSearchResult {
    // display_place is specifically the place name only (no address cruft)
    val name = displayPlace?.takeIf { it.isNotBlank() }
        ?: displayName?.split(",")?.firstOrNull()?.trim()
        ?: "Unknown"

    // Build subtitle from most specific → least specific:
    // road/suburb → city (normalizecity=1 ensures this is set) → state
    // Falls back to raw display_address string if address object is absent.
    val addr = address
    val subtitle = if (addr != null) {
        val road   = addr.road?.takeIf { it.isNotBlank() }
        val suburb = addr.suburb?.takeIf { it.isNotBlank() }
        val city   = (addr.city ?: addr.town ?: addr.village ?: addr.county)?.takeIf { it.isNotBlank() }
        val state  = addr.state?.takeIf { it.isNotBlank() }
        // Include road/suburb only when they add context beyond the place name itself
        val localPart = listOfNotNull(road, suburb).joinToString(", ").takeIf { it.isNotBlank() }
        listOfNotNull(localPart, city, state).joinToString(", ").ifBlank {
            displayAddress?.takeIf { it.isNotBlank() } ?: ""
        }
    } else {
        displayAddress?.takeIf { it.isNotBlank() } ?: ""
    }

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
    val neighbourhood: String?,
    @SerializedName("city_district") val cityDistrict: String?,
    val city: String?,
    val town: String?,
    val village: String?,
    val county: String?,
    val state: String?,
    val postcode: String?,
    val country: String?
)

fun LocationIqReverseResult.toSearchResult(lat: Double, lon: Double): GeoSearchResult {
    val addr = address
    // For reverse: name is the road/area the pin is on
    val name = addr?.road
        ?: addr?.suburb
        ?: addr?.neighbourhood
        ?: displayName?.split(",")?.firstOrNull()?.trim()
        ?: "Dropped Pin"
    // normalizeaddress=1 guarantees city is set if any locality data exists
    // Subtitle: suburb → city → state (low → high, most informative for dropped pin)
    val suburb = (addr?.suburb ?: addr?.neighbourhood ?: addr?.cityDistrict)?.takeIf { it.isNotBlank() }
    val city   = (addr?.city ?: addr?.town ?: addr?.village ?: addr?.county)?.takeIf { it.isNotBlank() }
    val state  = addr?.state?.takeIf { it.isNotBlank() }
    val subtitle = listOfNotNull(suburb, city, state).joinToString(", ").ifBlank {
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
    val district: String?,    // neighbourhood / suburb level
    val locality: String?,    // locality fallback (less common)
    val city: String?,
    val county: String?,      // county/tehsil — fallback when city is absent
    val state: String?,
    val country: String?,
    val countrycode: String?,
    val postcode: String?,
    @SerializedName("osm_id")    val osmId: Long?,
    @SerializedName("osm_key")   val osmKey: String?,
    @SerializedName("osm_value") val osmValue: String?
)

fun PhotonFeature.toSearchResult(): GeoSearchResult {
    val props = properties
    val name  = props.name ?: props.street ?: "Dropped Pin"

    // Build subtitle from most specific available fields downward:
    // street/district (local area) → city (fallback: county/locality) → state
    val localArea = (props.district ?: props.locality)?.takeIf { it.isNotBlank() }
    val city      = (props.city ?: props.county ?: props.locality)?.takeIf { it.isNotBlank() }
    val state     = props.state?.takeIf { it.isNotBlank() }

    // Avoid duplicating name in subtitle (e.g., if result IS a city, don't show "Chennai, Chennai, TN")
    val cityPart = city?.takeIf { it != name }
    val subtitle = listOfNotNull(localArea?.takeIf { it != cityPart && it != name }, cityPart, state)
        .joinToString(", ")

    return GeoSearchResult(
        id         = "photon_${props.osmId ?: System.currentTimeMillis()}",
        name       = name,
        subtitle   = subtitle,
        lat        = lat,
        lon        = lon,
        confidence = "medium"
    )
}
