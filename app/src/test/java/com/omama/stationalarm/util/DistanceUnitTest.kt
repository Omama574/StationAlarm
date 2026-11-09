package com.omama.stationalarm.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * km <-> miles conversion and display formatting. Internal storage is always km; only the display layer
 * converts, so these helpers are the single place where a wrong factor would show up on every screen.
 */
class DistanceUnitTest {

    private var savedLocale: Locale? = null

    @Before
    fun pinLocale() {
        // formatDistance uses "%.1f".format(...), which follows Locale.getDefault(); pin it for determinism.
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        savedLocale?.let { Locale.setDefault(it) }
    }

    // ---------------------------------------------------------------- preference string -> unit

    @Test
    fun fromPrefString_milesAndKm() {
        assertEquals(DistanceUnit.MILES, DistanceUnit.fromPrefString("miles"))
        assertEquals(DistanceUnit.KM, DistanceUnit.fromPrefString("km"))
    }

    @Test
    fun fromPrefString_anythingElseFallsBackToKm() {
        // Unknown / corrupted / differently-cased DataStore values must never crash or pick miles by accident.
        for (junk in listOf("", " ", "MILES", "Miles", "mi", "mile", " miles", "miles ", "kilometres", "null", "0", "système")) {
            assertEquals("pref=\"$junk\"", DistanceUnit.KM, DistanceUnit.fromPrefString(junk))
        }
    }

    @Test
    fun labels() {
        assertEquals("km", DistanceUnit.KM.label)
        assertEquals("mi", DistanceUnit.MILES.label)
    }

    // ---------------------------------------------------------------- convertFromKm

    @Test
    fun convertFromKm_kmIsIdentity_includingOddValues() {
        for (v in listOf(0.0, 1.0, 3.0, 20.0, -4.5, 1e-9, 12_345.678, Double.MAX_VALUE)) {
            assertEquals(v, convertFromKm(v, DistanceUnit.KM), 0.0)
        }
        assertTrue(convertFromKm(Double.NaN, DistanceUnit.KM).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, convertFromKm(Double.POSITIVE_INFINITY, DistanceUnit.KM), 0.0)
    }

    @Test
    fun convertFromKm_milesUsesTheKilometreToMileFactor() {
        assertEquals(0.0, convertFromKm(0.0, DistanceUnit.MILES), 0.0)
        assertEquals(0.621371, convertFromKm(1.0, DistanceUnit.MILES), 1e-12)
        assertEquals(1.864113, convertFromKm(3.0, DistanceUnit.MILES), 1e-9) // minimum alert radius
        assertEquals(12.42742, convertFromKm(20.0, DistanceUnit.MILES), 1e-9) // maximum alert radius
        assertEquals(-0.621371, convertFromKm(-1.0, DistanceUnit.MILES), 1e-12)
    }

    @Test
    fun convertFromKm_oneInternationalMileIsAboutOne() {
        // 1 mile = 1.609344 km exactly; the app's factor has 6 significant digits, so allow 1e-6.
        assertEquals(1.0, convertFromKm(1.609344, DistanceUnit.MILES), 1e-6)
        assertEquals(26.2188, convertFromKm(42.195, DistanceUnit.MILES), 1e-3) // marathon
    }

    @Test
    fun convertFromKm_milesIsMonotonicAndSmallerThanKm() {
        var previous = -1.0
        for (km in listOf(0.0, 0.5, 1.0, 5.0, 17.3, 100.0, 1000.0)) {
            val mi = convertFromKm(km, DistanceUnit.MILES)
            assertTrue("miles must be increasing: $mi after $previous", mi > previous)
            if (km > 0) assertTrue(mi < km)
            previous = mi
        }
    }

    @Test
    fun convertFromKm_nonFiniteInputsStayNonFinite() {
        assertTrue(convertFromKm(Double.NaN, DistanceUnit.MILES).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, convertFromKm(Double.POSITIVE_INFINITY, DistanceUnit.MILES), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, convertFromKm(Double.NEGATIVE_INFINITY, DistanceUnit.MILES), 0.0)
    }

    // ---------------------------------------------------------------- formatDistance

    @Test
    fun formatDistance_defaultsToOneDecimal() {
        assertEquals("3.0 km", formatDistance(3.0, DistanceUnit.KM))
        assertEquals("1.9 mi", formatDistance(3.0, DistanceUnit.MILES))
        assertEquals("0.0 km", formatDistance(0.0, DistanceUnit.KM))
        assertEquals("0.0 mi", formatDistance(0.0, DistanceUnit.MILES))
        assertEquals("12.4 mi", formatDistance(20.0, DistanceUnit.MILES))
        assertEquals("6.2 mi", formatDistance(10.0, DistanceUnit.MILES))
    }

    @Test
    fun formatDistance_honoursTheDecimalsArgument() {
        assertEquals("2 km", formatDistance(1.5, DistanceUnit.KM, decimals = 0))
        assertEquals("1 mi", formatDistance(1.609344, DistanceUnit.MILES, decimals = 0))
        assertEquals("1.50 km", formatDistance(1.5, DistanceUnit.KM, decimals = 2))
        assertEquals("1.000 mi", formatDistance(1.609344, DistanceUnit.MILES, decimals = 3))
    }

    @Test
    fun formatDistance_roundsHalfUpOnExactlyRepresentableHalves() {
        assertEquals("0.3 km", formatDistance(0.25, DistanceUnit.KM))
        assertEquals("0.8 km", formatDistance(0.75, DistanceUnit.KM))
        assertEquals("3 km", formatDistance(2.5, DistanceUnit.KM, decimals = 0))
        assertEquals("0.1 km", formatDistance(0.05, DistanceUnit.KM))
    }

    @Test
    fun formatDistance_largeAndNegativeValues() {
        assertEquals("12345.7 km", formatDistance(12_345.678, DistanceUnit.KM))
        assertEquals("-1.0 km", formatDistance(-1.0, DistanceUnit.KM))
        assertEquals("-0.6 mi", formatDistance(-1.0, DistanceUnit.MILES))
    }

    @Test
    fun formatDistance_valueNearZeroDoesNotShowNegativeZeroSurprises() {
        assertEquals("0.0 km", formatDistance(0.04, DistanceUnit.KM))
        assertEquals("0.0 mi", formatDistance(0.04, DistanceUnit.MILES))
    }

    @Test
    fun formatDistance_nonFiniteValuesDoNotThrow() {
        assertEquals("NaN km", formatDistance(Double.NaN, DistanceUnit.KM))
        assertEquals("Infinity km", formatDistance(Double.POSITIVE_INFINITY, DistanceUnit.KM))
        assertEquals("NaN mi", formatDistance(Double.NaN, DistanceUnit.MILES))
    }

    @Test
    fun formatDistance_followsTheDefaultLocaleDecimalSeparator() {
        // Characterisation: the helper is locale-aware, so a German UI shows "1,9 mi". The tests above pin
        // Locale.US; this one documents (and would catch a switch to Locale.ROOT/US) the localisation.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1,9 mi", formatDistance(3.0, DistanceUnit.MILES))
        assertEquals("3,0 km", formatDistance(3.0, DistanceUnit.KM))
    }

    @Test
    fun formatDistance_unitSwitchChangesBothNumberAndLabel() {
        val km = formatDistance(8.04672, DistanceUnit.KM)
        val mi = formatDistance(8.04672, DistanceUnit.MILES)
        assertEquals("8.0 km", km)
        assertEquals("5.0 mi", mi)
    }
}
