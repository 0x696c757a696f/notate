package com.alexdremov.notate.doc

/** What a document lets the user do; the toolbar greys out tools the focused document lacks. */
enum class Capability {
    PEN,
    HIGHLIGHTER,
    ERASER,
    SHAPES,
    LASSO,
    TEXT,
    INSERT_IMAGE,
    PAGES,
    ;

    companion object {
        val NOTE: Set<Capability> = entries.toSet()
        val WEB: Set<Capability> = setOf(PEN, HIGHLIGHTER, ERASER, SHAPES)
    }
}
