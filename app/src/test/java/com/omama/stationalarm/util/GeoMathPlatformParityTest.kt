package com.omama.stationalarm.util

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Random

/**
 * The alarm engine measures distance with android.location.Location.distanceBetween (WGS-84 ellipsoid)
 * while the map/ViewModel layer uses [GeoMath] (spherical). They must agree closely enough that a pin
 * the UI considers "inside 3 km" is not "4 km away" for the alarm engine, and vice-versa.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class GeoMathPlatformParityTest {

    private fun platformKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0] / 1000.0
    }

    private fun assertClose(label: String, lat1: Double, lon1: Double, lat2: Double, lon2: Double) {
        val spherical = GeoMath.distanceKm(lat1, lon1, lat2, lon2)
        val ellipsoidal = platformKm(lat1, lon1, lat2, lon2)
        // Sphere vs ellipsoid differs by <= ~0.56%; allow 0.75% plus a metre for float rounding.
        val tolerance = ellipsoidal * 0.0075 + 0.001
        assertEquals("$label spherical=$spherical ellipsoidal=$ellipsoidal", ellipsoidal, spherical, tolerance)
    }

    @Test
    fun agreesForTypicalAlarmDistances() {
        // Same point, 1 km, 3 km (min radius), 20 km (max radius), 100 km, 1,150 km.
        assertClose("same point", 18.5292, 73.8729, 18.5292, 73.8729)
        assertClose("1 km N", 18.5292, 73.8729, 18.5382, 73.8729)
        assertClose("3 km E", 18.5292, 73.8729, 18.5292, 73.9018)
        assertClose("20 km NE", 18.5292, 73.8729, 18.6700, 74.0100)
        assertClose("100 km", 18.5292, 73.8729, 19.4000, 74.4000)
        assertClose("Delhi-Pune", 28.6428, 77.2197, 18.5292, 73.8729)
    }

    @Test
    fun agreesAcrossHemispheresAndTheDateLine() {
        assertClose("London-New York", 51.5074, -0.1278, 40.7128, -74.0060)
        assertClose("Sydney-Auckland", -33.8688, 151.2093, -36.8485, 174.7633)
        assertClose("across date line", 0.0, 179.5, 0.0, -179.5)
        assertClose("equator meridian arc", 0.0, 10.0, 5.0, 10.0)
    }

    @Test
    fun agreesForRandomPairsWithinTwoThousandKilometres() {
        val rnd = Random(99L)
        var checked = 0
        while (checked < 300) {
            val lat1 = rnd.nextDouble() * 60 - 10 // -10..50 N: India and neighbours
            val lon1 = rnd.nextDouble() * 40 + 60 // 60..100 E
            val lat2 = lat1 + (rnd.nextDouble() - 0.5) * 20
            val lon2 = lon1 + (rnd.nextDouble() - 0.5) * 20
            if (GeoMath.distanceKm(lat1, lon1, lat2, lon2) > 2000.0) continue
            assertClose("random#$checked ($lat1,$lon1)-($lat2,$lon2)", lat1, lon1, lat2, lon2)
            checked++
        }
        assertTrue(checked == 300)
    }
}
