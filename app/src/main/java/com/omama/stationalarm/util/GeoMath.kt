package com.omama.stationalarm.util

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {

    private const val EARTH_RADIUS_KM = 6371.0088

    /**
     * Great-circle (haversine) distance in kilometres. Plain Kotlin on purpose, so it can be used from
     * ViewModels and exercised in ordinary JVM unit tests without `android.location.Location`.
     */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }
}
