package com.alexdremov.notate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

// Every size/line-height pair sits on one fixed scale (12/16, 14/20, 16/24, 18/28, 20/28, 24/32).
// Tracking is zero: extra letter spacing only makes dithered text look thinner on e-ink.
// Weights stop at Bold; the system font is kept because it carries the CJK glyphs.
private fun style(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight,
    letterSpacing = 0.sp,
)

val Typography =
    Typography(
        headlineLarge = style(24.sp, 32.sp, FontWeight.Bold),
        headlineMedium = style(20.sp, 28.sp, FontWeight.Bold),
        headlineSmall = style(20.sp, 28.sp, FontWeight.SemiBold),
        titleLarge = style(20.sp, 28.sp, FontWeight.SemiBold),
        titleMedium = style(18.sp, 28.sp, FontWeight.SemiBold),
        titleSmall = style(16.sp, 24.sp, FontWeight.SemiBold),
        bodyLarge = style(18.sp, 28.sp, FontWeight.Normal),
        bodyMedium = style(16.sp, 24.sp, FontWeight.Normal),
        bodySmall = style(14.sp, 20.sp, FontWeight.Normal),
        labelLarge = style(16.sp, 24.sp, FontWeight.SemiBold),
        labelMedium = style(14.sp, 20.sp, FontWeight.SemiBold),
        // 12sp is the bottom of the scale but too thin to read on a 227 ppi grayscale panel.
        labelSmall = style(14.sp, 20.sp, FontWeight.Medium),
    )
