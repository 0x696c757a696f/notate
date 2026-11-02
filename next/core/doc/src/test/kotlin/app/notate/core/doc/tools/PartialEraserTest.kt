package app.notate.core.doc.tools

import app.notate.core.model.EraserMode
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class PartialEraserTest {
    private val eraser = ToolPreset(ToolKind.ERASER, eraserMode = EraserMode.PARTIAL)
    private val style = InkStyle(PenType.BALLPOINT, 0xFF000000.toInt(), 0f)

    /** 0..100 along y=50, 11 vertices, pressure rising 0..1, time 0..1000 ms. */
    private fun line(): Stroke {
        val xy = FloatArray(22) { if (it % 2 == 0) (it / 2) * 10f else 50f }
        val pts = StrokePoints(xy, FloatArray(11) { it / 10f }, IntArray(11) { it * 100 })
        return Stroke(ItemId(1), 1, style, pts, ItemBounds.strokeBounds(pts, style))
    }

    private fun erase(r: ToolRig, x0: Float, x1: Float, y: Float = 50f) {
        val s = PartialEraserSession(eraser, r.ctx)
        s.begin(ip(x0, y)); s.end(ip(x1, y, 10))
    }

    @Test fun middleCutLeavesTwoPiecesWithInterpolatedEnds() = runTest {
        val r = ToolRig(this, seed = listOf(line()), radiusPx = 5f)   // radius 5
        erase(r, 45f, 55f)   // covers x in [40, 60]
        val pieces = r.items().filterIsInstance<Stroke>().sortedBy { it.points.xy[0] }
        assertThat(pieces).hasSize(2)
        val a = pieces[0].points; val b = pieces[1].points
        assertThat(a.xy[0]).isEqualTo(0f)
        assertThat(a.xy[a.xy.size - 2]).isWithin(0.05f).of(40f)
        assertThat(b.xy[0]).isWithin(0.05f).of(60f)
        assertThat(b.xy[b.xy.size - 2]).isEqualTo(100f)
        assertThat(a.pressure!![a.size - 1]).isWithin(0.01f).of(0.4f)
        assertThat(b.pressure!![0]).isWithin(0.01f).of(0.6f)
        assertThat(b.tMs!![0]).isEqualTo(0)                 // re-based
        assertThat(b.tMs!![b.size - 1]).isWithin(2).of(400)
        assertThat(pieces.map { it.id }.toSet()).hasSize(2)
        assertThat(pieces.map { it.id }).doesNotContain(ItemId(1))
    }

    @Test fun erasingTheStartLeavesOnePiece() = runTest {
        val r = ToolRig(this, seed = listOf(line()))
        erase(r, 0f, 20f)   // covers [-5, 25]
        val piece = r.items().single() as Stroke
        assertThat(piece.points.xy[0]).isWithin(0.05f).of(25f)
        assertThat(piece.points.xy[piece.points.xy.size - 2]).isEqualTo(100f)
    }

    @Test fun erasingEverythingOnlyRemoves() = runTest {
        val r = ToolRig(this, seed = listOf(line()))
        erase(r, -10f, 110f)
        assertThat(r.items()).isEmpty()
    }

    @Test fun missChangesNothing() = runTest {
        val r = ToolRig(this, seed = listOf(line()))
        erase(r, 40f, 60f, y = 80f)
        assertThat(r.items().single().id).isEqualTo(ItemId(1))
        assertThat(r.editor.history.canUndo.value).isFalse()
    }

    @Test fun shapeIsRemovedWhole() = runTest {
        val params = floatArrayOf(0f, 50f, 100f, 50f)
        val shape = Shape(ItemId(2), 2, style, ShapeKind.LINE, params, null, ItemBounds.shapeBounds(ShapeKind.LINE, params, style))
        val r = ToolRig(this, seed = listOf(shape))
        erase(r, 50f, 52f)
        assertThat(r.items()).isEmpty()
    }

    @Test fun undoRestoresOriginalStrokeWithSameId() = runTest {
        val orig = line()
        val r = ToolRig(this, seed = listOf(orig))
        erase(r, 45f, 55f)
        r.editor.undo()
        val back = r.items().single() as Stroke
        assertThat(back.id).isEqualTo(ItemId(1))
        assertThat(back.points).isEqualTo(orig.points)
    }

    @Test fun onlyTheCursorIsPreviewed() = runTest {
        val r = ToolRig(this, seed = listOf(line()))
        val s = PartialEraserSession(eraser, r.ctx)
        s.begin(ip(45f, 50f)); s.move(listOf(ip(50f, 50f, 5)))
        assertThat(r.preview.shown.all { it is Preview.EraserCursor }).isTrue()
        assertThat(r.items()).hasSize(1)
        s.cancel()
        assertThat(r.items().single().id).isEqualTo(ItemId(1))
    }
}
