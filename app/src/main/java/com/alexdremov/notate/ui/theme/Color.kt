package com.alexdremov.notate.ui.theme

import androidx.compose.ui.graphics.Color

// E-ink palette. Boox Note X panels are grayscale (16 levels): hues collapse into pale grays,
// so the UI uses black on white plus a few well-separated grays.

/** Primary text, icons, borders and selected fills. */
val Ink = Color(0xFF000000)

/** Secondary text (dates, hints). Dark enough to stay legible after dithering. */
val InkMuted = Color(0xFF4D4D4D)

/** Disabled or decorative strokes. */
val InkFaint = Color(0xFFBDBDBD)

/** Page background. */
val Paper = Color(0xFFFFFFFF)

/** Recessed surfaces such as thumbnail wells. */
val PaperDim = Color(0xFFEEEEEE)
