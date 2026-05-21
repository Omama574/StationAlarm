package com.omama.stationalarm.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Provider-neutral Retrofit interface for the Cloudflare Worker geocoding proxy.
 *
 * The app sends generic params; the Worker is responsible for translating to
 * whichever provider sits behind it (LocationIQ today; potentially Photon,
 * Mapbox, or anything else later).
 *
 * Base URL is configured in [GeocodingClient] and can be overridden at runtime
 * by Firebase Remote Config (`geocoding_backend_url`).
 *
 * @see GeocodingResponse for the response shape contract.
 */
interface GeocodingService {

    /**
     * Forward search / autocomplete.
     *
     * @param query         the user's typed query string
     * @param limit         max results to return (default 8, server caps at 20)
     * @param biasLat       optional latitude to bias results toward (the user's
     *                      current location). LocationIQ has no native bias param
     *                      and the Worker ignores it; Photon/Mapbox use it.
     * @param biasLon       optional longitude paired with [biasLat].
     * @param lang          ISO 639-1 language code (e.g., "en", "hi", "fr"). The
     *                      Worker forwards this as the provider's localization
     *                      hint (Accept-Language header for LocationIQ).
     */
    @GET("search")
    suspend fun search(
        @Query("q")        query: String,
        @Query("limit")    limit: Int = 8,
        @Query("bias_lat") biasLat: Double? = null,
        @Query("bias_lon") biasLon: Double? = null,
        @Query("lang")     lang: String? = null,
    ): GeocodingResponse

    /**
     * Reverse geocode — coordinates to address.
     * Returns 0 or 1 result; an empty list means "no address known here" and
     * the caller should fall back to displaying raw coordinates.
     */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat")  lat: Double,
        @Query("lon")  lon: Double,
        @Query("lang") lang: String? = null,
    ): GeocodingResponse
}
