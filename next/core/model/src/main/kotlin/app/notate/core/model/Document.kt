package app.notate.core.model

import app.notate.core.model.Units.mm
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DocKind(val code: Int) {
    NOTEBOOK(0), PDF(1), WEB(2);

    companion object {
        fun fromCode(code: Int): DocKind =
            entries.firstOrNull { it.code == code } ?: throw CorruptDataException("Unknown DocKind code $code")
    }
}

/** Per-document metadata (one row of `doc.db`). */
@Serializable
data class DocumentMeta(
    val id: DocId,
    val title: String,
    val kind: DocKind,
    val createdAt: Long,
    val modifiedAt: Long,
    val formatVersion: Int = 1,
    val newPageTemplate: PageTemplate = PageTemplate.DEFAULT,
    val view: ViewState? = null,
    val web: WebDocSettings? = null,
)

/** Last viewport of a document. */
@Serializable
data class ViewState(val page: PageId, val zoom: Float, val scrollXPt: Float, val scrollYPt: Float, val layout: PageLayoutMode)

@Serializable
enum class PageLayoutMode { CONTINUOUS, SINGLE }

@Serializable
enum class PenMode { WRITE, INTERACT, READ_ONLY }

@Serializable
data class WebDocSettings(
    val javascript: Boolean = true,
    val network: Boolean = false,
    val reflow: Boolean = false,
    val penMode: PenMode = PenMode.WRITE,
    val reduceMotion: Boolean = true,
    val textZoom: Int = 100,
    /** SHA-256 of the imported source file, for duplicate-import detection. */
    val sourceHash: String? = null,
)

/** Page record. [height] null = whiteboard page or web chapter page. */
@Serializable
data class PageInfo(
    val id: PageId,
    val ord: Double,
    val width: Float,
    val height: Float?,
    val background: Background,
    val inkRev: Long,
    val modifiedAt: Long,
    val bookmarked: Boolean = false,
)

@Serializable
enum class PaperPattern { BLANK, LINED, GRID, DOTS, CORNELL, ENGINEERING, ISOMETRIC }

@Serializable
sealed interface Background {
    /** Ruled paper; for CORNELL, [marginLeftPt] is the cue column width and [headerPt] the title area. */
    @Serializable @SerialName("paper")
    data class Paper(
        val pattern: PaperPattern,
        val spacingPt: Float,
        val lineColor: Int = 0xFFB0B0B0.toInt(),
        val paperColor: Int = 0xFFFFFFFF.toInt(),
        val marginLeftPt: Float? = null,
        val headerPt: Float? = null,
    ) : Background

    @Serializable @SerialName("pdf")
    data class Pdf(val asset: AssetId, val pageIndex: Int, val rotation: Int = 0) : Background

    @Serializable @SerialName("web")
    data class Web(val href: String, val layoutWidthCss: Float, val layoutGen: Int) : Background

    @Serializable @SerialName("none")
    data object None : Background
}

@Serializable
data class PageTemplate(val size: PageSize, val landscape: Boolean, val background: Background) {
    companion object {
        val DEFAULT = PageTemplate(PageSize.A4, false, Background.Paper(PaperPattern.LINED, mm(7.1f)))
    }
}

@Serializable
enum class PageSize(val widthPt: Float, val heightPt: Float?) {
    A4(595.28f, 841.89f), A5(419.53f, 595.28f), LETTER(612f, 792f), B5(498.9f, 708.66f),
    /** Boox Note X 3:4. */
    SCREEN(595.28f, 793.7f),
    /** Width is only the initial viewport; content may extend infinitely in all directions. */
    WHITEBOARD(2000f, null),
}
