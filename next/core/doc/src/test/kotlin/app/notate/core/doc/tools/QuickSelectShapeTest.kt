package app.notate.core.doc.tools

import app.notate.core.doc.select.Handle
import app.notate.core.doc.select.SelectRig
import app.notate.core.model.PenType
import app.notate.core.model.Shape
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A shape that was just drawn (shape tool or hold-to-shape) is selected so it can be moved, scaled and rotated at once. */
class QuickSelectShapeTest {
    private val black = 0xFF000000.toInt()

    @Test fun shapeToolSelectsTheNewShape() = runTest {
        val rig = SelectRig(this)
        val preset = ToolPreset(ToolKind.SHAPE, pen = PenType.BALLPOINT, color = black, widthPt = 1f, shapeKind = ShapeKind.RECT)
        val s = ShapeDragSession(preset, rig.ctx())
        s.begin(ip(100f, 100f)); s.move(listOf(ip(200f, 160f))); s.end(ip(200f, 160f))
        val shape = rig.items().single() as Shape
        assertThat(rig.controller.selection.value!!.ids).containsExactly(shape.id)
        assertThat(rig.controller.quick).isTrue()
    }

    @Test fun holdToShapeSelectsTheRecognisedShape() = runTest {
        val rig = SelectRig(this)
        val ctx = ToolContext(rig.editor, rig.p1, null, 1f, rig.preview, ToolSettings(600L, 5f), rig.clock, selection = rig.controller)
        val s = PenSession(ToolPreset(ToolKind.PEN, pen = PenType.BALLPOINT, color = black, widthPt = 1f), ctx)
        val xs = floatArrayOf(100f, 200f, 200f, 100f, 100f); val ys = floatArrayOf(100f, 100f, 160f, 160f, 100f)
        s.begin(ip(100f, 100f, 0))
        var t = 0L
        for (e in 0 until 4) for (k in 1..10) { t += 10; rig.clock.advance(10); s.move(listOf(ip(xs[e] + (xs[e + 1] - xs[e]) * k / 10f, ys[e] + (ys[e + 1] - ys[e]) * k / 10f, t))) }
        rig.clock.advance(650); s.poll()
        s.end(ip(100f, 100f, t + 700))
        val shape = rig.items().single() as Shape
        assertThat(rig.controller.selection.value!!.ids).containsExactly(shape.id)
    }

    @Test fun selectedShapeCanBeScaledAndRotated() = runTest {
        val rig = SelectRig(this)
        val preset = ToolPreset(ToolKind.SHAPE, pen = PenType.BALLPOINT, color = black, widthPt = 1f, shapeKind = ShapeKind.RECT)
        val s = ShapeDragSession(preset, rig.ctx())
        s.begin(ip(100f, 100f)); s.move(listOf(ip(200f, 160f))); s.end(ip(200f, 160f))
        val before = (rig.items().single() as Shape).bounds
        rig.controller.beginTransform(Handle.SE, 200f, 160f)
        rig.controller.updateTransform(300f, 160f, rig.p1)
        rig.controller.endTransform()
        val after = (rig.items().single() as Shape).bounds
        assertThat(after.width).isGreaterThan(before.width * 1.5f)
        assertThat(rig.controller.quick).isTrue()
    }

    @Test fun onlyTheOutlineGrabsAQuickSelectedShape() = runTest {
        val rig = SelectRig(this)
        val preset = ToolPreset(ToolKind.SHAPE, pen = PenType.BALLPOINT, color = black, widthPt = 1f, shapeKind = ShapeKind.RECT)
        val s = ShapeDragSession(preset, rig.ctx())
        s.begin(ip(100f, 100f)); s.move(listOf(ip(300f, 260f))); s.end(ip(300f, 260f))
        assertThat(rig.controller.nearShapeOutline(200f, 101f, 6f)).isTrue()
        assertThat(rig.controller.nearShapeOutline(200f, 180f, 6f)).isFalse() // inside: free for writing
    }

    @Test fun lassoSelectionIsNotQuick() = runTest {
        val rig = SelectRig(this)
        val preset = ToolPreset(ToolKind.SHAPE, pen = PenType.BALLPOINT, color = black, widthPt = 1f, shapeKind = ShapeKind.RECT)
        val s = ShapeDragSession(preset, rig.ctx())
        s.begin(ip(100f, 100f)); s.move(listOf(ip(200f, 160f))); s.end(ip(200f, 160f))
        rig.controller.selectByRect(rig.p1, app.notate.core.geometry.Rect(0f, 0f, 400f, 400f))
        assertThat(rig.controller.quick).isFalse()
    }
}
