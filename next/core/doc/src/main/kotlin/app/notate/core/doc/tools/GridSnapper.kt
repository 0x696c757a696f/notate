package app.notate.core.doc.tools

import app.notate.core.geometry.Vec2
import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.core.model.Units
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Snaps page points to the intersections of the paper pattern. Lines sit at multiples of the spacing from the page origin
 * (ENGINEERING: the fine grid, one fifth of the bold spacing, as long as the renderer draws it). ISOMETRIC uses the vertices of
 * the triangular lattice through the origin. Other backgrounds have no grid and return the point unchanged.
 */
class GridSnapper(background: Background?) {
    private val paper = background as? Background.Paper
    private val pattern = paper?.pattern

    /** True when [snap] can move points. */
    val available: Boolean = pattern == PaperPattern.GRID || pattern == PaperPattern.DOTS ||
        pattern == PaperPattern.ENGINEERING || pattern == PaperPattern.ISOMETRIC

    private val step: Float = when {
        paper == null -> 0f
        pattern == PaperPattern.ENGINEERING ->
            (paper.spacingPt / 5f).let { if (it >= Units.mm(0.5f)) it else paper.spacingPt }
        else -> paper.spacingPt.coerceAtLeast(1f)
    }

    fun snap(x: Float, y: Float): Vec2 = when {
        !available -> Vec2(x, y)
        pattern == PaperPattern.ISOMETRIC -> snapLattice(x, y)
        else -> Vec2((x / step).roundToInt() * step, (y / step).roundToInt() * step)
    }

    /** Lattice vertices are i·(s, 0) + j·(s/2, s·√3/2); look at the cells around the point and take the closest. */
    private fun snapLattice(x: Float, y: Float): Vec2 {
        val h = step * (sqrt(3f) / 2f)
        val j0 = floor(y / h).toInt()
        var best = Vec2(x, y)
        var bestD = Float.MAX_VALUE
        for (j in j0..j0 + 1) {
            val shift = j * step / 2f
            val i0 = floor((x - shift) / step).toInt()
            for (i in i0..i0 + 1) {
                val cx = i * step + shift
                val cy = j * h
                val d = hypot(cx - x, cy - y)
                if (d < bestD) { bestD = d; best = Vec2(cx, cy) }
            }
        }
        return best
    }

    companion object {
        /** Axis tick spacing on [background]: the grid spacing for GRID, DOTS and ENGINEERING, otherwise 5 mm. */
        fun tickSpacing(background: Background?): Float {
            val p = background as? Background.Paper ?: return Units.mm(5f)
            return when (p.pattern) {
                PaperPattern.GRID, PaperPattern.DOTS, PaperPattern.ENGINEERING -> p.spacingPt.coerceAtLeast(1f)
                else -> Units.mm(5f)
            }
        }
    }
}
