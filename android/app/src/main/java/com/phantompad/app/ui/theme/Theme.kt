package com.phantompad.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable

private val PhantomColors = darkColorScheme(
    primary = Accent,
    onPrimary = DeepBlack,
    secondary = Accent,
    onSecondary = DeepBlack,
    secondaryContainer = SurfaceElevated,
    onSecondaryContainer = TextPrimary,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = TextPrimary,
    surfaceTint = Accent,
    background = DeepBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderGlow,
    error = ErrRed,
    onError = DeepBlack
)

@Composable
fun PhantomPadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhantomColors,
        typography = PhantomTypography,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}
