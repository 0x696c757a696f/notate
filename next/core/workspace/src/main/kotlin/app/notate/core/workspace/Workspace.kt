package app.notate.core.workspace

import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.PageId
import app.notate.core.model.PageLayoutMode
import app.notate.core.model.ViewState
import kotlinx.serialization.Serializable

/** One open document in the tab strip. */
@Serializable
data class Tab(val doc: DocId, val kind: DocKind, val title: String)

/** Viewport of a pane, same fields as [ViewState] but owned by the workspace file. */
@Serializable
data class PaneViewStateDto(
    val page: Long,
    val zoom: Float,
    val scrollXPt: Float,
    val scrollYPt: Float,
    val layout: PageLayoutMode = PageLayoutMode.CONTINUOUS,
) {
    fun toViewState() = ViewState(PageId(page), zoom, scrollXPt, scrollYPt, layout)

    companion object {
        fun from(s: ViewState) = PaneViewStateDto(s.page.value, s.zoom, s.scrollXPt, s.scrollYPt, s.layout)
    }
}

/** What a pane shows: an index into [Workspace.tabs] and, once known, where the reader was in it. */
@Serializable
data class PaneSlot(val tab: Int, val view: PaneViewStateDto? = null)

@Serializable
enum class Orientation { HORIZONTAL, VERTICAL }

/** [ratio] is the share of the space that the left (or top) pane takes. */
@Serializable
data class SplitSpec(val orientation: Orientation, val ratio: Float)

/**
 * The open tabs and what the panes show (04 §6). Immutable; change it only through [WorkspaceOps]. [left] is null exactly when
 * [tabs] is empty; [right] and [split] are set together while the window is split.
 */
@Serializable
data class Workspace(
    val tabs: List<Tab> = emptyList(),
    val left: PaneSlot? = null,
    val right: PaneSlot? = null,
    val focusRight: Boolean = false,
    val split: SplitSpec? = null,
) {
    /** The slot that has the focus (the left one when the window is not split). */
    val focusedSlot: PaneSlot? get() = if (focusRight && right != null) right else left

    val focusedTab: Tab? get() = focusedSlot?.let { tabs.getOrNull(it.tab) }

    fun indexOf(doc: DocId): Int = tabs.indexOfFirst { it.doc == doc }

    val isEmpty: Boolean get() = tabs.isEmpty()
}
