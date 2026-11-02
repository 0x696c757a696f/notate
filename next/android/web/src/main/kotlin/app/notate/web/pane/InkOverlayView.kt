package app.notate.web.pane

import android.content.Context
import android.graphics.Canvas
import android.view.View
import app.notate.core.doc.content.PageContent
import app.notate.core.doc.layout.Viewport
import app.notate.core.model.PageId
import app.notate.editor.view.LiveInkLayer
import app.notate.render.ink.GeometryCache
import app.notate.render.ink.InkRenderer
import app.notate.render.ink.PaintPool

/**
 * Handwriting over the WebView. A chapter has few objects, so everything visible is drawn straight from the page content on
 * every frame (no tiles), followed by the live stroke. The view never takes touches: the pane routes the pen and the
 * WebView keeps the fingers. Draws what [WebPane] hands it; owns no state.
 */
class InkOverlayView(context: Context) : View(context) {
    /** Document plane = the chapter's page in pt, with its origin at the top-left of the content. */
    var viewport: Viewport = Viewport(0f, 0f, 1f, 1f, 0, 0)
    var page: PageId? = null
    var content: (PageId) -> PageContent? = { null }
    var live: LiveInkLayer? = null
    var layout: app.notate.core.doc.layout.DocumentLayout? = null
    var sizeListener: ((Int, Int) -> Unit)? = null

    private val renderer = InkRenderer(GeometryCache())
    private val paints = PaintPool()

    init {
        isFocusable = false
        isClickable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val vp = viewport
        val pg = page
        val c = pg?.let(content)
        if (c != null && vp.widthPx > 0) {
            val s = vp.scale
            canvas.save()
            canvas.scale(s, s)
            canvas.translate(-vp.scrollX, -vp.scrollY)
            val hidden = live?.hiddenIds.orEmpty()
            for (item in c.query(vp.visibleDocRect())) if (item.id !in hidden) renderer.draw(canvas, item, paints)
            canvas.restore()
        }
        val l = layout
        if (l != null) live?.draw(canvas, vp, l)
    }
}
