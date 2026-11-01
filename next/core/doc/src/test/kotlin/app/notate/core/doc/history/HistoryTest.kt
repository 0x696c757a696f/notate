package app.notate.core.doc.history

import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HistoryTest {
    private fun e(id: Long) = Edit.Items(PageId(1), emptyList(), listOf(stroke(id)))

    @Test fun undoRedoRoundTrip() {
        val h = History()
        h.record(e(1))
        assertThat(h.canUndo.value).isTrue()
        assertThat(h.undo()).isEqualTo(e(1).inverse())
        assertThat(h.canRedo.value).isTrue()
        assertThat(h.canUndo.value).isFalse()
        assertThat(h.redo()).isEqualTo(e(1))
        assertThat(h.canUndo.value).isTrue()
        assertThat(h.undo()).isEqualTo(e(1).inverse())
        assertThat(h.undo()).isNull()
    }

    @Test fun newRecordClearsRedo() {
        val h = History()
        h.record(e(1)); h.undo()
        h.record(e(2))
        assertThat(h.canRedo.value).isFalse()
        assertThat(h.redo()).isNull()
    }

    @Test fun limitDropsOldest() {
        val h = History(limit = 3)
        (1L..5L).forEach { h.record(e(it)) }
        val undone = generateSequence { h.undo() }.toList()
        assertThat(undone).containsExactly(e(5).inverse(), e(4).inverse(), e(3).inverse()).inOrder()
    }

    @Test fun groupBecomesOneBatch() {
        val h = History()
        h.beginGroup()
        h.record(e(1)); h.record(e(2)); h.record(e(3))
        assertThat(h.canUndo.value).isFalse()
        h.endGroup()
        assertThat(h.redo()).isNull()
        assertThat(h.undo()).isEqualTo(Edit.Batch(listOf(e(1), e(2), e(3))).inverse())
        assertThat(h.undo()).isNull()
    }

    @Test fun singleEditGroupIsNotWrappedAndEmptyGroupIsNothing() {
        val h = History()
        h.beginGroup(); h.endGroup()
        assertThat(h.canUndo.value).isFalse()
        h.beginGroup(); h.record(e(1)); h.endGroup()
        assertThat(h.undo()).isEqualTo(e(1).inverse())
    }

    @Test fun nestedGroupsFlatten() {
        val h = History()
        h.beginGroup(); h.record(e(1)); h.beginGroup(); h.record(e(2)); h.endGroup(); h.record(e(3)); h.endGroup()
        assertThat(h.undo()).isEqualTo(Edit.Batch(listOf(e(1), e(2), e(3))).inverse())
    }
}
