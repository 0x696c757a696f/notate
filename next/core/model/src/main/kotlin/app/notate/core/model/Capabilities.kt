package app.notate.core.model

/** Features a document pane can offer; drives toolbar greying (04 §5). */
enum class Capability {
    PEN, HIGHLIGHTER, ERASER, SHAPES, RULER, LASSO, TEXT, IMAGE, LINK, ZOOM_WINDOW, PAGES, ADD_BLANK_PAGE, OUTLINE, PEN_MODE, HAND, INSERT_SPACE
}

object Capabilities {
    private val COMMON = setOf(Capability.PEN, Capability.HIGHLIGHTER, Capability.ERASER, Capability.SHAPES, Capability.RULER, Capability.HAND)
    private val PAGED = COMMON + setOf(
        Capability.LASSO, Capability.TEXT, Capability.IMAGE, Capability.LINK, Capability.ZOOM_WINDOW,
        Capability.PAGES, Capability.ADD_BLANK_PAGE, Capability.INSERT_SPACE,
    )

    /** Capabilities of a document kind. OUTLINE for NOTEBOOK is added dynamically by the panel when bookmarks exist. */
    fun forKind(kind: DocKind): Set<Capability> = when (kind) {
        DocKind.NOTEBOOK -> PAGED
        DocKind.PDF -> PAGED + Capability.OUTLINE
        DocKind.WEB -> COMMON + setOf(Capability.OUTLINE, Capability.PEN_MODE)
    }
}
