package com.omama.stationalarm.network

import retrofit2.http.GET
import retrofit2.http.Query

interface MapboxGeocodingService {

    /**
     * Mapbox Geocoding v6 forward geocoding.
     * Optimized for Indian addresses with country + worldview filters.
     */
    @GET("search/geocode/v6/forward")
    suspend fun search(
        @Query("q") query: String,
        @Query("access_token") token: String,
        @Query("country") country: String = "IN",
        @Query("worldview") worldview: String = "in",
        @Query("limit") limit: Int = 5,
        @Query("language") language: String = "en",
        @Query("proximity") proximity: String? = null  // "lon,lat" string for user-location bias
    ): MapboxGeocodingResponse

    /**
     * Mapbox Geocoding v6 reverse geocoding.
     * Maps lat/lon clicks back to a physical address.
     * Now includes language, country, worldview, and street type
     * for accurate Indian address resolution.
     */
    @GET("search/geocode/v6/reverse")
    suspend fun reverseSearch(
        @Query("longitude") longitude: Double,
        @Query("latitude") latitude: Double,
        @Query("access_token") token: String,
        @Query("country") country: String = "IN",
        @Query("worldview") worldview: String = "in",
        @Query("language") language: String = "en",
        @Query("limit") limit: Int = 5
    ): MapboxGeocodingResponse
}
