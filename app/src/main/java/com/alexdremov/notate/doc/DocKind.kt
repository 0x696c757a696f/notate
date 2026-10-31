package com.alexdremov.notate.doc

import kotlinx.serialization.Serializable

/**
 * What a tab opens: a handwritten note (including imported PDFs) or an EPUB/HTML document.
 * Names are stored in workspace files: never rename or reorder.
 */
@Serializable
enum class DocKind { NOTE, WEB }
