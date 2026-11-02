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

    // --- split (R5-2) ---

    @Test fun openBesideCreatesTheRightPaneAndFocusesIt() {
        val ws = WorkspaceOps.openBeside(wsOf("a"), tab("b"))
        assertThat(ws.leftName()).isEqualTo("a")
        assertThat(ws.rightName()).isEqualTo("b")
        assertThat(ws.focusRight).isTrue()
        assertThat(ws.split).isEqualTo(SplitSpec(Orientation.HORIZONTAL, 0.5f))
        assertThat(ws.names()).containsExactly("a", "b").inOrder()
    }

    @Test fun theSameDocumentCanBeInBothPanes() {
        val ws = WorkspaceOps.openBeside(wsOf("a"), tab("a"))
        assertThat(ws.names()).containsExactly("a")
        assertThat(ws.leftName()).isEqualTo("a")
        assertThat(ws.rightName()).isEqualTo("a")
        assertThat(WorkspaceOps.normalize(ws).right).isNotNull()
    }

    @Test fun openBesideInASplitReplacesThePaneWithoutFocus() {
        val ws = WorkspaceOps.openBeside(split(wsOf("a", "b", "c"), 0, 1).copy(focusRight = false), tab("c"))
        assertThat(ws.leftName()).isEqualTo("a")
        assertThat(ws.rightName()).isEqualTo("c")
        assertThat(ws.focusRight).isTrue()
        val other = WorkspaceOps.openBeside(ws, tab("b"))
        assertThat(other.leftName()).isEqualTo("b")
        assertThat(other.rightName()).isEqualTo("c")
        assertThat(other.focusRight).isFalse()
    }

    @Test fun swapKeepsTheFocusOnTheSameDocumentAndFlipsTheRatio() {
        val ws = WorkspaceOps.setRatio(split(wsOf("a", "b"), 0, 1).copy(focusRight = true), 2f / 3f)
        val swapped = WorkspaceOps.swap(ws)
        assertThat(swapped.leftName()).isEqualTo("b")
        assertThat(swapped.rightName()).isEqualTo("a")
        assertThat(swapped.focusRight).isFalse()
        assertThat(swapped.focusedTab!!.doc.value).isEqualTo("b")
        assertThat(swapped.split!!.ratio).isWithin(0.001f).of(1f / 3f)
    }

    @Test fun closingAPaneKeepsItsTab() {
        val ws = split(wsOf("a", "b"), 0, 1)
        val closed = WorkspaceOps.closePane(ws, right = true)
        assertThat(closed.names()).containsExactly("a", "b")
        assertThat(closed.leftName()).isEqualTo("a")
        assertThat(closed.right).isNull()
        assertThat(closed.split).isNull()
        val leftClosed = WorkspaceOps.closePane(ws, right = false)
        assertThat(leftClosed.leftName()).isEqualTo("b")
        assertThat(leftClosed.names()).containsExactly("a", "b")
    }

    @Test fun maximizeKeepsOnlyThatPane() {
        val ws = split(wsOf("a", "b"), 0, 1)
        val max = WorkspaceOps.maximize(ws, right = true)
        assertThat(max.leftName()).isEqualTo("b")
        assertThat(max.right).isNull()
        assertThat(max.names()).containsExactly("a", "b")
    }

    @Test fun orientationAndRatioChangeOnlyTheSplit() {
        val ws = split(wsOf("a", "b"), 0, 1)
        val v = WorkspaceOps.setOrientation(ws, Orientation.VERTICAL)
        assertThat(v.split!!.orientation).isEqualTo(Orientation.VERTICAL)
        assertThat(WorkspaceOps.setOrientation(v, Orientation.VERTICAL)).isSameInstanceAs(v)
        assertThat(WorkspaceOps.setRatio(v, 0.99f).split!!.ratio).isEqualTo(0.95f)
        assertThat(WorkspaceOps.setOrientation(wsOf("a"), Orientation.VERTICAL).split).isNull()
    }

    @Test fun focusMovesOnlyToExistingPanes() {
        val single = wsOf("a")
        assertThat(WorkspaceOps.focus(single, right = true)).isSameInstanceAs(single)
        val ws = split(wsOf("a", "b"), 0, 1).copy(focusRight = false)
        assertThat(WorkspaceOps.focus(ws, right = true).focusRight).isTrue()
    }

    @Test fun closingTheTabShownInBothPanesLeavesOnePane() {
        val ws = WorkspaceOps.openBeside(wsOf("a", "b"), tab("a"))
        val closed = WorkspaceOps.close(ws, 0)
        assertThat(closed.names()).containsExactly("b")
        assertThat(closed.right).isNull()
        assertThat(closed.leftName()).isEqualTo("b")
    }

    @Test fun paneViewsAreStoredPerPaneEvenForTheSameDocument() {
        val ws = WorkspaceOps.openBeside(wsOf("a"), tab("a"))
        val l = PaneViewStateDto(1, 1f, 0f, 0f)
        val r = PaneViewStateDto(2, 2f, 0f, 10f)
        val out = WorkspaceOps.withPaneView(WorkspaceOps.withPaneView(ws, false, l), true, r)
        assertThat(out.left!!.view).isEqualTo(l)
        assertThat(out.right!!.view).isEqualTo(r)
    }
}
