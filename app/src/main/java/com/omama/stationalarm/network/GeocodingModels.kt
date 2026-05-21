package com.omama.stationalarm.network

/**
 * Provider-neutral geocoding contract.
 *
 * The app talks to the Cloudflare Worker, not to any specific geocoding
 * provider. The Worker accepts the requests defined in [GeocodingService]
 * and returns the shape below. This decouples the app from LocationIQ's
 * (or anyone else's) wire format — swapping providers is a Worker-only
 * change with no APK update required.
 *
 * Result shape:
 *   - id              opaque, stable per result (for Compose keys / dedup)
 *   - name            primary label shown on the result row
 *   - formattedAddress full address one-liner shown beneath the name
 *   - lat / lon       valid geographic coordinates
 *
 * Note on naming: this class is still called [GeoSearchResult] for historical
 * reasons — the original implementation had different result shapes for
 * search vs reverse. Now both endpoints return the same shape via
 * [GeocodingResponse]. Rename was skipped to avoid a churn-only diff;
 * see PROJECT_STATE.md for the decision log.
 */
data class GeoSearchResult(
    val id: String,
    val name: String,
    val formattedAddress: String,
    val lat: Double,
    val lon: Double,
) {
    /** True only if lat/lon parse cleanly and lie inside Earth's geographic bounds.
     *  Filters out NaN-from-bad-string and the (0,0) Atlantic placeholder that
     *  some upstream APIs return for "no result". */
    val hasValidCoords: Boolean
        get() = !lat.isNaN() && !lon.isNaN() &&
                lat in -90.0..90.0 && lon in -180.0..180.0 &&
                !(lat == 0.0 && lon == 0.0)
}

/**
 * Wire-format envelope returned by both /search and /reverse.
 * Reverse always returns 0 or 1 result — an empty list means the provider
 * had no address for the given coordinates (e.g., middle of an ocean),
 * and the caller should fall back to displaying raw coordinates.
 */
data class GeocodingResponse(
    val results: List<GeoSearchResult>
)
