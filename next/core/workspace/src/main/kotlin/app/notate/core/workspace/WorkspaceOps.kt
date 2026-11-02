package app.notate.core.workspace

import app.notate.core.model.DocId

/** Pure operations on [Workspace]; every one returns the new state (or the same instance when nothing changed). */
object WorkspaceOps {
    /**
     * Shows [tab] in the focused pane (or in the other pane when [inFocusedPane] is false and the window is split). A document
     * that already has a tab is not opened twice: its title is refreshed and the tab is selected.
     */
    fun open(ws: Workspace, tab: Tab, inFocusedPane: Boolean = true): Workspace {
        val existing = ws.indexOf(tab.doc)
        if (existing >= 0) {
            val renamed = if (ws.tabs[existing] == tab) ws else ws.copy(tabs = ws.tabs.toMutableList().also { it[existing] = tab })
            return select(renamed, existing, inFocusedPane)
        }
        val index = ws.tabs.size
        return place(ws.copy(tabs = ws.tabs + tab), index, inFocusedPane)
    }

    /**
     * Closes tab [index]. A pane that showed it moves to the neighbour on the right, else on the left. When that neighbour is
     * what the other pane shows, the pane is closed instead and the window is no longer split. Closing the last tab empties
     * the workspace.
     */
    fun close(ws: Workspace, index: Int): Workspace {
        if (index !in ws.tabs.indices) return ws
        val tabs = ws.tabs.filterIndexed { i, _ -> i != index }
        if (tabs.isEmpty()) return Workspace()
        fun shift(i: Int) = if (i > index) i - 1 else i
        var left = ws.left
        var right = ws.right
        var focusRight = ws.focusRight && right != null
        fun neighbour(avoid: Int?): Int? {
            val candidates = listOf(index, index - 1, index + 1, index - 2).filter { it in tabs.indices }   // in new numbering
            return candidates.firstOrNull { it != avoid } ?: if (avoid == null) candidates.firstOrNull() else null
        }
        val leftHit = left?.tab == index
        val rightHit = right?.tab == index
        if (right == null) {
            if (leftHit) left = PaneSlot(neighbour(null)!!) else left = left?.copy(tab = shift(left.tab))
        } else {
            val l = left!!.tab
            val r = right.tab
            when {
                leftHit -> {
                    val n = neighbour(shift(r))
                    if (n == null) { left = PaneSlot(shift(r), right.view); right = null; focusRight = false }
                    else left = PaneSlot(n)
                    if (right != null) right = right.copy(tab = shift(r))
                }
                rightHit -> {
                    val n = neighbour(shift(l))
                    left = left.copy(tab = shift(l))
                    if (n == null) { right = null; focusRight = false } else right = PaneSlot(n)
                }
                else -> { left = left.copy(tab = shift(l)); right = right.copy(tab = shift(r)) }
            }
        }
        val splitKept = if (right != null) ws.split else null
        return Workspace(tabs, left, right, focusRight && right != null, splitKept)
    }

    /** Keeps only tab [keep], shown in the left pane. */
    fun closeOthers(ws: Workspace, keep: Int): Workspace {
        if (keep !in ws.tabs.indices) return ws
        val view = listOfNotNull(ws.left, ws.right).firstOrNull { it.tab == keep }?.view
        return Workspace(listOf(ws.tabs[keep]), PaneSlot(0, view))
    }

    /**
     * Shows tab [index] in the focused pane. When the other pane already shows it, only the focus moves there. Selecting
     * the tab the focused pane shows changes nothing.
     */
    fun select(ws: Workspace, index: Int, inFocusedPane: Boolean = true): Workspace {
        if (index !in ws.tabs.indices) return ws
        return place(ws, index, inFocusedPane)
    }

    /** Moves tab [from] to position [to]; the panes keep showing the same documents. */
    fun move(ws: Workspace, from: Int, to: Int): Workspace {
        if (from !in ws.tabs.indices || to !in ws.tabs.indices || from == to) return ws
        val order = ws.tabs.indices.toMutableList().also { it.add(to, it.removeAt(from)) }   // new position -> old index
        val newIndex = IntArray(ws.tabs.size).also { a -> order.forEachIndexed { pos, old -> a[old] = pos } }
        return ws.copy(
            tabs = order.map { ws.tabs[it] },
            left = ws.left?.let { it.copy(tab = newIndex[it.tab]) },
            right = ws.right?.let { it.copy(tab = newIndex[it.tab]) },
        )
    }

    /** Changes the title of the tab that shows [doc]. */
    fun rename(ws: Workspace, doc: DocId, title: String): Workspace {
        val i = ws.indexOf(doc)
        if (i < 0 || ws.tabs[i].title == title) return ws
        return ws.copy(tabs = ws.tabs.toMutableList().also { it[i] = it[i].copy(title = title) })
    }

    /** Records where the reader is in the pane that shows tab [index] (no-op when no pane shows it). */
    fun withView(ws: Workspace, index: Int, view: PaneViewStateDto?): Workspace {
        val l = ws.left
        val r = ws.right
        return when {
            l != null && l.tab == index && l.view != view -> ws.copy(left = l.copy(view = view))
            r != null && r.tab == index && r.view != view -> ws.copy(right = r.copy(view = view))
            else -> ws
        }
    }

    /** Closes every tab whose document [exists] says is gone (deleted or in the trash). */
    fun prune(ws: Workspace, exists: (DocId) -> Boolean): Workspace =
        ws.tabs.indices.reversed().filter { !exists(ws.tabs[it].doc) }.fold(ws) { acc, i -> close(acc, i) }

    /** Repairs a workspace read from disk: indices out of range, a split without a right pane, duplicated documents. */
    fun normalize(ws: Workspace): Workspace {
        val seen = HashSet<DocId>()
        val tabs = ws.tabs.filter { seen.add(it.doc) }
        if (tabs.isEmpty()) return Workspace()
        // Duplicates would shift indices; only trust the slots when nothing was dropped.
        val trusted = tabs.size == ws.tabs.size
        val left = ws.left?.takeIf { trusted && it.tab in tabs.indices } ?: PaneSlot(0)
        val right = ws.right?.takeIf { trusted && it.tab in tabs.indices && it.tab != left.tab }
        return Workspace(tabs, left, right, ws.focusRight && right != null, if (right != null) ws.split else null)
    }

    private fun place(ws: Workspace, index: Int, inFocusedPane: Boolean): Workspace {
        val l = ws.left
        val r = ws.right
        if (l == null) return ws.copy(left = PaneSlot(index), focusRight = false)
        if (l.tab == index) return ws.copy(focusRight = false)
        if (r != null && r.tab == index) return ws.copy(focusRight = true)
        val targetRight = r != null && (ws.focusRight == inFocusedPane)
        return if (targetRight) ws.copy(right = PaneSlot(index), focusRight = true) else ws.copy(left = PaneSlot(index), focusRight = false)
    }
}
