package com.alexdremov.notate.webdoc

import kotlinx.serialization.Serializable

/**
 * Splits a scrolled page into screen-sized slices for export.
 * Each slice is captured at [scrollY]; rows [srcTop, srcTop + height) of the capture are kept,
 * so the last slice does not repeat content when the page cannot scroll a full screen further.
 */
object WebDocExportPlan {
    data class Slice(
        val scrollY: Int,
        val srcTop: Int,
        val height: Int,
    )

    fun slices(
        contentHeightPx: Int,
        viewportHeightPx: Int,
        maxSlices: Int = 300,
    ): List<Slice> {
        if (viewportHeightPx <= 0) return emptyList()
        val content = contentHeightPx.coerceAtLeast(viewportHeightPx)
        val maxScroll = content - viewportHeightPx
        val out = ArrayList<Slice>()
        var y = 0
        while (y < content && out.size < maxSlices) {
            val scroll = minOf(y, maxScroll)
            val srcTop = y - scroll
            val height = minOf(viewportHeightPx - srcTop, content - y)
            if (height <= 0) break
            out.add(Slice(scroll, srcTop, height))
            y += height
        }
        return out
    }
}

/** Portable dump of a document's notes, for backup or processing elsewhere. */
@Serializable
data class WebDocNotesExport(
    val format: String = "notate-webdoc-notes",
    val version: Int = 1,
    val title: String,
    val kind: WebDocKind,
    val sourceName: String,
    val pages: List<WebDocPage>,
    val annotations: WebDocAnnotations,
)
