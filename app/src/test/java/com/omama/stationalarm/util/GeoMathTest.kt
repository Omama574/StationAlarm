package com.omama.stationalarm.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoMathTest {

    @Test
    fun samePointIsZero() {
        assertEquals(0.0, GeoMath.distanceKm(18.5292, 73.8729, 18.5292, 73.8729), 1e-9)
    }

    @Test
    fun oneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        assertEquals(111.19, GeoMath.distanceKm(0.0, 0.0, 1.0, 0.0), 0.1)
    }

    @Test
    fun isSymmetric() {
        val ab = GeoMath.distanceKm(28.6428, 77.2197, 18.5292, 73.8729)
        val ba = GeoMath.distanceKm(18.5292, 73.8729, 28.6428, 77.2197)
        assertEquals(ab, ba, 1e-9)
    }

    @Test
    fun newDelhiToPuneIsAboutElevenHundredFiftyKilometres() {
        // Straight-line distance between the two station coordinates, roughly 1,170 km.
        val d = GeoMath.distanceKm(28.6428, 77.2197, 18.5292, 73.8729)
        assertTrue("was $d", d in 1150.0..1190.0)
    }

    @Test
    fun aCloseSnapIsUnderTheMapPinThreshold() {
        // ~0.001 degree of latitude is roughly 111 m: a POI snap, below the 0.15 km pin threshold.
        val d = GeoMath.distanceKm(18.5800, 73.9180, 18.5810, 73.9180)
        assertTrue("was $d", d < 0.15)
    }

    @Test
    fun aRuralReverseGeocodeJumpIsOverTheThreshold() {
        // ~0.03 degree is roughly 3.3 km: the kind of "nearest village centroid" jump that must not move a pin.
        val d = GeoMath.distanceKm(18.5800, 73.9180, 18.6100, 73.9180)
        assertTrue("was $d", d > 0.15)
    }
}
