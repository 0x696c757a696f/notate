package com.alexdremov.notate.ui.split

import com.alexdremov.notate.ui.tabs.Workspace
import kotlin.math.abs

/** What letting go of the split-view divider does. */
object SplitDrag {
    sealed class Outcome {
        data class Resize(
            val ratio: Float,
        ) : Outcome()

        /** Divider pulled to an edge: keep pane [keepIndex] (0 = first, 1 = second) alone. */
        data class Collapse(
            val keepIndex: Int,
        ) : Outcome()
    }

    private val SNAP_POINTS = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)
    private const val SNAP_DISTANCE = 0.04f
    private const val COLLAPSE_EDGE = 0.1f

    /** Divider let go at [ratio] (share of the first pane): snap to 1/3, 1/2, 2/3 or close a pane. */
    fun resolve(ratio: Float): Outcome {
        if (ratio < COLLAPSE_EDGE) return Outcome.Collapse(keepIndex = 1)
        if (ratio > 1f - COLLAPSE_EDGE) return Outcome.Collapse(keepIndex = 0)
        val snapped = SNAP_POINTS.firstOrNull { abs(it - ratio) <= SNAP_DISTANCE } ?: ratio
        return Outcome.Resize(snapped.coerceIn(Workspace.MIN_RATIO, Workspace.MAX_RATIO))
    }
}
