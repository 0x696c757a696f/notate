package app.notate.core.doc.select

import app.notate.core.model.ImageItem
import app.notate.core.model.AssetId
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.Stroke
import app.notate.core.geometry.Rect
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SelectionControllerTest {
    // A horizontal stroke from x=100 to x=200 at y=100.
    private fun bar(id: Long, x0: Float = 100f, y: Float = 100f) = stroke(id, points = floatArrayOf(x0, y, x0 + 50f, y, x0 + 100f, y))
    private fun image(id: Long, locked: Boolean) = ImageItem(ItemId(id), id, AssetId("a"), Rect(100f, 300f, 200f, 400f), 0f, 1f, locked, Rect(100f, 300f, 200f, 400f))

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)

    @Test fun lassoSelectsStrokeWithHalfOfPointsInside() = runTest {
        // Points x = 100, 150, 200; box covers x<=150 → 2 of 3 points inside (66%).
        val rig = SelectRig(this, listOf(bar(1)))
        rig.controller.selectByLasso(rig.p1, box(90f, 80f, 160f, 120f))
        assertThat(rig.controller.selection.value!!.ids).containsExactly(ItemId(1))
    }

    @Test fun lassoBoundaryIsFiftyPercent() = runTest {
        // 4 points, 2 inside = exactly 50 % → selected; 1 inside = 25 % → not.
        val s = stroke(1, points = floatArrayOf(100f, 100f, 110f, 100f, 300f, 100f, 310f, 100f))
        val rig = SelectRig(this, listOf(s))
        rig.controller.selectByLasso(rig.p1, box(90f, 80f, 120f, 120f))
        assertThat(rig.controller.selection.value).isNotNull()
        rig.controller.clear()
        rig.controller.selectByLasso(rig.p1, box(90f, 80f, 105f, 120f))
        assertThat(rig.controller.selection.value).isNull()
    }

    @Test fun lockedImageIsNotSelected() = runTest {
        val rig = SelectRig(this, listOf(image(1, locked = true), image(2, locked = false)))
        rig.controller.selectByLasso(rig.p1, box(50f, 250f, 250f, 450f))
        assertThat(rig.controller.selection.value!!.ids).containsExactly(ItemId(2))
    }

    @Test fun moveShiftsBoundsAndIsOneUndoableEdit() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        val before = rig.controller.selection.value!!.bounds
        rig.controller.beginTransform(Handle.MOVE, 150f, 100f)
        rig.controller.updateTransform(180f, 140f, rig.p1)
        assertThat(rig.items().single().bounds).isEqualTo(before)   // document untouched during the drag
        rig.controller.endTransform()
        val after = rig.controller.selection.value!!.bounds
        assertThat(after.l).isWithin(1e-3f).of(before.l + 30f)
        assertThat(after.t).isWithin(1e-3f).of(before.t + 40f)
        assertThat(rig.editor.undo()).isTrue()
        assertThat(rig.items().single().bounds).isEqualTo(before)
    }

    @Test fun cornerScaleIsUniformAboutOppositeCorner() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        val b = rig.controller.selection.value!!.bounds
        rig.controller.beginTransform(Handle.SE, b.r, b.b)
        val d = kotlin.math.hypot(b.width, b.height)
        // Pointer moves along the diagonal to double the distance from the NW anchor.
        rig.controller.updateTransform(b.l + (b.r - b.l) * 2f, b.t + (b.b - b.t) * 2f, rig.p1)
        rig.controller.endTransform()
        val nb = rig.controller.selection.value!!.bounds
        assertThat(nb.l).isWithin(1f).of(b.l)   // stroke width scales too, the 0.5 pt AA margin does not
        assertThat(nb.width).isWithin(1.5f).of(b.width * 2f)
        assertThat(nb.height).isWithin(1.5f).of(b.height * 2f)
        assertThat(d).isGreaterThan(0f)
    }

    @Test fun scaleNeverCollapsesBelowMinimumSize() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        val b = rig.controller.selection.value!!.bounds
        rig.controller.beginTransform(Handle.SE, b.r, b.b)
        rig.controller.updateTransform(b.l, b.t, rig.p1)   // pointer onto the anchor
        val nb = rig.controller.selection.value!!.bounds
        assertThat(maxOf(nb.width, nb.height)).isAtLeast(SelectionController.MIN_SIZE_PT - 0.01f)
        rig.controller.endTransform()
    }

    @Test fun edgeHandleOnStrokeScalesUniformly() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        val b = rig.controller.selection.value!!.bounds
        rig.controller.beginTransform(Handle.E, b.r, b.center.y)
        rig.controller.updateTransform(b.l + b.width * 2f, b.center.y, rig.p1)
        rig.controller.endTransform()
        val nb = rig.controller.selection.value!!.bounds
        assertThat(nb.width / nb.height).isWithin(0.01f).of(b.width / b.height)
    }

    @Test fun edgeHandleOnImageScalesOneAxis() = runTest {
        val rig = SelectRig(this, listOf(image(1, locked = false)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 290f, 210f, 410f))
        val b = rig.controller.selection.value!!.bounds
        rig.controller.beginTransform(Handle.E, b.r, b.center.y)
        rig.controller.updateTransform(b.l + b.width * 2f, b.center.y, rig.p1)
        rig.controller.endTransform()
        val nb = rig.controller.selection.value!!.bounds
        assertThat(nb.width).isWithin(0.5f).of(b.width * 2f)
        assertThat(nb.height).isWithin(0.01f).of(b.height)
    }

    @Test fun rotationSnapsToFortyFiveDegrees() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        val c = rig.controller.selection.value!!.bounds.center
        rig.controller.beginTransform(Handle.ROTATE, c.x, c.y - 100f)
        // 43.5° clockwise: end point rotated about the centre.
        val rad = Math.toRadians(43.5)
        rig.controller.updateTransform(c.x + (100 * kotlin.math.sin(rad)).toFloat(), c.y - (100 * kotlin.math.cos(rad)).toFloat(), rig.p1)
        assertThat(rig.controller.selection.value!!.rotationDeg).isWithin(1e-3f).of(45f)
        // 30° is outside the ±3° window and stays unsnapped.
        val r2 = Math.toRadians(30.0)
        rig.controller.updateTransform(c.x + (100 * kotlin.math.sin(r2)).toFloat(), c.y - (100 * kotlin.math.cos(r2)).toFloat(), rig.p1)
        assertThat(rig.controller.selection.value!!.rotationDeg).isWithin(0.1f).of(30f)
        rig.controller.endTransform()
        assertThat(rig.editor.history.canUndo.value).isTrue()
    }

    @Test fun movingToNextPageConvertsCoordinatesInOneEdit() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        rig.controller.beginTransform(Handle.MOVE, 150f, 100f)
        // Pointer lands at doc y = 920 → page 2 y = 100.
        rig.controller.updateTransform(150f, 920f, rig.p2)
        rig.controller.endTransform()
        assertThat(rig.items(rig.p1)).isEmpty()
        val moved = rig.items(rig.p2).single() as Stroke
        assertThat(moved.points.xy[1]).isWithin(1e-3f).of(100f)
        assertThat(rig.controller.selection.value!!.page).isEqualTo(rig.p2)
        assertThat(rig.editor.undo()).isTrue()
        assertThat(rig.items(rig.p1)).hasSize(1)
        assertThat(rig.items(rig.p2)).isEmpty()
    }

    @Test fun deleteIsOneUndoableEdit() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        rig.controller.delete()
        assertThat(rig.items()).isEmpty()
        assertThat(rig.controller.selection.value).isNull()
        rig.editor.undo()
        assertThat(rig.items()).hasSize(2)
    }

    @Test fun duplicateAddsOffsetCopiesAndSelectsThem() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        rig.controller.duplicate(12f)
        assertThat(rig.items()).hasSize(2)
        val sel = rig.controller.selection.value!!
        assertThat(sel.ids).doesNotContain(ItemId(1))
        assertThat(sel.bounds.l).isWithin(1e-3f).of(rig.items().first { it.id == ItemId(1) }.bounds.l + 12f)
        rig.editor.undo()
        assertThat(rig.items()).hasSize(1)
    }

    @Test fun recolorAndRewidthAreSingleEditsAndUndoable() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 220f))
        rig.controller.recolor(0xFF0000FF.toInt())
        assertThat(rig.items().map { (it as Stroke).style.color }).containsExactly(0xFF0000FF.toInt(), 0xFF0000FF.toInt())
        rig.controller.rewidth(4f)
        assertThat(rig.items().map { (it as Stroke).style.widthPt }).containsExactly(4f, 4f)
        assertThat((rig.items().first() as Stroke).bounds).isEqualTo(ItemBounds.strokeBounds((rig.items().first() as Stroke).points, (rig.items().first() as Stroke).style))
        rig.editor.undo()
        assertThat(rig.items().map { (it as Stroke).style.widthPt }).containsExactly(1.134f, 1.134f)
        rig.editor.undo()
        assertThat(rig.items().map { (it as Stroke).style.color }).containsExactly(0xFF000000.toInt(), 0xFF000000.toInt())
        assertThat(rig.controller.selection.value).isNotNull()
    }

    @Test fun frontAndBackChangeOrderOnly() = runTest {
        val rig = SelectRig(this, listOf(bar(1), bar(2, y = 200f), bar(3, y = 300f)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))   // item 1
        rig.controller.bringToFront()
        val byOrder = rig.items().sortedBy { it.order }.map { it.id.value }
        assertThat(byOrder).containsExactly(2L, 3L, 1L).inOrder()
        rig.controller.sendToBack()
        assertThat(rig.items().sortedBy { it.order }.map { it.id.value }).containsExactly(1L, 2L, 3L).inOrder()
        rig.editor.undo(); rig.editor.undo()
        assertThat(rig.items().sortedBy { it.order }.map { it.id.value }).containsExactly(1L, 2L, 3L).inOrder()
    }

    @Test fun refreshClearsSelectionWhenItemsVanish() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        rig.controller.duplicate()
        rig.editor.undo()
        rig.controller.refresh()
        assertThat(rig.controller.selection.value).isNull()
    }

    @Test fun cutPutsItemsOnClipboardAndDeletes() = runTest {
        val rig = SelectRig(this, listOf(bar(1)))
        val clip = InkClipboard()
        rig.controller.selectByRect(rig.p1, Rect(90f, 80f, 210f, 120f))
        rig.controller.cut(clip)
        assertThat(clip.hasContent.value).isTrue()
        assertThat(rig.items()).isEmpty()
    }
}
