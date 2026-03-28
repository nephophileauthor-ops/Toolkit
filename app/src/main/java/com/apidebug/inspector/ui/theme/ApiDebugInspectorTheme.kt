package com.apidebug.inspector.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF6AE3FF),
    onPrimary = Color(0xFF002733),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF3A2800),
    tertiary = Color(0xFF7AF5C1),
    background = Color(0xFF07111D),
    onBackground = Color(0xFFE8F2FF),
    surface = Color(0xFF101C2B),
    onSurface = Color(0xFFE8F2FF),
    surfaceVariant = Color(0xFF1B2B3D),
    onSurfaceVariant = Color(0xFFC5D5E6),
    outline = Color(0xFF47627E)
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF005B78),
    onPrimary = Color.White,
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    tertiary = Color(0xFF006D4B),
    background = Color(0xFFF3F8FC),
    onBackground = Color(0xFF102030),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF102030),
    surfaceVariant = Color(0xFFDCE7F2),
    onSurfaceVariant = Color(0xFF40576E),
    outline = Color(0xFF70869C)
)

@Composable
fun ApiDebugInspectorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        content = content
    )
}
