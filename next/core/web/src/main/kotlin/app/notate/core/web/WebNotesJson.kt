package app.notate.core.web

import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.LinkItem
import app.notate.core.model.LinkTarget
import app.notate.core.model.Shape
import app.notate.core.model.Stroke
import app.notate.core.model.TextBox

/** One chapter that has notes. */
class ChapterNotes(val href: String, val layoutWidthCss: Float, val items: List<Item>)

/** One entry of the table of contents in the JSON export. */
class NotesToc(val title: String, val href: String?, val depth: Int)

/**
 * "Notes data (JSON)" export of a web document (R4-3): `{"title","toc","pages":[{"href","layoutWidthCss","items":[…]}]}`.
 * Geometry is given twice, in points (`…Pt`) and in CSS px (`x_css = x_pt / 0.75`). Strokes carry their point list and style.
 * Written by hand: the core module has no JSON library and the shape is small. Pure.
 */
object WebNotesJson {
    fun build(title: String, toc: List<NotesToc>, pages: List<ChapterNotes>): String {
        val sb = StringBuilder(1024)
        sb.append("{\"format\":\"notate-web-notes\",\"version\":1,\"title\":").str(title).append(",\"toc\":[")
        toc.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"title\":").str(e.title).append(",\"href\":").strOrNull(e.href).append(",\"depth\":").append(e.depth).append('}')
        }
        sb.append("],\"pages\":[")
        pages.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"href\":").str(p.href).append(",\"layoutWidthCss\":").num(p.layoutWidthCss).append(",\"items\":[")
            p.items.forEachIndexed { j, item ->
                if (j > 0) sb.append(',')
                item(sb, item)
            }
            sb.append("]}")
        }
        return sb.append("]}").toString()
    }

    private fun item(sb: StringBuilder, it: Item) {
        sb.append("{\"id\":").append(it.id.value).append(",\"order\":").append(it.order)
        when (it) {
            is Stroke -> {
                sb.append(",\"type\":\"stroke\",\"pen\":").str(it.style.pen.name).append(",\"color\":").append(it.style.color)
                    .append(",\"widthPt\":").num(it.style.widthPt).append(",\"widthCss\":").num(WebViewTransform.ptToCss(it.style.widthPt))
                rect(sb, it)
                val xy = it.points.xy
                sb.append(",\"pointsPt\":[")
                for (k in 0 until it.points.size) {
                    if (k > 0) sb.append(',')
                    sb.append('[').num(xy[2 * k]).append(',').num(xy[2 * k + 1])
                    it.points.pressure?.let { p -> sb.append(',').num(p[k]) }
                    sb.append(']')
                }
                sb.append("],\"pointsCss\":[")
                for (k in 0 until it.points.size) {
                    if (k > 0) sb.append(',')
                    sb.append('[').num(WebViewTransform.ptToCss(xy[2 * k])).append(',').num(WebViewTransform.ptToCss(xy[2 * k + 1])).append(']')
                }
                sb.append(']')
            }
            is Shape -> {
                sb.append(",\"type\":\"shape\",\"kind\":").str(it.kind.name).append(",\"pen\":").str(it.style.pen.name)
                    .append(",\"color\":").append(it.style.color).append(",\"widthPt\":").num(it.style.widthPt)
                it.fill?.let { f -> sb.append(",\"fill\":").append(f) }
                rect(sb, it)
                sb.append(",\"paramsPt\":[")
                it.params.forEachIndexed { k, v -> if (k > 0) sb.append(','); sb.num(v) }
                sb.append(']')
            }
            is TextBox -> {
                sb.append(",\"type\":\"text\",\"text\":").str(it.text).append(",\"fontSizePt\":").num(it.fontSizePt)
                    .append(",\"color\":").append(it.color).append(",\"bold\":").append(it.bold).append(",\"rotationDeg\":").num(it.rotationDeg)
                rect(sb, it)
            }
            is ImageItem -> {
                sb.append(",\"type\":\"image\",\"asset\":").append(it.asset.value).append(",\"rotationDeg\":").num(it.rotationDeg)
                    .append(",\"opacity\":").num(it.opacity)
                rect(sb, it)
            }
            is LinkItem -> {
                sb.append(",\"type\":\"link\",\"label\":").str(it.label)
                when (val t = it.target) {
                    is LinkTarget.Url -> sb.append(",\"url\":").str(t.url)
                    is LinkTarget.Page -> sb.append(",\"doc\":").str(t.doc.value).append(",\"page\":").append(t.page.value)
                }
                rect(sb, it)
            }
        }
        sb.append('}')
    }

    private fun rect(sb: StringBuilder, it: Item) {
        val b = it.bounds
        sb.append(",\"boundsPt\":{\"l\":").num(b.l).append(",\"t\":").num(b.t).append(",\"r\":").num(b.r).append(",\"b\":").num(b.b)
        sb.append("},\"boundsCss\":{\"l\":").num(WebViewTransform.ptToCss(b.l)).append(",\"t\":").num(WebViewTransform.ptToCss(b.t))
            .append(",\"r\":").num(WebViewTransform.ptToCss(b.r)).append(",\"b\":").num(WebViewTransform.ptToCss(b.b)).append('}')
    }

    private fun StringBuilder.num(v: Float): StringBuilder {
        if (!v.isFinite()) return append('0')
        val r = Math.round(v * 100f) / 100f
        return append(if (r == Math.rint(r.toDouble()).toFloat() && Math.abs(r) < 1e9f) r.toLong().toString() else r.toString())
    }

    private fun StringBuilder.strOrNull(s: String?): StringBuilder = if (s == null) append("null") else str(s)

    private fun StringBuilder.str(s: String): StringBuilder {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        return append('"')
    }
}
