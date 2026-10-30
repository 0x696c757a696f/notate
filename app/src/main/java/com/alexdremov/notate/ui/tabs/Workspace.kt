package com.alexdremov.notate.ui.tabs

import kotlinx.serialization.Serializable

/** What a tab opens: a handwritten note (including imported PDFs) or an EPUB/HTML document. */
@Serializable
enum class DocKind { NOTE, WEB }

@Serializable
data class DocTab(
    val kind: DocKind,
    val path: String,
    val title: String,
)

@Serializable
enum class SplitOrientation {
    /** Left | right. */
    SIDE_BY_SIDE,

    /** Top / bottom. */
    STACKED,
}

/**
 * Two documents shown together. [first] is the left (or top) pane. [ratio] is the share of the
 * space taken by the first pane.
 */
@Serializable
data class SplitState(
    val first: String,
    val second: String,
    val orientation: SplitOrientation = SplitOrientation.SIDE_BY_SIDE,
    val ratio: Float = 0.5f,
) {
    fun contains(path: String) = first == path || second == path

    fun partnerOf(path: String): String? =
        when (path) {
            first -> second
            second -> first
            else -> null
        }
}

/**
 * The open tabs and the split pairs, like the tab strip in GoodNotes: opening a document adds a
 * tab, a tab remembers the document it is split with, closing a tab picks its neighbour.
 * Immutable; every operation returns the new state.
 */
@Serializable
data class Workspace(
    val tabs: List<DocTab> = emptyList(),
    val activePath: String? = null,
    val splits: List<SplitState> = emptyList(),
) {
    fun tab(path: String): DocTab? = tabs.firstOrNull { it.path == path }

    fun splitFor(path: String): SplitState? = splits.firstOrNull { it.contains(path) }

    /** Adds [tab] next to the active tab (or refreshes its title) and makes it active. */
    fun open(tab: DocTab): Workspace {
        val existing = tabs.indexOfFirst { it.path == tab.path }
        val newTabs =
            if (existing >= 0) {
                tabs.toMutableList().also { it[existing] = tab }
            } else {
                val activeIndex = tabs.indexOfFirst { it.path == activePath }
                tabs.toMutableList().also { it.add(if (activeIndex >= 0) activeIndex + 1 else it.size, tab) }
            }
        return copy(tabs = newTabs, activePath = tab.path)
    }

    fun activate(path: String): Workspace = if (tab(path) != null) copy(activePath = path) else this

    /**
     * Removes the tab and any split it was part of. If it was active, the tab to its right
     * (or else its left) becomes active.
     */
    fun close(path: String): Workspace {
        val index = tabs.indexOfFirst { it.path == path }
        if (index < 0) return this
        val newTabs = tabs.filterIndexed { i, _ -> i != index }
        val newActive =
            if (activePath == path) newTabs.getOrNull(index)?.path ?: newTabs.lastOrNull()?.path else activePath
        return copy(tabs = newTabs, activePath = newActive, splits = splits.filterNot { it.contains(path) })
    }

    /** Shows [other] next to [path]; each document can be in only one split. */
    fun split(
        path: String,
        other: String,
        orientation: SplitOrientation,
    ): Workspace {
        if (path == other) return this
        val kept = splits.filterNot { it.contains(path) || it.contains(other) }
        return copy(splits = kept + SplitState(first = path, second = other, orientation = orientation))
    }

    fun unsplit(path: String): Workspace = copy(splits = splits.filterNot { it.contains(path) })

    fun updateSplit(
        path: String,
        transform: (SplitState) -> SplitState,
    ): Workspace = copy(splits = splits.map { if (it.contains(path)) transform(it) else it })

    companion object {
        const val MIN_RATIO = 0.15f
        const val MAX_RATIO = 0.85f
    }
}
