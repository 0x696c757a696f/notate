package app.notate.editor.view

import android.content.Context
import android.graphics.Canvas
import android.view.View
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.render.view.TileCompositor

/** The page surface: tiles first, then the live ink layer. Draws what [NotebookPane] hands it; owns no state. */
class PageCanvasView(context: Context) : View(context) {
    var viewport: Viewport = Viewport(0f, 0f, 1f, 1f, 0, 0)
    var layout: DocumentLayout? = null
    var compositor: TileCompositor? = null
    var live: LiveInkLayer? = null
    var sizeListener: ((Int, Int) -> Unit)? = null

    init {
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        compositor?.draw(canvas, viewport, l)
        live?.draw(canvas, viewport, l)
    }
}
