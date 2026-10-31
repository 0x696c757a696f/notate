package com.alexdremov.notate.doc

/** Identifies an open document: a note's file path or a web document's id. */
data class DocRef(
    val kind: DocKind,
    val id: String,
    val title: String = id,
)
