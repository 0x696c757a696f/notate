package app.notate.core.legacy

import app.notate.core.geometry.Rect
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.ImageItem
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageSize
import app.notate.core.model.PageTemplate
import app.notate.core.model.PaperPattern
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.core.model.Units.mm
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Scale and paper choice for a legacy canvas (03-data-model §9). */
data class LegacyPageSpec(val factor: Float, val widthPt: Float, val heightPt: Float?, val paper: PageSize)

/** Maps an old `.notate` note to the new model (03-data-model §9). Pure; any thread. */
object LegacyMapper {
    /** Old world units are Note X screen pixels (227 dpi); the new model uses pt. */
    const val INFINITE_FACTOR: Float = 72f / 227f
    /** Gap between pages in the old fixed-page layout (`CanvasConfig.PAGE_SPACING`). */
    const val OLD_PAGE_SPACING: Float = 100f
    /** Old pressure values are raw device pressure (max 4096 on Boox); values at most this are treated as already normalised. */
    private const val NORMALISED_MAX = 1.5f
    private const val DEVICE_MAX_PRESSURE = 4096f
    private val PAPERS = listOf(
        Triple(3508f, 4961f, 841.89f to 1190.55f),
        Triple(2480f, 3508f, 595.28f to 841.89f),
        Triple(1748f, 2480f, 419.53f to 595.28f),
    )

    /** Scale factor, target page size in pt and closest [PageSize] for a canvas (portrait or landscape fixed pages, or infinite). */
    fun pageSpec(type: LegacyCanvasType, pageWidth: Float, pageHeight: Float): LegacyPageSpec {
        if (type == LegacyCanvasType.INFINITE) return LegacyPageSpec(INFINITE_FACTOR, PageSize.WHITEBOARD.widthPt, null, PageSize.WHITEBOARD)
        val pw = if (pageWidth > 0f) pageWidth else 2480f
        val ph = if (pageHeight > 0f) pageHeight else 3508f
        val landscape = pw > ph
        val s = min(pw, ph); val l = max(pw, ph)
        val hit = PAPERS.firstOrNull { abs(it.first - s) <= 2f && abs(it.second - l) <= 2f }
        val (shortPt, longPt) = hit?.third ?: run {
            val f = PageSize.A4.widthPt / s
            PageSize.A4.widthPt to l * f
        }
        val w = if (landscape) longPt else shortPt
        val h = if (landscape) shortPt else longPt
        val paper = when {
            hit == null -> PageSize.SCREEN
            hit.first == 2480f -> PageSize.A4
            hit.first == 1748f -> PageSize.A5
            else -> PageSize.A4
        }
        return LegacyPageSpec(w / pw, w, h, paper)
    }

    /** `factor` only; see [pageSpec]. */
    fun factor(type: LegacyCanvasType, pageWidth: Float, pageHeight: Float): Float = pageSpec(type, pageWidth, pageHeight).factor

    /** Old pen type -> new pen type (BRUSH->FOUNTAIN, CHARCOAL->PENCIL, DASH->DASHED, DOT->DOTTED). */
    fun penOf(t: LegacyStrokeType): PenType = when (t) {
        LegacyStrokeType.FOUNTAIN, LegacyStrokeType.BRUSH -> PenType.FOUNTAIN
        LegacyStrokeType.BALLPOINT -> PenType.BALLPOINT
        LegacyStrokeType.FINELINER -> PenType.FINELINER
        LegacyStrokeType.HIGHLIGHTER -> PenType.HIGHLIGHTER
        LegacyStrokeType.CHARCOAL -> PenType.PENCIL
        LegacyStrokeType.DASH -> PenType.DASHED
        LegacyStrokeType.DOT -> PenType.DOTTED
    }

