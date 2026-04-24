package com.fieldspec.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF64FFDA),
    onPrimary = Color(0xFF00382E),
    secondary = Color(0xFFFFB300),
    background = Color(0xFF101010),
    surface = Color(0xFF1A1A1A),
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun FieldSpecTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
