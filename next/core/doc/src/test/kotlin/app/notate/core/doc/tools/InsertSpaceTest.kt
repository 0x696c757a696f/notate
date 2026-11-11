package app.notate.core.doc.tools

import app.notate.core.model.Stroke
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InsertSpaceTest {
    private fun at(y: Float, id: Long) = stroke(id, points = floatArrayOf(10f, y, 60f, y), widthPt = 2f)

    private fun drag(r: ToolRig, from: Float, to: Float) {
        val s = InsertSpaceSession(r.ctx)
        s.begin(ip(30f, from, 0)); s.move(listOf(ip(30f, (from + to) / 2, 5))); s.end(ip(30f, to, 10))
    }

    private fun ys(r: ToolRig) = r.items().sortedBy { it.id.value }.map { (it as Stroke).points.xy[1] }

    @Test fun dragDownPushesEverythingBelowTheLine() = runTest {
        val r = ToolRig(this, seed = listOf(at(50f, 1), at(150f, 2), at(250f, 3)))
        drag(r, 100f, 160f)
        assertThat(ys(r)).containsExactly(50f, 210f, 310f).inOrder()
    }

    @Test fun oneGestureIsOneUndoableEdit() = runTest {
        val r = ToolRig(this, seed = listOf(at(50f, 1), at(150f, 2)))
        drag(r, 100f, 140f)
        r.editor.undo()
        assertThat(ys(r)).containsExactly(50f, 150f).inOrder()
    }

    @Test fun anItemCrossingTheLineStaysPut() = runTest {
        val tall = stroke(1, points = floatArrayOf(10f, 80f, 10f, 120f), widthPt = 2f)
        val r = ToolRig(this, seed = listOf(tall, at(150f, 2)))
        drag(r, 100f, 130f)
        assertThat(r.items().first { it.id.value == 1L }.bounds.t).isLessThan(100f)
        assertThat(ys(r).last()).isEqualTo(180f)
    }

    @Test fun aTapOrDragUpChangesNothing() = runTest {
        val r = ToolRig(this, seed = listOf(at(150f, 1)))
        drag(r, 100f, 101f)
        drag(r, 100f, 40f)
        assertThat(ys(r)).containsExactly(150f)
        assertThat(r.editor.history.canUndo.value).isFalse()
    }

    @Test fun theGapStopsAtThePageBottom() = runTest {
        val r = ToolRig(this, seed = listOf(at(150f, 1), at(250f, 2)))
        val ctx = ToolContext(r.editor, r.p1, app.notate.core.geometry.Rect(0f, 0f, 200f, 300f), 1f, r.preview, ToolSettings(600, 5f), r.clock)
        val s = InsertSpaceSession(ctx)
        s.begin(ip(30f, 100f, 0)); s.move(listOf(ip(30f, 400f, 5))); s.end(ip(30f, 500f, 10))
        // lowest item bottom is 251, page bottom 300 -> at most 49 pt
        assertThat(ys(r)[1]).isWithin(1.5f).of(299f)   // bounds include the stroke width
    }
}
