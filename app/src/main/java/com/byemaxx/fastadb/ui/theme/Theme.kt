package com.byemaxx.fastadb.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Cyan200,
    onPrimary = Ink950,
    secondary = Ember200,
    onSecondary = Ink950,
    tertiary = Sand200,
    background = Ink950,
    surface = Slate900,
    surfaceVariant = Slate700,
    onBackground = Mist100,
    onSurface = Mist100,
    onSurfaceVariant = Mist200,
    primaryContainer = Color(0xFF12323C),
    onPrimaryContainer = Mist100,
    secondaryContainer = Color(0xFF402517),
    onSecondaryContainer = Mist100,
    outline = Mist200,
    error = Color(0xFFFFB4AB),
    onError = Ink950
)

private val LightColorScheme = lightColorScheme(
    primary = Cyan700,
    onPrimary = Mist100,
    secondary = Ember700,
    onSecondary = Mist100,
    tertiary = Slate700,
    background = Color(0xFFF9FBFD),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8EEF6),
    onBackground = Ink950,
    onSurface = Ink950,
    onSurfaceVariant = Slate700,
    primaryContainer = Color(0xFFD8FFF8),
    onPrimaryContainer = Ink950,
    secondaryContainer = Color(0xFFFFE8DB),
    onSecondaryContainer = Ink950,
    outline = Color(0xFFB6C2D2),
    error = ErrorWarm,
    onError = Mist100
)

@Composable
fun FASTADBTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
