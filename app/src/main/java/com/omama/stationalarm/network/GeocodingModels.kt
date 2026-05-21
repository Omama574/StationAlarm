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
) {
    /** True only if lat/lon parse cleanly and lie inside Earth's geographic bounds.
     *  Filters out NaN-from-bad-string and the (0,0) Atlantic placeholder that
     *  some upstream APIs return for "no result". */
    val hasValidCoords: Boolean
        get() = !lat.isNaN() && !lon.isNaN() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)
}

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
        lat        = lat.toDoubleOrNull() ?: Double.NaN,
        lon        = lon.toDoubleOrNull() ?: Double.NaN,
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

