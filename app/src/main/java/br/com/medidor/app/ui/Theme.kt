package br.com.medidor.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Shared palette from the approved screen references; the app keeps its own name.
private val Lime = Color(0xFFC0F565)
private val Ink = Color(0xFF17251F)

private val Light = lightColorScheme(
    primary = Lime,
    onPrimary = Ink,
    primaryContainer = Color(0xFFE8F9D0),
    onPrimaryContainer = Ink,
    secondary = Ink,
    onSecondary = Color.White,
    background = Color(0xFFF7F8F5),
    surface = Color.White,
    onSurface = Ink,
    onBackground = Ink,
    outline = Color(0xFFD9DFD6)
)

private val Dark = darkColorScheme(
    primary = Lime,
    onPrimary = Ink,
    primaryContainer = Color(0xFF34472E),
    secondary = Color(0xFFB7D6B3),
    background = Color(0xFF101C16),
    surface = Color(0xFF1B2B22),
    onSurface = Color(0xFFEAF2E6)
)

@Composable
fun MedidorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content
    )
}
