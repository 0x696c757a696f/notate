package app.notate.render.paper

import android.graphics.Canvas
import android.graphics.Color
import app.notate.core.geometry.Rect
import app.notate.core.model.Background
import app.notate.core.model.PageInfo

/** Draws the background of a page. Page coordinates (pt); the canvas already carries the page→target matrix. Any thread. */
interface BackgroundRenderer {
    fun draw(canvas: Canvas, page: PageInfo, clipDoc: Rect)
}

/**
 * Dispatches by [Background] type: Paper → [PaperRenderer]; Pdf → the renderer registered with [registerPdf] (R3-1; white
 * until then); Web → nothing (the WebView below shows through, see [isTransparent]); None → white.
 */
class BackgroundRendererSet(private val paper: BackgroundRenderer = PaperRenderer()) {
    @Volatile private var pdf: BackgroundRenderer? = null

    fun registerPdf(renderer: BackgroundRenderer) { pdf = renderer }

    fun draw(canvas: Canvas, page: PageInfo, clipDoc: Rect) {
        when (page.background) {
            is Background.Paper -> paper.draw(canvas, page, clipDoc)
            is Background.Pdf -> pdf?.draw(canvas, page, clipDoc) ?: fillWhite(canvas, page, clipDoc)
            is Background.Web -> Unit
            Background.None -> fillWhite(canvas, page, clipDoc)
        }
    }

    private fun fillWhite(canvas: Canvas, page: PageInfo, clipDoc: Rect) {
        val area = PaperRenderer.pageArea(page, clipDoc)
        if (!area.isEmpty) canvas.drawRect(area.l, area.t, area.r, area.b, whitePaint())
    }

    private fun whitePaint() = android.graphics.Paint().apply { color = Color.WHITE }

    companion object {
        /** True when tiles of this page must start transparent so the content below stays visible. */
        fun isTransparent(page: PageInfo) = page.background is Background.Web
    }
}
