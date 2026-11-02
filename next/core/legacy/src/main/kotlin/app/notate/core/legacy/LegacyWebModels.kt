package app.notate.core.legacy

import kotlinx.serialization.Serializable

/* Read-only copies of the old `webdoc` JSON models (WebDocModels.kt, WebInkStoreV2.kt). Property names must match the JSON keys. */

@Serializable enum class LegacyWebKind { EPUB, HTML }

@Serializable data class LegacyWebPage(val href: String, val title: String)

@Serializable
data class LegacyWebPolicy(
    val javaScriptEnabled: Boolean = true,
    val networkAllowed: Boolean = false,
    val reduceMotion: Boolean = false,
)

/** `meta.json`. */
@Serializable
data class LegacyWebMeta(
    val id: String,
    val title: String,
    val kind: LegacyWebKind = LegacyWebKind.HTML,
    val pages: List<LegacyWebPage> = emptyList(),
    val fixedLayout: Boolean = false,
    val sourceName: String = "",
    val importedAt: Long = 0L,
    val policy: LegacyWebPolicy = LegacyWebPolicy(),
)

@Serializable enum class LegacyWebInkTool { PEN, HIGHLIGHTER }

/** One stroke; points are CSS px, interleaved x,y. */
@Serializable
class LegacyWebStroke(
    val tool: LegacyWebInkTool,
    val color: Int,
    val width: Float,
    val points: FloatArray,
    val style: LegacyStrokeType = LegacyStrokeType.FINELINER,
    val pressures: FloatArray? = null,
)

@Serializable
data class LegacyWebLayout(val widthCss: Float, val textZoom: Int = 100, val gen: Int = 1)

/** v1 `annotations.json`. */
@Serializable
internal class LegacyWebV1(
    val version: Int = 1,
    val strokes: Map<String, List<LegacyWebStroke>> = emptyMap(),
    val lastPage: String? = null,
    val scrollY: Map<String, Float> = emptyMap(),
    val layoutWidth: Map<String, Float> = emptyMap(),
    val penMode: String = "WRITE",
    val layout: LegacyWebLayout? = null,
)

/** v2 `state.json`. */
@Serializable
internal class LegacyWebState(
    val version: Int = 2,
    val lastPage: String? = null,
    val scrollY: Map<String, Float> = emptyMap(),
    val layoutWidth: Map<String, Float> = emptyMap(),
    val penMode: String = "WRITE",
    val appliedSeq: Long = 0,
    val layout: LegacyWebLayout? = null,
    val pages: Map<String, String> = emptyMap(),
)

/** v2 `ink/<key>.json`. */
@Serializable
internal class LegacyWebInkFile(val href: String, val strokes: List<LegacyWebStroke>)

/** One line of `ink.journal`: `k` is `add` (append [st]) or `set` (replace the page's strokes). */
@Serializable
internal class LegacyWebJournalOp(val s: Long, val h: String, val k: String, val st: List<LegacyWebStroke>)

/** Everything read from one old web document folder (`webdocs/<id>/`). */
class LegacyWebDoc(
    val meta: LegacyWebMeta,
    val strokes: Map<String, List<LegacyWebStroke>>,
    val lastPage: String?,
    /** CSS px per page href. */
    val scrollY: Map<String, Float>,
    val layoutWidth: Map<String, Float>,
    val layout: LegacyWebLayout?,
    val penMode: String,
    /** The `content/` folder holding the unpacked EPUB/HTML resources. */
    val contentDir: java.io.File,
    val warnings: List<String>,
)
