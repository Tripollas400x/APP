package com.phantompad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PhantomColors = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    secondary = Accent,
    onSecondary = DeepBlack,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderGlow,
    error = ErrRed,
    onError = TextPrimary
)

@Composable
fun PhantomPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhantomColors,
        typography = PhantomTypography,
        content = content
    )
}
