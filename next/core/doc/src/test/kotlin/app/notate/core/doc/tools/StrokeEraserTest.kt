package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemId
import app.notate.core.model.PenType
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.EraserMode
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StrokeEraserTest {
    private val eraser = ToolPreset(ToolKind.ERASER, eraserMode = EraserMode.STROKE)
    // horizontal stroke at y=100 from x=0..100, width 2 -> half width 1
    private fun line(id: Long, y: Float = 100f, pen: PenType = PenType.BALLPOINT, w: Float = 2f) =
        stroke(id, points = floatArrayOf(0f, y, 100f, y), pen = pen, widthPt = w)

    private fun erase(r: ToolRig, preset: ToolPreset, x: Float, y: Float) {
        val s = StrokeEraserSession(preset, r.ctx)
        s.begin(ip(x, y)); s.end(ip(x, y, 10))
    }

    @Test fun boundaryIsRadiusPlusHalfWidth() = runTest {
        // radius = 5 px / 1 = 5 pt; reach = 5 + 1 = 6
        val hit = ToolRig(this, seed = listOf(line(1)))
        erase(hit, eraser, 50f, 105.9f)
        assertThat(hit.items()).isEmpty()
        val miss = ToolRig(this, seed = listOf(line(1)))
        erase(miss, eraser, 50f, 106.1f)
        assertThat(miss.items()).hasSize(1)
    }

    @Test fun radiusScalesWithZoom() = runTest {
        // pxPerPt = 5 -> radius 1 pt, reach 2 pt
        val r = ToolRig(this, seed = listOf(line(1)), pxPerPt = 5f)
        erase(r, eraser, 50f, 103f)
        assertThat(r.items()).hasSize(1)
        erase(r, eraser, 50f, 101.9f)
        assertThat(r.items()).isEmpty()
    }

    @Test fun sweptSegmentHitsWhatEndpointsMiss() = runTest {
        val r = ToolRig(this, seed = listOf(line(1)))
        val s = StrokeEraserSession(eraser, r.ctx)
        s.begin(ip(50f, 80f)); s.move(listOf(ip(50f, 120f, 10))); s.end(ip(50f, 130f, 20))
        assertThat(r.items()).isEmpty()
        assertThat(r.preview.shown.filterIsInstance<Preview.Hidden>().last().ids).containsExactly(ItemId(1))
    }

    @Test fun highlighterOnlyFilter() = runTest {
        val r = ToolRig(this, seed = listOf(line(1), line(2, pen = PenType.HIGHLIGHTER)))
        erase(r, eraser.copy(eraseHighlighterOnly = true), 50f, 100f)
        assertThat(r.items().map { it.id.value }).containsExactly(1L)
    }

    @Test fun imagesAreNotErased() = runTest {
        val img = ImageItem(ItemId(3), 3, AssetId("a"), Rect(0f, 90f, 100f, 110f), 0f, 1f, locked = true, Rect(0f, 90f, 100f, 110f))
        val r = ToolRig(this, seed = listOf(img, line(1)))
        erase(r, eraser, 50f, 100f)
        assertThat(r.items().map { it.id.value }).containsExactly(3L)
    }

    @Test fun oneGestureOneEditAndUndoRestores() = runTest {
        val r = ToolRig(this, seed = listOf(line(1, 100f), line(2, 110f), line(3, 300f)))
        val s = StrokeEraserSession(eraser, r.ctx)
        s.begin(ip(50f, 95f)); s.move(listOf(ip(50f, 105f, 5), ip(50f, 112f, 10))); s.end(ip(50f, 115f, 15))
        assertThat(r.items().map { it.id.value }).containsExactly(3L)
        assertThat(r.editor.history.canUndo.value).isTrue()
        r.editor.undo()
        assertThat(r.items().map { it.id.value }).containsExactly(1L, 2L, 3L)
        assertThat(r.editor.history.canUndo.value).isFalse()   // seed was non-undoable, erase was the only entry
    }
}
