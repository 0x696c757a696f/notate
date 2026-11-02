package app.notate.core.workspace

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WorkspaceOpsTest {
    private fun tab(n: String, kind: DocKind = DocKind.NOTEBOOK) = Tab(DocId(n), kind, "Title $n")
    private fun wsOf(vararg names: String): Workspace = names.fold(Workspace()) { ws, n -> WorkspaceOps.open(ws, tab(n)) }
    private fun Workspace.names() = tabs.map { it.doc.value }
    private fun Workspace.leftName() = left?.let { tabs[it.tab].doc.value }
    private fun Workspace.rightName() = right?.let { tabs[it.tab].doc.value }
    private fun split(ws: Workspace, left: Int, right: Int) =
        ws.copy(left = PaneSlot(left), right = PaneSlot(right), split = SplitSpec(Orientation.HORIZONTAL, 0.5f), focusRight = false)

    @Test fun openAppendsAndShowsTheNewTab() {
        val ws = wsOf("a", "b", "c")
        assertThat(ws.names()).containsExactly("a", "b", "c").inOrder()
        assertThat(ws.leftName()).isEqualTo("c")
        assertThat(ws.focusedTab!!.doc).isEqualTo(DocId("c"))
    }

    @Test fun openingTheSameDocumentTwiceOnlySwitches() {
        val ws = wsOf("a", "b")
        val again = WorkspaceOps.open(ws, tab("a").copy(title = "New name"))
        assertThat(again.tabs).hasSize(2)
        assertThat(again.leftName()).isEqualTo("a")
        assertThat(again.tabs[0].title).isEqualTo("New name")
    }

    @Test fun closingTheVisibleTabShowsTheNeighbourOnTheRight() {
        val ws = WorkspaceOps.select(wsOf("a", "b", "c"), 1)
        val closed = WorkspaceOps.close(ws, 1)
        assertThat(closed.names()).containsExactly("a", "c").inOrder()
        assertThat(closed.leftName()).isEqualTo("c")
    }

    @Test fun closingTheLastVisibleTabShowsTheOneOnTheLeft() {
        val closed = WorkspaceOps.close(wsOf("a", "b", "c"), 2)
        assertThat(closed.leftName()).isEqualTo("b")
    }

    @Test fun closingAHiddenTabKeepsTheVisibleOne() {
        val ws = WorkspaceOps.select(wsOf("a", "b", "c"), 2)
        val closed = WorkspaceOps.close(ws, 0)
        assertThat(closed.names()).containsExactly("b", "c").inOrder()
        assertThat(closed.leftName()).isEqualTo("c")
        assertThat(closed.left!!.tab).isEqualTo(1)
    }

    @Test fun closingTheOnlyTabEmptiesTheWorkspace() {
        val closed = WorkspaceOps.close(wsOf("a"), 0)
        assertThat(closed.tabs).isEmpty()
        assertThat(closed.left).isNull()
        assertThat(closed.isEmpty).isTrue()
    }

    @Test fun closeOutOfRangeChangesNothing() {
        val ws = wsOf("a", "b")
        assertThat(WorkspaceOps.close(ws, 5)).isSameInstanceAs(ws)
        assertThat(WorkspaceOps.close(ws, -1)).isSameInstanceAs(ws)
    }

    @Test fun closeOthersKeepsOnlyThatTab() {
        val ws = wsOf("a", "b", "c")
        val kept = WorkspaceOps.closeOthers(ws, 0)
        assertThat(kept.names()).containsExactly("a")
        assertThat(kept.leftName()).isEqualTo("a")
        assertThat(kept.right).isNull()
    }

    @Test fun closeOthersKeepsTheViewOfThatTab() {
        val view = PaneViewStateDto(3, 1.5f, 10f, 20f)
        val ws = WorkspaceOps.withView(wsOf("a", "b"), 1, view)
        assertThat(WorkspaceOps.closeOthers(ws, 1).left!!.view).isEqualTo(view)
    }

    @Test fun selectPutsTheTabInTheFocusedPane() {
        val ws = WorkspaceOps.select(wsOf("a", "b", "c"), 0)
        assertThat(ws.leftName()).isEqualTo("a")
        assertThat(ws.left!!.view).isNull()
    }

    @Test fun selectingATabShownInTheOtherPaneOnlyMovesTheFocus() {
        val ws = split(wsOf("a", "b", "c"), 0, 1)
        val moved = WorkspaceOps.select(ws, 1)
        assertThat(moved.focusRight).isTrue()
        assertThat(moved.leftName()).isEqualTo("a")
        assertThat(moved.rightName()).isEqualTo("b")
        assertThat(WorkspaceOps.select(moved, 0).focusRight).isFalse()
    }

    @Test fun selectWithTheRightPaneFocusedReplacesTheRightPane() {
        val ws = split(wsOf("a", "b", "c"), 0, 1).copy(focusRight = true)
        val s = WorkspaceOps.select(ws, 2)
        assertThat(s.leftName()).isEqualTo("a")
        assertThat(s.rightName()).isEqualTo("c")
    }

    @Test fun openInTheOtherPaneOfASplit() {
        val ws = split(wsOf("a", "b"), 0, 1).copy(focusRight = false)
        val opened = WorkspaceOps.open(ws, tab("c"), inFocusedPane = false)
        assertThat(opened.leftName()).isEqualTo("a")
        assertThat(opened.rightName()).isEqualTo("c")
        assertThat(opened.focusRight).isTrue()
    }

    @Test fun closingAPaneTabThatWouldDuplicateTheOtherPaneClosesThePane() {
        val ws = split(wsOf("a", "b"), 0, 1)
        val closed = WorkspaceOps.close(ws, 0)
        assertThat(closed.names()).containsExactly("b")
        assertThat(closed.leftName()).isEqualTo("b")
        assertThat(closed.right).isNull()
        assertThat(closed.split).isNull()
    }

    @Test fun closingASplitTabPicksANeighbourNotShownByTheOtherPane() {
        val ws = split(wsOf("a", "b", "c"), 0, 1)
        val closed = WorkspaceOps.close(ws, 0)
        assertThat(closed.names()).containsExactly("b", "c").inOrder()
        assertThat(closed.leftName()).isEqualTo("c")
        assertThat(closed.rightName()).isEqualTo("b")
        assertThat(closed.split).isNotNull()
    }

    @Test fun closingAHiddenTabReindexesBothPanes() {
        val ws = split(wsOf("a", "b", "c", "d"), 2, 3)
        val closed = WorkspaceOps.close(ws, 0)
        assertThat(closed.leftName()).isEqualTo("c")
        assertThat(closed.rightName()).isEqualTo("d")
    }

    @Test fun moveReordersAndKeepsPanesOnTheirDocuments() {
        val ws = split(wsOf("a", "b", "c"), 0, 2)
        val moved = WorkspaceOps.move(ws, 0, 2)
        assertThat(moved.names()).containsExactly("b", "c", "a").inOrder()
        assertThat(moved.leftName()).isEqualTo("a")
        assertThat(moved.rightName()).isEqualTo("c")
        val back = WorkspaceOps.move(moved, 2, 0)
        assertThat(back.names()).containsExactly("a", "b", "c").inOrder()
    }

    @Test fun renameChangesOnlyTheTitle() {
        val ws = WorkspaceOps.rename(wsOf("a", "b"), DocId("a"), "Calculus")
        assertThat(ws.tabs[0].title).isEqualTo("Calculus")
        assertThat(ws.tabs[1].title).isEqualTo("Title b")
        assertThat(WorkspaceOps.rename(ws, DocId("zzz"), "x")).isSameInstanceAs(ws)
    }

    @Test fun pruneDropsDeletedDocuments() {
        val ws = WorkspaceOps.select(wsOf("a", "b", "c"), 1)
        val pruned = WorkspaceOps.prune(ws) { it.value != "b" }
        assertThat(pruned.names()).containsExactly("a", "c").inOrder()
        assertThat(pruned.leftName()).isEqualTo("c")
        assertThat(WorkspaceOps.prune(ws) { false }.isEmpty).isTrue()
    }

    @Test fun normalizeRepairsBrokenIndices() {
        val broken = Workspace(listOf(tab("a"), tab("b")), left = PaneSlot(7), right = PaneSlot(7), focusRight = true)
        val fixed = WorkspaceOps.normalize(broken)
        assertThat(fixed.left!!.tab).isEqualTo(0)
        assertThat(fixed.right).isNull()
        assertThat(fixed.focusRight).isFalse()
        assertThat(WorkspaceOps.normalize(Workspace(emptyList(), PaneSlot(0))).left).isNull()
    }
}
