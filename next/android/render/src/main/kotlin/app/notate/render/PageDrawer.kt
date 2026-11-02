package app.notate.render

import android.graphics.Canvas
import app.notate.core.doc.content.PageContent
import app.notate.core.geometry.Rect
import app.notate.core.model.PageInfo
import app.notate.render.ink.InkRenderer
import app.notate.render.ink.PaintPool
import app.notate.render.paper.BackgroundRendererSet

/**
 * Draws one region of a page (background, then items by (layer, order)) onto a canvas that already carries the
 * page→target matrix. Used by tiles and thumbnails. One instance per drawing thread (owns a [PaintPool]).
 */
class PageDrawer(private val backgrounds: BackgroundRendererSet, private val ink: InkRenderer) {
    private val paints = PaintPool()

    /** [checkpoint] is called every few items so a long page can be cancelled. [drawBackground] false when the caller already drew it (cached PDF tile). */
    fun draw(canvas: Canvas, page: PageInfo, content: PageContent, region: Rect, drawBackground: Boolean = true, checkpoint: () -> Unit = {}) {
        canvas.save()
        page.height?.let { canvas.clipRect(0f, 0f, page.width, it) }
        if (drawBackground) backgrounds.draw(canvas, page, region)
        var n = 0
        for (item in content.query(region)) {
            if (++n % CHECK_EVERY == 0) checkpoint()
            ink.draw(canvas, item, paints)
        }
        canvas.restore()
    }

    private companion object { const val CHECK_EVERY = 64 }
}
