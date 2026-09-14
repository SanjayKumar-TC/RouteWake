package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RouteWakeDarkColorScheme = darkColorScheme(
    primary = ElectricBlue,
    onPrimary = DarkBackground,
    primaryContainer = DeepBlue,
    onPrimaryContainer = TextPrimary,
    secondary = AmberAction,
    onSecondary = DarkBackground,
    secondaryContainer = AmberDark,
    onSecondaryContainer = TextPrimary,
    tertiary = TealRoute,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderAccent,
    outlineVariant = BorderSubtle,
    error = AlarmCrimson,
    onError = TextPrimary
)

@Composable
fun RouteWakeTheme(
    content: @Composable () -> Unit
) {
    // RouteWake is strictly Dark-Only Night Transit HUD
    MaterialTheme(
        colorScheme = RouteWakeDarkColorScheme,
        typography = Typography,
        content = content
    )
}
