package app.notate.core.workspace

import kotlin.math.abs

/** What dropping the divider at some position means (04 §6). */
sealed interface DividerDrop {
    /** The panes keep both and share the space with this ratio (share of the first pane). */
    data class Ratio(val value: Float) : DividerDrop

    /** The first (left or top) pane got smaller than 10 %: close it. */
    data object CloseFirst : DividerDrop

    /** The second (right or bottom) pane got smaller than 10 %: close it. */
    data object CloseSecond : DividerDrop
}

/** Pure rules for the draggable divider between the two panes. */
object SplitGeometry {
    const val CLOSE_BELOW = 0.10f
    const val CLOSE_ABOVE = 0.90f
    const val SNAP_TOLERANCE = 0.06f
    val SNAP_POINTS = floatArrayOf(1f / 3f, 0.5f, 2f / 3f)

    /** Decides what a divider released at [raw] (share of the first pane, 0..1) does: close a side, snap, or keep the ratio. */
    fun drop(raw: Float): DividerDrop {
        if (raw < CLOSE_BELOW) return DividerDrop.CloseFirst
        if (raw > CLOSE_ABOVE) return DividerDrop.CloseSecond
        val near = SNAP_POINTS.minByOrNull { abs(it - raw) }!!
        return DividerDrop.Ratio(if (abs(near - raw) <= SNAP_TOLERANCE) near else raw)
    }
}
