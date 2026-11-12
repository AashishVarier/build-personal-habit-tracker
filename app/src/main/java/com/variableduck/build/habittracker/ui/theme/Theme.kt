package com.variableduck.build.habittracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6B4A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA8F2C8),
    onPrimaryContainer = Color(0xFF002113),
    secondary = Color(0xFF4E6356),
    secondaryContainer = Color(0xFFD0E8D7),
    tertiary = Color(0xFF3C6472),
    tertiaryContainer = Color(0xFFC0E9FA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD5AD),
    onPrimary = Color(0xFF003823),
    primaryContainer = Color(0xFF005235),
    onPrimaryContainer = Color(0xFFA8F2C8),
    secondary = Color(0xFFB4CCBC),
    secondaryContainer = Color(0xFF364B3F),
    tertiary = Color(0xFFA4CDDD),
    tertiaryContainer = Color(0xFF234C59),
)

/** Colours with a fixed meaning (YES / NO / missing), tuned separately for light and dark. */
@Immutable
data class StatusColors(
    val yes: Color,
    val onYes: Color,
    val no: Color,
    val onNo: Color,
    val missing: Color,
    val reward: Color,
)

private val LightStatus = StatusColors(
    yes = Color(0xFF2E8B57),
    onYes = Color(0xFFFFFFFF),
    no = Color(0xFFF2D7D5),
    onNo = Color(0xFF5C1A15),
    missing = Color(0xFFE3E3E3),
    reward = Color(0xFFB8860B),
)

private val DarkStatus = StatusColors(
    yes = Color(0xFF3FA36B),
    onYes = Color(0xFF00210F),
    no = Color(0xFF5A2B28),
    onNo = Color(0xFFFFDAD6),
    missing = Color(0xFF3A3A3A),
    reward = Color(0xFFFFCC4D),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

@Composable
fun HabitTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
