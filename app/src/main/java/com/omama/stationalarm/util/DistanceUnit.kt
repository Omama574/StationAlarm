package com.omama.stationalarm.util

import androidx.compose.runtime.compositionLocalOf
import com.omama.stationalarm.data.UserPreferences

/**
 * Display unit for distances. Internal storage throughout the app is ALWAYS km;
 * only the display layer converts. Conversion helpers live here so every screen
 * reads the same formatting.
 */
enum class DistanceUnit(val label: String) {
    KM("km"),
    MILES("mi");

    companion object {
        fun fromPrefString(value: String): DistanceUnit = when (value) {
            UserPreferences.UNIT_MILES -> MILES
            else -> KM
        }
    }
}

private const val KM_TO_MI = 0.621371

/** Convert kilometres to the unit's numeric display value. */
fun convertFromKm(km: Double, unit: DistanceUnit): Double = when (unit) {
    DistanceUnit.KM -> km
    DistanceUnit.MILES -> km * KM_TO_MI
}

/** Format a km distance for display in the user's preferred unit. */
fun formatDistance(km: Double, unit: DistanceUnit, decimals: Int = 1): String {
    val value = convertFromKm(km, unit)
    val fmt = "%.${decimals}f".format(value)
    return "$fmt ${unit.label}"
}

/**
 * CompositionLocal providing the current display unit. Provided once at
 * MainActivity.setContent — every composable reads it via
 * `LocalDistanceUnit.current`.
 */
val LocalDistanceUnit = compositionLocalOf { DistanceUnit.KM }
