package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.core.model.ToolKind
import app.notate.core.model.ToolPreset
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TextPlaceSessionTest {
    private val preset = ToolPreset(ToolKind.TEXT, color = 0xFF112233.toInt(), widthPt = 24f, bold = true, align = TextAlign.CENTER)

    private fun ctxOf(r: ToolRig, calls: MutableList<Pair<ItemId, Boolean>>): ToolContext =
        ToolContext(r.editor, r.p1, Rect(0f, 0f, 595.28f, 841.89f), 1f, r.preview, ToolSettings(null, 5f), r.clock).also {
            it.onEditText = { id, isNew -> calls.add(id to isNew) }
        }

    private fun tap(ctx: ToolContext, x: Float, y: Float) {
        val s = TextPlaceSession(preset, ctx)
        s.begin(ip(x, y)); s.end(ip(x + 1f, y, 10))      // 1 pt < 2 mm
    }

    private fun boxOf(text: String, id: Long = 7, frame: Rect = Rect(100f, 100f, 300f, 130f), rot: Float = 0f) = TextBox(
        ItemId(id), id, frame, rot, text, 14f, 0xFF000000.toInt(), false, TextAlign.LEFT, ItemBounds.frameBounds(frame, rot),
    )

    @Test fun tapOnEmptyPageCreatesBoxAndStartsEditing() = runTest {
        val r = ToolRig(this)
        val calls = ArrayList<Pair<ItemId, Boolean>>()
        tap(ctxOf(r, calls), 50f, 60f)
        val box = r.items().single() as TextBox
        assertThat(calls).containsExactly(box.id to true)
        assertThat(box.frame.width).isWithin(0.01f).of(595.28f / 2f)
        assertThat(box.frame.l).isEqualTo(50f)
        assertThat(box.frame.t).isEqualTo(60f)
        assertThat(box.frame.height).isAtLeast(24f)
        assertThat(box.fontSizePt).isEqualTo(24f)
        assertThat(box.bold).isTrue()
        assertThat(box.color).isEqualTo(0xFF112233.toInt())
        assertThat(box.align).isEqualTo(TextAlign.CENTER)
        assertThat(box.text).isEmpty()
    }

    @Test fun creationIsNotAnUndoStep() = runTest {
        val r = ToolRig(this)
        tap(ctxOf(r, ArrayList()), 50f, 60f)
        assertThat(r.editor.undo()).isFalse()
        assertThat(r.items()).hasSize(1)
    }

    @Test fun tapInsideExistingBoxEditsIt() = runTest {
        val r = ToolRig(this, seed = listOf(boxOf("hi")))
        val calls = ArrayList<Pair<ItemId, Boolean>>()
        tap(ctxOf(r, calls), 150f, 110f)
        assertThat(calls).containsExactly(ItemId(7) to false)
        assertThat(r.items()).hasSize(1)
    }

    @Test fun tapOutsideBoxCreatesAnother() = runTest {
        val r = ToolRig(this, seed = listOf(boxOf("hi")))
        val calls = ArrayList<Pair<ItemId, Boolean>>()
        tap(ctxOf(r, calls), 150f, 400f)
        assertThat(r.items()).hasSize(2)
        assertThat(calls.single().second).isTrue()
    }

    @Test fun rotatedBoxHitUsesRotatedFrame() = runTest {
        // 200×30 frame rotated 90° about its centre (200,115): covers x 185..215, y 15..215
        val r = ToolRig(this, seed = listOf(boxOf("hi", rot = 90f)))
        val calls = ArrayList<Pair<ItemId, Boolean>>()
        tap(ctxOf(r, calls), 200f, 200f)
        assertThat(calls).containsExactly(ItemId(7) to false)
        val calls2 = ArrayList<Pair<ItemId, Boolean>>()
        tap(ctxOf(r, calls2), 120f, 115f)          // inside the unrotated frame only
        assertThat(calls2.single().second).isTrue()
    }

    @Test fun dragCreatesBoxWithDraggedFrame() = runTest {
        val r = ToolRig(this)
        val calls = ArrayList<Pair<ItemId, Boolean>>()
        val s = TextPlaceSession(preset, ctxOf(r, calls))
        s.begin(ip(300f, 200f)); s.move(listOf(ip(150f, 260f, 5))); s.end(ip(100f, 300f, 10))
        val box = r.items().single() as TextBox
        assertThat(box.frame.l).isEqualTo(100f)
        assertThat(box.frame.t).isEqualTo(200f)
        assertThat(box.frame.width).isEqualTo(200f)
        assertThat(box.frame.height).isEqualTo(100f)
    }

    @Test fun boxNearRightEdgeStaysInsidePage() = runTest {
        val r = ToolRig(this)
        tap(ctxOf(r, ArrayList()), 560f, 60f)
        val box = r.items().single() as TextBox
        assertThat(box.frame.r).isAtMost(595.28f + 0.01f)
        assertThat(box.frame.l).isAtLeast(0f)
    }

    @Test fun cancelCreatesNothing() = runTest {
        val r = ToolRig(this)
        val s = TextPlaceSession(preset, ctxOf(r, ArrayList()))
        s.begin(ip(50f, 60f)); s.cancel(); s.end(ip(50f, 60f))
        assertThat(r.items()).isEmpty()
    }

    @Test fun estimateGrowsWithLines() {
        val m = TextLayoutMetrics.Estimate
        assertThat(m.measure("", 10f, false, 100f)).isEqualTo(12f)
        assertThat(m.measure("a\nb\nc", 10f, false, 100f)).isEqualTo(36f)
        assertThat(m.measure("x".repeat(100), 10f, false, 100f)).isGreaterThan(m.measure("x", 10f, false, 100f))
    }
}
