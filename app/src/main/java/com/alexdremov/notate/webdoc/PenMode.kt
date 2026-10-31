package com.alexdremov.notate.webdoc

/**
 * What the pen does on a web document: [WRITE] inks, [INTERACT] hands pen events to the page
 * (links, buttons, scrolling). Kept per document, and per pane in a split.
 */
enum class PenMode {
    WRITE,
    INTERACT,
    ;

    fun toggled() = if (this == WRITE) INTERACT else WRITE

    companion object {
        fun parse(name: String?): PenMode = entries.firstOrNull { it.name == name } ?: WRITE
    }
}
