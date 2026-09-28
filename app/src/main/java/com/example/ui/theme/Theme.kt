package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AgnesPrimary,
    onPrimary = Color.White,
    secondary = AgnesSecondary,
    onSecondary = Color.Black,
    tertiary = AgnesCyan,
    background = AgnesBg,
    onBackground = AgnesTextPrimary,
    surface = AgnesSurface,
    onSurface = AgnesTextPrimary,
    surfaceVariant = AgnesCard,
    onSurfaceVariant = AgnesTextSecondary,
    error = AgnesError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