    /** Old background -> paper; spacing is multiplied by [factor]. Padding/centering are not carried over. */
    fun backgroundOf(bg: LegacyBackground, factor: Float): Background.Paper = when (bg) {
        is LegacyBackground.Blank -> Background.Paper(PaperPattern.BLANK, mm(7.1f))
        is LegacyBackground.Dots -> Background.Paper(PaperPattern.DOTS, bg.spacing * factor, lineColor = bg.color)
        is LegacyBackground.Lines -> Background.Paper(PaperPattern.LINED, bg.spacing * factor, lineColor = bg.color)
        is LegacyBackground.Grid -> Background.Paper(PaperPattern.GRID, bg.spacing * factor, lineColor = bg.color)
    }

    /** Page index (0-based) of an item whose vertical centre is [centerY] in old fixed-page coordinates. */
    fun pageIndexOf(centerY: Float, pageHeight: Float): Int =
        floor(centerY / (pageHeight + OLD_PAGE_SPACING)).toInt().coerceAtLeast(0)

    /** Maps [legacy] to a new notebook document. [title]/[createdAt]/[modifiedAt] come from the file (the container has no name). */
    fun toDocument(
        legacy: LegacyCanvas,
        ids: DocIdSource,
        clock: Clock,
        title: String = "Imported note",
        createdAt: Long = clock.now(),
        modifiedAt: Long = createdAt,
    ): MappedDocument {
        val d = legacy.data
        val warnings = ArrayList(legacy.warnings)
        val fixed = d.canvasType == LegacyCanvasType.FIXED_PAGES
        val spec = pageSpec(d.canvasType, d.pageWidth, d.pageHeight)
        val f = spec.factor
        val oldPageH = if (d.pageHeight > 0f) d.pageHeight else 3508f
        var nextItem = 0L
        val staged = ArrayList<Pair<Int, (ItemId) -> Item>>()
        val used = LinkedHashMap<String, LegacyAssetRef>()

        fun pageOf(centerY: Float) = if (fixed) pageIndexOf(centerY, oldPageH) else 0
        for (r in legacy.regions) {
            for (s in r.strokes) {
                val pts = s.pointsPacked; val ts = s.timestampsPacked
                if (pts == null || pts.size < 6) { warnings.add("Skipped empty stroke in region ${r.idX},${r.idY}"); continue }
                val n = ts?.size?.takeIf { it > 0 } ?: (pts.size / STRIDE)
                val stride = if (ts != null && ts.isNotEmpty()) pts.size / ts.size else STRIDE
                if (stride < 4) { warnings.add("Skipped stroke with bad stride"); continue }
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (i in 0 until n) { val y = pts[i * stride + 1]; if (y < minY) minY = y; if (y > maxY) maxY = y }
                val idx = pageOf((minY + maxY) / 2f)
                val yOff = if (fixed) idx * (oldPageH + OLD_PAGE_SPACING) else 0f
                staged.add(idx to { id -> buildStroke(id, s, pts, ts, n, stride, yOff, f) })
            }
            for (im in r.images) {
                val ref = legacy.assets[im.uri]
                if (ref == null) { warnings.add("Dropped image with missing file: ${im.uri}"); continue }
                used[im.uri] = ref
                val idx = pageOf(im.y + im.height / 2f)
                val yOff = if (fixed) idx * (oldPageH + OLD_PAGE_SPACING) else 0f
                staged.add(idx to { id ->
                    val fr = Rect(im.x * f, (im.y - yOff) * f, (im.x + im.width) * f, (im.y + im.height - yOff) * f)
                    ImageItem(id, im.order, ref.assetId, fr, im.rotation, im.opacity, im.locked, ItemBounds.frameBounds(fr, im.rotation))
                })
            }
            for (t in r.texts) {
                val idx = pageOf(t.y + t.height / 2f)
                val yOff = if (fixed) idx * (oldPageH + OLD_PAGE_SPACING) else 0f
                staged.add(idx to { id ->
                    val fr = Rect(t.x * f, (t.y - yOff) * f, (t.x + t.width) * f, (t.y + t.height - yOff) * f)
                    val align = when (t.alignment) { 1 -> TextAlign.RIGHT; 2 -> TextAlign.CENTER; else -> TextAlign.LEFT }
                    TextBox(id, t.order, fr, t.rotation, t.text, t.fontSize * f, t.color, false, align, ItemBounds.frameBounds(fr, t.rotation))
                })
            }
            for (l in r.links) {
                val idx = pageOf(l.y + l.height / 2f)
                val yOff = if (fixed) idx * (oldPageH + OLD_PAGE_SPACING) else 0f
                staged.add(idx to { id ->
                    val fr = Rect(l.x * f, (l.y - yOff) * f, (l.x + l.width) * f, (l.y + l.height - yOff) * f)
                    LinkItem(id, l.order, fr, l.label, targetOf(l), ItemBounds.frameBounds(fr, l.rotation))
                })
            }
        }
        val pageCount = if (fixed) (staged.maxOfOrNull { it.first } ?: 0) + 1 else 1
        val bg = backgroundOf(d.backgroundStyle, f)
        val pages = (0 until pageCount).map { i ->
            PageInfo(PageId(i + 1L), (i + 1).toDouble(), spec.widthPt, spec.heightPt, bg, 0L, modifiedAt)
        }
        val byPage = HashMap<Int, ArrayList<Item>>()
        // Stable order: by page, then original draw order, so ids follow the old stacking.
        for ((idx, build) in staged) byPage.getOrPut(idx) { ArrayList() }.add(build(ItemId(0)))
        val items = LinkedHashMap<PageId, List<Item>>()
        for (i in 0 until pageCount) {
            val list = (byPage[i] ?: emptyList<Item>()).sortedBy { it.order }
            items[PageId(i + 1L)] = list.map { reId(it, ItemId(++nextItem)) }
        }
        val defs = d.tagDefinitions.associateBy { it.id }
        val tagNames = d.tagIds.mapNotNull { defs[it]?.name }.distinct()
        val template = PageTemplate(spec.paper, spec.widthPt > (spec.heightPt ?: Float.MAX_VALUE), bg)
        val meta = DocumentMeta(ids.next(), title, DocKind.NOTEBOOK, createdAt, max(modifiedAt, createdAt), newPageTemplate = template)
        return MappedDocument(
            meta, pages, items, used.values.toList(), tagNames,
            tagNames.associateWith { n -> d.tagDefinitions.first { it.name == n }.color },
            warnings, d.uuid,
        )
    }

