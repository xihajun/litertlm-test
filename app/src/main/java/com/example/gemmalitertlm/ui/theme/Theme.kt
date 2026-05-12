package com.example.gemmalitertlm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Blue40,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    surface = Surface,
    onSurface = Slate20,
)

private val DarkColors = darkColorScheme(
    primary = Blue80,
    onPrimary = Slate20,
    surface = SurfaceDark,
    onSurface = Slate80,
)

@Composable
fun GemmaLiteRTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
