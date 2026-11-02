package app.notate.core.doc.tools

import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.core.model.Units
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sqrt

class GridSnapperTest {
    private fun paper(p: PaperPattern, s: Float = 14.17f) = Background.Paper(p, s)

    @Test fun gridAndDotsSnapToMultiplesOfSpacing() {
        for (p in listOf(PaperPattern.GRID, PaperPattern.DOTS)) {
            val v = GridSnapper(paper(p, 10f)).snap(23f, 46f)
            assertThat(v.x).isWithin(1e-4f).of(20f)
            assertThat(v.y).isWithin(1e-4f).of(50f)
        }
    }

    @Test fun engineeringSnapsToTheFineGrid() {
        val major = Units.mm(5f)
        val v = GridSnapper(paper(PaperPattern.ENGINEERING, major)).snap(major * 1.1f, major * 0.35f)
        assertThat(v.x).isWithin(1e-3f).of(major * 1.2f)
        assertThat(v.y).isWithin(1e-3f).of(major * 0.4f)
    }

    @Test fun isometricSnapsToLatticeVertices() {
        val s = 20f
        val g = GridSnapper(paper(PaperPattern.ISOMETRIC, s))
        val h = s * sqrt(3f) / 2f
        val v = g.snap(s / 2f + 1f, h - 1f)             // near vertex (s/2, h)
        assertThat(v.x).isWithin(1e-3f).of(s / 2f)
        assertThat(v.y).isWithin(1e-3f).of(h)
        val w = g.snap(2 * s + 0.5f, 0.5f)
        assertThat(hypot(w.x - 2 * s, w.y)).isLessThan(1e-3f)
    }

    @Test fun plainBackgroundsDoNotSnap() {
        for (bg in listOf(paper(PaperPattern.BLANK), paper(PaperPattern.LINED), paper(PaperPattern.CORNELL), Background.None, null)) {
            val g = GridSnapper(bg)
            assertThat(g.available).isFalse()
            val v = g.snap(13.3f, 7.7f)
            assertThat(v.x).isEqualTo(13.3f); assertThat(v.y).isEqualTo(7.7f)
        }
    }

    @Test fun tickSpacing() {
        assertThat(GridSnapper.tickSpacing(paper(PaperPattern.GRID, 11f))).isEqualTo(11f)
        assertThat(GridSnapper.tickSpacing(paper(PaperPattern.LINED))).isEqualTo(Units.mm(5f))
        assertThat(GridSnapper.tickSpacing(null)).isEqualTo(Units.mm(5f))
    }
}
