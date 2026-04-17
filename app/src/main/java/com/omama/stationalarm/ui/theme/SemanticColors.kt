package com.omama.stationalarm.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

// Domain colors that don't fit cleanly into M3 roles.
// Proximity bar: far → near → imminent gradient on the station card.
// Status badges: an alarm's active/paused state.

val ColorScheme.proximityFar: Color get() = tertiaryContainer
val ColorScheme.proximityNear: Color get() = tertiary
val ColorScheme.proximityImminent: Color get() = error

val ColorScheme.statusActive: Color get() = primary
val ColorScheme.statusInactive: Color get() = outline
