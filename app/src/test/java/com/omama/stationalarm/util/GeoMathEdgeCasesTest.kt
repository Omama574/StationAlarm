package com.omama.stationalarm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Edge cases for the haversine helper (complements the happy-path GeoMathTest). Plain JVM on purpose:
 * GeoMath is documented as Android-free.
 */
class GeoMathEdgeCasesTest {

    private val earthRadiusKm = 6371.0088
    private val kmPerDegreeOfArc = earthRadiusKm * Math.PI / 180.0 // ~111.19508 km

    private fun d(lat1: Double, lon1: Double, lat2: Double, lon2: Double) =
        GeoMath.distanceKm(lat1, lon1, lat2, lon2)

    // ---------------------------------------------------------------- exact geometry

    @Test
    fun oneDegreeAlongTheEquatorAndAlongAMeridianIsTheSameArcLength() {
        assertEquals(kmPerDegreeOfArc, d(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(kmPerDegreeOfArc, d(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(kmPerDegreeOfArc, d(0.0, 100.0, 0.0, 101.0), 1e-6)
    }

    @Test
    fun longitudeDegreesShrinkWithLatitude() {
        // cos(60 deg) = 0.5: one degree of longitude at 60 N is about half an equatorial degree.
        assertEquals(kmPerDegreeOfArc * 0.5, d(60.0, 0.0, 60.0, 1.0), 0.01)
        // And at 89.9 N a full degree of longitude is only ~0.19 km.
        assertTrue(d(89.9, 0.0, 89.9, 1.0) < 0.2)
    }

    @Test
    fun antipodalPoints_areHalfTheEarthCircumference() {
        val half = Math.PI * earthRadiusKm
        assertEquals(half, d(0.0, 0.0, 0.0, 180.0), 1e-6)
        assertEquals(half, d(90.0, 0.0, -90.0, 0.0), 1e-6)
        assertEquals(half, d(45.0, 10.0, -45.0, -170.0), 1e-6)
    }

    @Test
    fun antipodalSweep_neverProducesNaN() {
        // asin(sqrt(a)) is NaN if rounding pushes `a` just above 1. Guard a coarse grid of exact antipodes.
        var lat = -89.5
        while (lat <= 89.5) {
            var lon = -179.5
            while (lon <= 179.5) {
                val antipodeLon = if (lon > 0) lon - 180.0 else lon + 180.0
                val dist = d(lat, lon, -lat, antipodeLon)
                assertTrue("NaN for ($lat,$lon)", !dist.isNaN())
                assertEquals("($lat,$lon)", Math.PI * earthRadiusKm, dist, 1e-3)
                lon += 7.5
            }
            lat += 7.5
        }
    }

    @Test
    fun crossingTheDateLine_takesTheShortWay() {
        val shortWay = d(0.0, 179.9, 0.0, -179.9)
        assertEquals(0.2 * kmPerDegreeOfArc, shortWay, 1e-3)
        assertTrue("went the long way round: $shortWay", shortWay < 100.0)
    }

    @Test
    fun longitudesThatDifferByAFullTurn_areTheSamePlace() {
        assertEquals(0.0, d(10.0, 190.0, 10.0, -170.0), 1e-6)
        assertEquals(0.0, d(-33.0, 540.0, -33.0, 180.0), 1e-6)
    }

    @Test
    fun theTwoPolesAreSinglePoints_whateverTheLongitude() {
        assertEquals(0.0, d(90.0, 0.0, 90.0, 123.0), 1e-6)
        assertEquals(0.0, d(-90.0, -45.0, -90.0, 170.0), 1e-6)
    }

    // ---------------------------------------------------------------- scale

    @Test
    fun metreScaleDistancesAreResolved() {
        // 1e-5 degrees of latitude is ~1.112 m.
        assertEquals(0.0011119508, d(18.0, 73.0, 18.00001, 73.0), 1e-7)
        // The 3.0 km minimum alert radius is ~0.027 degrees of latitude.
        assertEquals(3.0, d(12.0, 77.0, 12.0 + 3.0 / kmPerDegreeOfArc, 77.0), 1e-9)
    }

    @Test
    fun samePointIsExactlyZero_forAnyCoordinate() {
        for ((lat, lon) in listOf(0.0 to 0.0, 90.0 to 0.0, -90.0 to 180.0, 28.6428 to 77.2197, -33.8688 to 151.2093)) {
            assertEquals("($lat,$lon)", 0.0, d(lat, lon, lat, lon), 0.0)
        }
    }

    @Test
    fun realWorldCityPairs() {
        // Mumbai -> Delhi, great circle ~1,150 km.
        assertTrue(d(19.0760, 72.8777, 28.7041, 77.1025) in 1140.0..1165.0)
        // London -> New York, great circle ~5,570 km (negative longitudes involved).
        assertTrue(d(51.5074, -0.1278, 40.7128, -74.0060) in 5560.0..5585.0)
        // Sydney -> Auckland crosses southern latitudes, ~2,160 km.
        assertTrue(d(-33.8688, 151.2093, -36.8485, 174.7633) in 2140.0..2180.0)
    }

    // ---------------------------------------------------------------- algebraic properties

    @Test
    fun symmetricNonNegativeAndBoundedForRandomPairs() {
        val rnd = Random(20240607L)
        val half = Math.PI * earthRadiusKm
        repeat(2_000) {
            val lat1 = rnd.nextDouble() * 180 - 90
            val lon1 = rnd.nextDouble() * 360 - 180
            val lat2 = rnd.nextDouble() * 180 - 90
            val lon2 = rnd.nextDouble() * 360 - 180
            val ab = d(lat1, lon1, lat2, lon2)
            val ba = d(lat2, lon2, lat1, lon1)
            assertTrue("NaN for ($lat1,$lon1)-($lat2,$lon2)", !ab.isNaN())
            assertEquals(ab, ba, 1e-9)
            assertTrue("negative: $ab", ab >= 0.0)
            assertTrue("beyond half circumference: $ab", ab <= half + 1e-6)
        }
    }

    @Test
    fun triangleInequalityHolds() {
        val rnd = Random(7L)
        repeat(1_000) {
            val a = doubleArrayOf(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180)
            val b = doubleArrayOf(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180)
            val c = doubleArrayOf(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180)
            val ac = d(a[0], a[1], c[0], c[1])
            val viaB = d(a[0], a[1], b[0], b[1]) + d(b[0], b[1], c[0], c[1])
            assertTrue("a->c=$ac > a->b->c=$viaB", ac <= viaB + 1e-6)
        }
    }

    // ---------------------------------------------------------------- garbage in

    @Test
    fun nanInAnyArgument_givesNaN_notAWrongFiniteNumber() {
        assertTrue(d(Double.NaN, 0.0, 0.0, 0.0).isNaN())
        assertTrue(d(0.0, Double.NaN, 0.0, 0.0).isNaN())
        assertTrue(d(0.0, 0.0, Double.NaN, 0.0).isNaN())
        assertTrue(d(0.0, 0.0, 0.0, Double.NaN).isNaN())
    }

    @Test
    fun infiniteCoordinates_giveNaN() {
        assertTrue(d(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0).isNaN())
        assertTrue(d(0.0, Double.NEGATIVE_INFINITY, 0.0, 0.0).isNaN())
    }
}
