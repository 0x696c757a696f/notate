package com.alexdremov.notate.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale. Layout values come from this table only. */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp
}

/** Corner radius scale. */
object Radius {
    val sm = 4.dp
    val md = 8.dp
    val lg = 12.dp
    val xl = 16.dp
    val dialog = 28.dp

    /**
     * Radius for a shape nested inside another with [gap] between them: outer minus gap,
     * or square when the result would be 2dp or less.
     */
    fun nested(
        outer: Dp,
        gap: Dp,
    ): Dp {
        val inner = outer - gap
        return if (inner > 2.dp) inner else 0.dp
    }
}

/** Hairline border used instead of shadows: elevation renders as a gray smear on e-ink. */
val BorderWidth = 1.dp

val CardShape = RoundedCornerShape(Radius.lg)
