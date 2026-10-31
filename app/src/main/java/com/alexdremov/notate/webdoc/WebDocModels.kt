package com.alexdremov.notate.webdoc

import com.alexdremov.notate.model.StrokeType
import kotlinx.serialization.Serializable

/** What kind of source a web document was imported from. */
@Serializable
enum class WebDocKind {
    /** EPUB 2/3 package (reflowable or fixed layout). */
    EPUB,

    /** A standalone HTML file, a ZIP bundle or a whole folder with an HTML entry point. */
    HTML,
}

/** One readable unit: an EPUB spine item, or the single entry page of an HTML bundle. */
@Serializable
data class WebDocPage(
    /** Path relative to the document's content root, '/'-separated, no fragment. */
    val href: String,
    val title: String,
)

/**
 * Per-document security and display options. Stored in meta.json so every document
 * keeps its own choices (a trusted textbook can get network access, a random EPUB does not).
 */
@Serializable
data class WebDocPolicy(
    val javaScriptEnabled: Boolean = true,
    val networkAllowed: Boolean = false,
    /** Injects CSS that removes transitions/animations, which ghost badly on E Ink. */
    val reduceMotion: Boolean = false,
)

@Serializable
data class WebDocMeta(
    val id: String,
    val title: String,
    val kind: WebDocKind,
    val pages: List<WebDocPage>,
    /** EPUB `rendition:layout` = `pre-paginated`. */
    val fixedLayout: Boolean = false,
    val sourceName: String = "",
    val importedAt: Long = 0L,
    val policy: WebDocPolicy = WebDocPolicy(),
)

/**
 * A handwritten stroke anchored to the document, not to the screen.
 * Coordinates are CSS pixels relative to the top-left of the page's document,
 * so the ink stays on the content while the page scrolls or zooms.
 */
@Serializable
class WebInkStroke(
    val tool: WebInkTool,
    val color: Int,
    /** Width in CSS px. */
    val width: Float,
    /** Interleaved x0, y0, x1, y1, ... in CSS px. */
    val points: FloatArray,
    /** Pen type: decides dashes/dots and, with [pressures], the pen's pressure response. */
    val style: StrokeType = StrokeType.FINELINER,
    /** Normalized pen pressure (0..1), one per point; null for shapes and strokes saved before pressure was recorded. */
    val pressures: FloatArray? = null,
)

@Serializable
enum class WebInkTool { PEN, HIGHLIGHTER }

@Serializable
data class WebDocAnnotations(
    val version: Int = 1,
    /** Keyed by [WebDocPage.href]. */
    val strokes: Map<String, List<WebInkStroke>> = emptyMap(),
    val lastPage: String? = null,
    /** Last vertical scroll per page in CSS px, to reopen where the reader left off. */
    val scrollY: Map<String, Float> = emptyMap(),
    /**
     * Content width (CSS px) of each page when its first ink was saved. Reflowable pages whose
     * width later differs move their text away from the handwriting; this lets the reader say so.
     */
    val layoutWidth: Map<String, Float> = emptyMap(),
)
