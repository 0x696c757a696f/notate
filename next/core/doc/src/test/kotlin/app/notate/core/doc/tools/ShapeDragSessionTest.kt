package app.notate.core.doc.tools

import app.notate.core.model.Background
import app.notate.core.model.PaperPattern
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.Units
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class ShapeDragSessionTest {
    private fun preset(k: ShapeKind, snap: Boolean = false, sides: Int = 5) =
        ToolPreset(ToolKind.SHAPE, pen = PenType.BALLPOINT, color = 0xFF000000.toInt(), widthPt = 1f, shapeKind = k, snapToGrid = snap, polygonSides = sides)

    private fun TestScope.drag(
        k: ShapeKind, x1: Float, y1: Float, x0: Float = 100f, y0: Float = 100f, constraint: Boolean = false,
        snap: Boolean = false, bg: Background? = null, sides: Int = 5,
    ): Pair<ToolRig, Shape?> {
        val r = ToolRig(this)
        val ctx = app.notate.core.doc.tools.ToolContext(r.editor, r.p1, null, 1f, r.preview, r.ctx.settings, r.clock, background = bg)
        ctx.constraint = constraint
        val s = ShapeDragSession(preset(k, snap, sides), ctx)
        s.begin(ip(x0, y0))
        s.move(listOf(ip((x0 + x1) / 2, (y0 + y1) / 2)))
        s.move(listOf(ip(x1, y1)))
        s.end(ip(x1, y1))
        return r to r.items().singleOrNull() as Shape?
    }

    @Test fun lineAndArrowUseBothEnds() = runTest {
        for (k in listOf(ShapeKind.LINE, ShapeKind.ARROW)) {
            val (r, s) = drag(k, 160f, 130f)
            assertThat(s!!.kind).isEqualTo(k)
            assertThat(s.params).isEqualTo(floatArrayOf(100f, 100f, 160f, 130f))
            assertThat(r.preview.shown.last()).isInstanceOf(Preview.Ghost::class.java)
        }
    }

    @Test fun lineConstraintSnapsTo15Degrees() = runTest {
        val (_, s) = drag(ShapeKind.LINE, 200f, 120f, constraint = true)   // 11.3° → 15°
        val dx = s!!.params[2] - 100f; val dy = s.params[3] - 100f
        assertThat(Math.toDegrees(atan2(dy, dx).toDouble())).isWithin(1e-3).of(15.0)
        assertThat(hypot(dx, dy)).isWithin(1e-2f).of(hypot(100f, 20f))
    }

    @Test fun rectHasFourAxisAlignedCorners() = runTest {
        val (_, s) = drag(ShapeKind.RECT, 180f, 160f)
        assertThat(s!!.params).isEqualTo(floatArrayOf(100f, 100f, 180f, 100f, 180f, 160f, 100f, 160f))
    }

    @Test fun rectConstraintIsASquare() = runTest {
        val (_, s) = drag(ShapeKind.RECT, 180f, 40f, constraint = true)   // dx 80, dy −60 → 80 square up-right
        assertThat(s!!.params).isEqualTo(floatArrayOf(100f, 100f, 180f, 100f, 180f, 20f, 100f, 20f))
    }

    @Test fun ellipseFitsTheBoxAndConstraintMakesACircle() = runTest {
        val (_, e) = drag(ShapeKind.ELLIPSE, 180f, 140f)
        assertThat(e!!.params).isEqualTo(floatArrayOf(140f, 120f, 40f, 20f, 0f))
        val (_, c) = drag(ShapeKind.ELLIPSE, 180f, 140f, constraint = true)
        assertThat(c!!.params[2]).isEqualTo(c.params[3])
        assertThat(c.params[2]).isEqualTo(40f)
    }

    @Test fun polygonIsRegularWithVertexUp() = runTest {
        val (_, s) = drag(ShapeKind.POLYGON, 100f, 50f, sides = 6)     // radius 50, pen straight above the centre
        val p = s!!.params
        assertThat(p.size).isEqualTo(12)
        assertThat(p[0]).isWithin(1e-3f).of(100f); assertThat(p[1]).isWithin(1e-3f).of(50f)   // first vertex straight up
        for (i in 0 until 6) assertThat(hypot(p[2 * i] - 100f, p[2 * i + 1] - 100f)).isWithin(1e-3f).of(50f)
        val (_, tri) = drag(ShapeKind.POLYGON, 100f, 50f, sides = 3)
        assertThat(tri!!.params.size).isEqualTo(6)
    }

    @Test fun axesUseDragLengthAndGridTicks() = runTest {
        val bg = Background.Paper(PaperPattern.GRID, 12f)
        for (k in listOf(ShapeKind.AXES_2D, ShapeKind.AXES_Q1)) {
            val (_, s) = drag(k, 160f, 180f, bg = bg)          // length 100
            assertThat(s!!.params).isEqualTo(floatArrayOf(100f, 100f, 100f, 100f, 0f, 12f))
        }
        val (_, plain) = drag(ShapeKind.AXES_Q1, 160f, 180f, bg = Background.Paper(PaperPattern.LINED, 20f))
        assertThat(plain!!.params[5]).isEqualTo(Units.mm(5f))
    }

    @Test fun snapToGridPutsBothEndsOnIntersections() = runTest {
        val bg = Background.Paper(PaperPattern.GRID, Units.mm(5f))
        val g = Units.mm(5f)
        val (_, s) = drag(ShapeKind.RECT, 3.4f * g, 2.6f * g, x0 = 0.8f * g, y0 = 0.9f * g, snap = true, bg = bg)
        val p = s!!.params
        assertThat(p[0]).isWithin(1e-3f).of(g); assertThat(p[1]).isWithin(1e-3f).of(g)
        assertThat(p[4]).isWithin(1e-3f).of(3 * g); assertThat(p[5]).isWithin(1e-3f).of(3 * g)
    }

    @Test fun snapIsIgnoredOnPlainPaper() = runTest {
        val (_, s) = drag(ShapeKind.LINE, 163.3f, 100f, snap = true, bg = Background.Paper(PaperPattern.LINED, 20f))
        assertThat(s!!.params[2]).isEqualTo(163.3f)
    }

    @Test fun tooShortDragCommitsNothing() = runTest {
        val (r, s) = drag(ShapeKind.RECT, 101f, 100.5f)
        assertThat(s).isNull()
        assertThat(r.items()).isEmpty()
        assertThat(r.preview.cleared).isEqualTo(1)
    }

    @Test fun cancelCommitsNothing() = runTest {
        val r = ToolRig(this)
        val s = ShapeDragSession(preset(ShapeKind.LINE), r.ctx)
        s.begin(ip(0f, 0f)); s.move(listOf(ip(50f, 50f))); s.cancel()
        assertThat(r.items()).isEmpty()
    }

    @Test fun releasingTheConstraintRestoresTheFreeShape() = runTest {
        val r = ToolRig(this)
        val s = ShapeDragSession(preset(ShapeKind.RECT), r.ctx)
        s.begin(ip(100f, 100f)); s.move(listOf(ip(180f, 140f)))
        r.ctx.constraint = true; s.reapplyConstraint()
        assertThat((((r.preview.shown.last() as Preview.Ghost).items.single()) as Shape).params[5]).isEqualTo(180f)
        r.ctx.constraint = false; s.reapplyConstraint()
        s.end(ip(180f, 140f))
        assertThat((r.items().single() as Shape).params[5]).isEqualTo(140f)
    }

    @Test fun factoryBuildsShapeSessionAndUndoRemovesIt() = runTest {
        val r = ToolRig(this)
        val s = ToolSessionFactory().create(preset(ShapeKind.LINE), r.ctx)
        assertThat(s).isInstanceOf(ShapeDragSession::class.java)
        s.begin(ip(0f, 0f)); s.end(ip(50f, 0f))
        assertThat(r.items()).hasSize(1)
        r.editor.undo()
        assertThat(r.items()).isEmpty()
    }
}
