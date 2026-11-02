package app.notate.core.doc.tools

import app.notate.core.geometry.Rect
import app.notate.core.model.AssetId
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemBounds
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import kotlin.math.max
import kotlin.math.min

/** Places an imported image on a page. Main thread only. */
object ImageInsert {
    /** At most this share of the page width, so a photo does not swallow the page. */
    const val MAX_WIDTH_SHARE = 0.6f

    /**
     * Builds the insert edit for [asset] (pixel size [pixelW]×[pixelH]): scaled to at most 60 % of the page width (never up
     * beyond 1 px = 1 pt), aspect kept, centred on [viewportDoc] (the visible part of the page) and moved so it lies in the page.
     * Returns null for an unknown page or a zero-sized image. The edit's single added item is the new [ImageItem].
     */
    fun place(editor: app.notate.core.doc.editor.DocumentEditor, page: PageId, asset: AssetId, pixelW: Int, pixelH: Int, viewportDoc: Rect): Edit.Items? {
        val info = editor.pages.value.firstOrNull { it.id == page } ?: return null
        if (pixelW <= 0 || pixelH <= 0) return null
        var w = min(pixelW.toFloat(), info.width * MAX_WIDTH_SHARE)
        var h = w * pixelH / pixelW
        info.height?.let { ph ->                       // a tall image on a fixed page must also fit the height
            val maxH = ph * MAX_HEIGHT_SHARE
            if (h > maxH) { h = maxH; w = h * pixelW / pixelH }
        }
        val vp = viewportDoc.let { v -> Rect(max(v.l, 0f), max(v.t, 0f), min(v.r, info.width), info.height?.let { min(v.b, it) } ?: v.b) }
        val c = if (vp.isEmpty) Rect(0f, 0f, info.width, info.height ?: 0f).center else vp.center
        var l = c.x - w / 2f
        var t = c.y - h / 2f
        l = l.coerceIn(0f, max(0f, info.width - w))
        t = info.height?.let { t.coerceIn(0f, max(0f, it - h)) } ?: max(0f, t)
        val frame = Rect(l, t, l + w, t + h)
        val item = ImageItem(
            id = editor.newItemId(), order = editor.nextOrder(page), asset = asset, frame = frame, rotationDeg = 0f, opacity = 1f,
            locked = false, bounds = ItemBounds.frameBounds(frame, 0f),
        )
        return Edit.Items(page, emptyList(), listOf(item))
    }

    private const val MAX_HEIGHT_SHARE = 0.9f
}