    private const val STRIDE = 6

    private fun buildStroke(
        id: ItemId, s: LegacyStrokeData, pts: FloatArray, ts: LongArray?, n: Int, stride: Int, yOff: Float, f: Float,
    ): Stroke {
        val xy = FloatArray(n * 2)
        for (i in 0 until n) { xy[2 * i] = pts[i * stride] * f; xy[2 * i + 1] = (pts[i * stride + 1] - yOff) * f }
        var pressure: FloatArray? = null
        if (stride >= 3) {
            val raw = FloatArray(n) { pts[it * stride + 2] }
            if (raw.any { !it.isNaN() && it > 0f }) {
                val scale = if (raw.max() > NORMALISED_MAX) DEVICE_MAX_PRESSURE else 1f
                pressure = FloatArray(n) { if (raw[it].isNaN() || raw[it] <= 0f) 1f else (raw[it] / scale).coerceIn(0f, 1f) }
            }
        }
        val tMs = if (ts != null && ts.size >= n && ts[n - 1] != ts[0]) IntArray(n) { (ts[it] - ts[0]).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() } else null
        val points = StrokePoints(xy, pressure, tMs)
        val style = InkStyle(penOf(s.style), s.color, s.width * f)
        return Stroke(id, s.strokeOrder, style, points, ItemBounds.strokeBounds(points, style))
    }

    private fun targetOf(l: LegacyLinkData): LinkTarget = when (l.type) {
        LegacyLinkType.INTERNAL_NOTE -> LinkTarget.Url(LegacyLinkFixer.PENDING_PREFIX + l.target)
        else -> LinkTarget.Url(l.target)
    }

    private fun reId(it: Item, id: ItemId): Item = when (it) {
        is Stroke -> it.copy(id = id)
        is ImageItem -> it.copy(id = id)
        is TextBox -> it.copy(id = id)
        is LinkItem -> it.copy(id = id)
        else -> it
    }
}
