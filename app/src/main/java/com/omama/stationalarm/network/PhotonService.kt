package com.omama.stationalarm.network

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for Photon by Komoot (https://photon.komoot.io).
 * Called DIRECTLY from the device — never via Cloudflare Worker.
 *
 * Why direct: Komoot throttles by IP address. Each user's phone has its own IP,
 * so the throttle budget is per-user rather than pooled across the whole app.
 * Routing through Cloudflare would pool everyone onto the server IP and exhaust
 * the limit much faster.
 */
interface PhotonService {

    /**
     * Forward search / autocomplete.
     * Optionally bias results toward user's current location via lat/lon.
     */
    @GET("api/")
    suspend fun search(
        @Query("q")                   query: String,
        @Query("limit")               limit: Int = 5,
        @Query("lang")                lang: String = "en",
        @Query("lat")                 lat: Double? = null,
        @Query("lon")                 lon: Double? = null,
        // zoom=10 → city-level bias radius (~50km). Default 16 is street-level (~100m),
        // which barely helps when user is in India searching "Mumbai".
        @Query("zoom")                zoom: Int = 10,
        // 0.5 → stronger weighting of proximity vs prominence (default 0.2 barely helps).
        // Makes results from user's country rank much higher.
        @Query("location_bias_scale") locationBiasScale: Double = 0.5,
        // Exclude administrative boundary polygons — they have no useful alarm point.
        @Query("osm_tag")             osmTag: String = "!boundary"
        // layer param intentionally omitted — Photon's "other" layer holds all POIs (bus stations,
        // railway stations, airports, etc.) and setting layer= without it silently drops them.
    ): PhotonResponse

    /**
     * Reverse geocoding — coordinates → address.
     */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat")    lat: Double,
        @Query("lon")    lon: Double,
        // 0.5 km radius — prevents pulling addresses from far away on map tap
        @Query("radius") radius: Double = 0.5
    ): PhotonResponse
}
