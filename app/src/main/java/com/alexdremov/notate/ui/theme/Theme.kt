package com.alexdremov.notate.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Same scheme in light and dark: the panel has no dark mode worth supporting, and Material's
// tinted containers (lavender selections, blue accents) turn into muddy grays on e-ink.
private val InkColorScheme =
    lightColorScheme(
        primary = Ink,
        onPrimary = Paper,
        primaryContainer = Ink,
        onPrimaryContainer = Paper,
        secondary = InkMuted,
        onSecondary = Paper,
        secondaryContainer = Ink,
        onSecondaryContainer = Paper,
        tertiary = InkMuted,
        onTertiary = Paper,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = PaperDim,
        onSurfaceVariant = InkMuted,
        surfaceTint = Paper,
        outline = Ink,
        outlineVariant = InkFaint,
    )

@Composable
fun NotateTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Paper.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = InkColorScheme,
        typography = Typography,
        content = content,
    )
}
