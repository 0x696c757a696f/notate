package app.notate.core.testing

import app.notate.core.geometry.Rect
import app.notate.core.model.Background
import app.notate.core.model.DocId
import app.notate.core.model.DocKind
import app.notate.core.model.DocumentMeta
import app.notate.core.model.InkStyle
import app.notate.core.model.ItemBounds
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.core.model.PaperPattern
import app.notate.core.model.PenType
import app.notate.core.model.Stroke
import app.notate.core.model.StrokePoints
import app.notate.core.model.PageTemplate

private val DEFAULT_POINTS = floatArrayOf(10f, 10f, 50f, 50f, 90f, 20f)

/** Test stroke; [order] defaults to the id; bounds are computed from the points. */
fun stroke(
    id: Long,
    order: Long = id,
    points: FloatArray = DEFAULT_POINTS,
    pen: PenType = PenType.BALLPOINT,
    color: Int = 0xFF000000.toInt(),
    widthPt: Float = 1.134f,
): Stroke {
    val style = InkStyle(pen, color, widthPt)
    val pts = StrokePoints(points, null, null)
    return Stroke(ItemId(id), order, style, pts, ItemBounds.strokeBounds(pts, style))
}

/** Test page with A4-ish size and lined paper. */
fun page(id: Long, ord: Double = id.toDouble(), height: Float? = 841.89f): PageInfo =
    PageInfo(PageId(id), ord, 595.28f, height, Background.Paper(PaperPattern.LINED, 20f), inkRev = 0, modifiedAt = 0)

fun meta(id: String = "01TESTDOC000000000000000A", kind: DocKind = DocKind.NOTEBOOK, title: String = "Test"): DocumentMeta =
    DocumentMeta(DocId(id), title, kind, createdAt = 0, modifiedAt = 0, newPageTemplate = PageTemplate.DEFAULT)

/** Page-local rect helper. */
fun rect(l: Float, t: Float, r: Float, b: Float) = Rect(l, t, r, b)
