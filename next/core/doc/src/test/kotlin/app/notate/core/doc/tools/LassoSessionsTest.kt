package app.notate.core.doc.tools

import app.notate.core.doc.select.SelectRig
import app.notate.core.model.ItemId
import app.notate.core.model.LassoMode
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import app.notate.core.model.EraserMode
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LassoSessionsTest {
    private fun bar(id: Long, x0: Float = 100f, y: Float = 100f) = stroke(id, points = floatArrayOf(x0, y, x0 + 50f, y, x0 + 100f, y))

    private fun draw(s: ToolSession, vararg xy: Float) {
        s.begin(ip(xy[0], xy[1]))
        val mid = (1 until xy.size / 2 - 1).map { ip(xy[2 * it], xy[2 * it + 1]) }
        s.move(mid)
        s.end(ip(xy[xy.size - 2], xy[xy.size - 1]))
    }

    @Test fun lassoSelectsEnclosedStrokes() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 400f)))
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.LASSO), rig.ctx())
        draw(s, 80f, 60f, 260f, 60f, 260f, 140f, 80f, 140f)
        assertThat(rig.controller.selection.value!!.ids).containsExactly(ItemId(1))
        assertThat(rig.preview.cleared).isGreaterThan(0)
    }

    @Test fun rectangleModeUsesCornersOnly() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 400f)))
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.LASSO, lassoMode = LassoMode.RECTANGLE), rig.ctx())
        draw(s, 80f, 60f, 500f, 10f, 260f, 450f)   // wandering path; only start (80,60) and end (260,450) count
        assertThat(rig.controller.selection.value!!.ids).containsExactly(ItemId(1), ItemId(2))
    }

    @Test fun penDownInsideSelectionBecomesMove() = runTest {
        // A tent shape, tall enough that its centre is clear of the edge handles.
        val rig = SelectRig(this, listOf(stroke(1, points = floatArrayOf(100f, 100f, 150f, 160f, 200f, 100f))))
        rig.controller.selectByRect(rig.p1, app.notate.core.geometry.Rect(90f, 80f, 210f, 180f))
        val before = rig.items().single().bounds
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.LASSO), rig.ctx())
        s.begin(ip(150f, 130f))
        s.move(listOf(ip(170f, 150f)))
        s.end(ip(190f, 170f))
        val after = rig.items().single().bounds
        assertThat(after.l).isWithin(1e-3f).of(before.l + 40f)
        assertThat(after.t).isWithin(1e-3f).of(before.t + 40f)
        assertThat(rig.controller.selection.value).isNotNull()
    }

    @Test fun penDownOnHandleScales() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, app.notate.core.geometry.Rect(90f, 80f, 210f, 220f))
        val b = rig.controller.selection.value!!.bounds
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.LASSO), rig.ctx())
        s.begin(ip(b.r, b.b))
        s.end(ip(b.l + b.width * 2f, b.t + b.height * 2f))
        assertThat(rig.controller.selection.value!!.bounds.width).isWithin(2f).of(b.width * 2f)
    }

    @Test fun penDownOutsideClearsSelectionAndStartsNewLasso() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 400f)))
        rig.controller.selectByRect(rig.p1, app.notate.core.geometry.Rect(90f, 80f, 210f, 120f))
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.LASSO), rig.ctx())
        draw(s, 80f, 360f, 260f, 360f, 260f, 440f, 80f, 440f)
        assertThat(rig.controller.selection.value!!.ids).containsExactly(ItemId(2))
    }

    @Test fun lassoEraserDeletesOnlyFullyEnclosedObjectsInOneEdit() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f), bar(3, x0 = 400f, y = 150f)))
        val tool = ToolPreset(ToolKind.ERASER, eraserMode = EraserMode.LASSO)
        val s = ToolSessionFactory().create(tool, rig.ctx())
        // Encloses bars 1 and 2 fully; bar 3 (x 400..500) is outside.
        draw(s, 80f, 60f, 260f, 60f, 260f, 240f, 80f, 240f)
        assertThat(rig.items().map { it.id.value }).containsExactly(3L)
        rig.editor.undo()
        assertThat(rig.items()).hasSize(3)
    }

    @Test fun lassoEraserKeepsPartiallyEnclosed() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        val s = ToolSessionFactory().create(ToolPreset(ToolKind.ERASER, eraserMode = EraserMode.LASSO), rig.ctx())
        draw(s, 80f, 60f, 160f, 60f, 160f, 140f, 80f, 140f)   // 2 of 3 points inside (67 % < 95 %)
        assertThat(rig.items()).hasSize(1)
    }
}
