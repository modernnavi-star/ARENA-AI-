package com.modernnavi.arenaai.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA78BFA),
    secondary = Color(0xFF22D3EE),
    tertiary = Color(0xFFF472B6),
    background = Color(0xFF020617),
    surface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFF1E293B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6D28D9),
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFFBE185D),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0)
)

@Composable
fun ArenaTheme(content: @Composable () -> Unit) {
    // Arena-style experience: keep the app in a consistent dark AI-chat theme.
    MaterialTheme(colorScheme = DarkColors, content = content)
}
