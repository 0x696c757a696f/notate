package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TextEditsTest {
    private val m = TextLayoutMetrics.Estimate
    private val style = TextStyle(10f, false, 0xFF000000.toInt(), TextAlign.LEFT)
    private val frame = Rect(10f, 10f, 110f, 22f)

    private fun box(text: String) = TextBox(ItemId(5), 5, frame, 0f, text, 10f, 0xFF000000.toInt(), false, TextAlign.LEFT, ItemBounds.frameBounds(frame, 0f))

    @Test fun existingBoxEditIsOneUndoableReplace() = runTest {
        val r = ToolRig(this, seed = listOf(box("a")))
        val out = TextEdits.commit(r.editor, r.p1, ItemId(5), false, "hello", style, m)!!
        assertThat((r.items().single() as TextBox).text).isEqualTo("hello")
        assertThat(out.frame.width).isEqualTo(100f)
        r.editor.undo()
        assertThat((r.items().single() as TextBox).text).isEqualTo("a")
    }

    @Test fun blankTextDeletesAndUndoRestores() = runTest {
        val r = ToolRig(this, seed = listOf(box("a")))
        assertThat(TextEdits.commit(r.editor, r.p1, ItemId(5), false, "  \n ", style, m)).isNull()
        assertThat(r.items()).isEmpty()
        r.editor.undo()
        assertThat(r.items()).hasSize(1)
    }

    @Test fun newBlankBoxVanishesWithoutHistory() = runTest {
        val r = ToolRig(this, seed = listOf(box("")))
        r.editor.undo()                                  // seed is not undoable → nothing to undo
        TextEdits.commit(r.editor, r.p1, ItemId(5), true, "", style, m)
        assertThat(r.items()).isEmpty()
        assertThat(r.editor.undo()).isFalse()
    }

    @Test fun newBoxWithTextIsOneUndoStepThatRemovesIt() = runTest {
        val r = ToolRig(this, seed = listOf(box("")))
        TextEdits.commit(r.editor, r.p1, ItemId(5), true, "F = ma", style, m)
        assertThat((r.items().single() as TextBox).text).isEqualTo("F = ma")
        assertThat(r.editor.undo()).isTrue()
        assertThat(r.items()).isEmpty()
        assertThat(r.editor.undo()).isFalse()
    }

    @Test fun styleChangesApply() = runTest {
        val r = ToolRig(this, seed = listOf(box("x")))
        TextEdits.commit(r.editor, r.p1, ItemId(5), false, "x", TextStyle(24f, true, 0xFFFF0000.toInt(), TextAlign.RIGHT), m)
        val t = r.items().single() as TextBox
        assertThat(t.fontSizePt).isEqualTo(24f)
        assertThat(t.bold).isTrue()
        assertThat(t.color).isEqualTo(0xFFFF0000.toInt())
        assertThat(t.align).isEqualTo(TextAlign.RIGHT)
        assertThat(t.frame.height).isAtLeast(24f)
    }

    @Test fun unchangedEditAddsNoHistory() = runTest {
        val b = TextEdits.updated(box("same"), "same", style, m)
        val r = ToolRig(this, seed = listOf(b))
        assertThat(TextEdits.commit(r.editor, r.p1, ItemId(5), false, "same", style, m)).isNull()
        assertThat(r.editor.undo()).isFalse()
    }

    @Test fun heightGrowsWithLines() = runTest {
        val r = ToolRig(this, seed = listOf(box("a")))
        val one = TextEdits.commit(r.editor, r.p1, ItemId(5), false, "one", style, m)!!.frame.height
        val three = TextEdits.commit(r.editor, r.p1, ItemId(5), false, "one\ntwo\nthree", style, m)!!.frame.height
        assertThat(three).isGreaterThan(one * 2.5f)
    }
}
