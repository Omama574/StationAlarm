package com.omama.stationalarm.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for our Cloudflare Worker geocoding proxy.
 * The Worker sits in front of LocationIQ — it adds the API key and handles caching.
 *
 * Base URL is set in GeocodingClient to the Worker URL constant.
 * Replace the placeholder URL in GeocodingClient once your Worker is deployed.
 */
interface LocationIqService {

    /**
     * Autocomplete / forward search.
     * Worker path: GET /autocomplete?q=...
     * Worker proxies to: LocationIQ /v1/autocomplete
     */
    @GET("autocomplete")
    suspend fun autocomplete(
        @Query("q")             query: String,
        @Query("limit")         limit: Int = 5,
        @Query("dedupe")        dedupe: Int = 1,
        // normalizecity=1: if address.city is absent, API promotes town/village/county into it
        @Query("normalizecity") normalizecity: Int = 1
        // layers param intentionally omitted — it's an allowlist with no POI/amenity option,
        // so setting it silently drops bus stations, railway stations, airports, etc.
    ): List<LocationIqAutocompleteResult>

    /**
     * Reverse geocoding — map tap → address.
     * Worker path: GET /reverse?lat=...&lon=...
     * Worker proxies to: LocationIQ /v1/reverse
     */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat")              lat: Double,
        @Query("lon")              lon: Double,
        // normalizeaddress=1: guarantees address.city is always populated if any locality data exists
        @Query("normalizeaddress") normalizeaddress: Int = 1
    ): LocationIqReverseResult
}
