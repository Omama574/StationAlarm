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
        @Query("q")     query: String,
        @Query("limit") limit: Int = 10,
        @Query("lang")  lang: String = "en",
        @Query("lat")   lat: Double? = null,
        @Query("lon")   lon: Double? = null
    ): PhotonResponse

    /**
     * Reverse geocoding — coordinates → address.
     */
    @GET("reverse")
    suspend fun reverse(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): PhotonResponse
}
