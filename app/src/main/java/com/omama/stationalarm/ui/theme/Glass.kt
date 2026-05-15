package com.omama.stationalarm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

// Carries the single app-wide HazeState down the tree without threading it
// through every function signature. Screens call hazeEffect(LocalAppHazeState.current).
val LocalAppHazeState = compositionLocalOf<HazeState?> { null }

// Glass tokens — reused across all screens.
// hazeSource goes on the gradient background; hazeEffect goes on each panel.

@Composable
fun glassCardStyle(): HazeStyle {
    val dark = isSystemInDarkTheme()
    return HazeStyle(
        blurRadius = 18.dp,
        tints = listOf(
            HazeTint(if (dark) Color(0xFF0E1514).copy(alpha = 0.45f) else Color(0xFFFAFDFB).copy(alpha = 0.50f))
        ),
        noiseFactor = 0.08f
    )
}

@Composable
fun glassSheetStyle(): HazeStyle {
    val dark = isSystemInDarkTheme()
    return HazeStyle(
        blurRadius = 28.dp,
        tints = listOf(
            HazeTint(if (dark) Color(0xFF0E1514).copy(alpha = 0.60f) else Color(0xFFFAFDFB).copy(alpha = 0.65f))
        ),
        noiseFactor = 0.06f
    )
}

@Composable
fun glassTopBarStyle(): HazeStyle {
    val dark = isSystemInDarkTheme()
    return HazeStyle(
        blurRadius = 22.dp,
        tints = listOf(
            HazeTint(if (dark) Color(0xFF0E1514).copy(alpha = 0.55f) else Color(0xFFFAFDFB).copy(alpha = 0.60f))
        ),
        noiseFactor = 0.05f
    )
}

// Subtle border tint used on glass panels to give the "edge" illusion
val glassBorderLight = Color(0xFF006A60).copy(alpha = 0.18f)
val glassBorderDark  = Color(0xFF81D5C7).copy(alpha = 0.18f)

@Composable
fun glassBorderColor() = if (isSystemInDarkTheme()) glassBorderDark else glassBorderLight
