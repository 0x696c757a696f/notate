package app.notate.core.legacy

import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PageLayoutMode
import app.notate.core.model.PenMode
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.Units
import app.notate.core.model.ViewState
import app.notate.core.model.WebDocSettings

/** Maps an old web document to a new WEB document: one page per spine href (plus hrefs that only have ink); CSS px x 0.75 -> pt. */
object LegacyWebMapper {
    /** Layout width used when the old document never recorded one. */
    const val DEFAULT_LAYOUT_WIDTH_CSS = 720f

    fun toDocument(web: LegacyWebDoc, ids: DocIdSource, clock: Clock): MappedDocument {
        val k = Units.PT_PER_CSS_PX
        val hrefs = (web.meta.pages.map { it.href } + web.strokes.keys.sorted()).distinct()
        val widthCss = web.layout?.widthCss
        var nextItem = 0L
        val pages = ArrayList<PageInfo>()
        val items = LinkedHashMap<PageId, List<Item>>()
        val pageOf = HashMap<String, PageId>()
        val now = clock.now()
        hrefs.forEachIndexed { i, href ->
            val id = PageId(i + 1L)
            pageOf[href] = id
            val w = widthCss ?: web.layoutWidth[href] ?: DEFAULT_LAYOUT_WIDTH_CSS
            pages.add(PageInfo(id, (i + 1).toDouble(), w * k, null, Background.Web(href, w, web.layout?.gen ?: 1), 0L, now))
            items[id] = web.strokes[href].orEmpty().mapIndexed { n, s -> toStroke(ItemId(++nextItem), n + 1L, s, k) }
        }
        val view = web.lastPage?.let { pageOf[it] }?.let {
            ViewState(it, 1f, 0f, (web.scrollY[web.lastPage] ?: 0f) * k, PageLayoutMode.CONTINUOUS)
        }
        val settings = WebDocSettings(
            javascript = web.meta.policy.javaScriptEnabled, network = web.meta.policy.networkAllowed,
            reflow = false, penMode = if (web.penMode == "INTERACT") PenMode.INTERACT else PenMode.WRITE,
            reduceMotion = web.meta.policy.reduceMotion, textZoom = web.layout?.textZoom ?: 100,
        )
        val created = if (web.meta.importedAt > 0) web.meta.importedAt else now
        val meta = DocumentMeta(ids.next(), web.meta.title, DocKind.WEB, created, maxOf(created, now), view = view, web = settings)
        return MappedDocument(meta, pages, items, emptyList(), emptyList(), emptyMap(), web.warnings, web.meta.id, web.contentDir)
    }

    private fun toStroke(id: ItemId, order: Long, s: LegacyWebStroke, k: Float): Stroke {
        val xy = FloatArray(s.points.size / 2 * 2) { s.points[it] * k }
        val n = xy.size / 2
        val pressure = s.pressures?.takeIf { it.size == n }?.let { p -> FloatArray(n) { p[it].coerceIn(0f, 1f) } }
        val pen = if (s.tool == LegacyWebInkTool.HIGHLIGHTER) PenType.HIGHLIGHTER else LegacyMapper.penOf(s.style)
        val style = InkStyle(pen, s.color, s.width * k)
        val pts = StrokePoints(xy, pressure, null)
        return Stroke(id, order, style, pts, ItemBounds.strokeBounds(pts, style))
    }
}
