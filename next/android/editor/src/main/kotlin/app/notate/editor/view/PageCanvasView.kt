package app.notate.editor.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    /** Draws the selection box above everything else. */
    var overlay: ((Canvas) -> Unit)? = null
    var sizeListener: ((Int, Int) -> Unit)? = null

    /** Label of the "Add page" button below the last page (continuous mode); null = no button. */
    var addButtonLabel: String? = null

    /** Single-page mode past the last page: a card with the same label replaces the pages. */
    var addCard: Boolean = false

    /** Shown under the label of the card. */
    var addCardHint: String? = null

    private val density = resources.displayMetrics.density
    private val border = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 18 * density; textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F5F5F.toInt(); textSize = 14 * density; textAlign = Paint.Align.CENTER }

    init {
        isFocusable = false
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        sizeListener?.invoke(w, h)
    }

    /** Screen rectangle of the continuous-mode "Add page" button (below the last page), or null when it is not shown. */
    fun addButtonRect(): RectF? {
        val l = layout ?: return null
        if (addButtonLabel == null || addCard || l.pageCount == 0 || l.hasUnboundedPage || l.mode != app.notate.core.model.PageLayoutMode.CONTINUOUS) return null
        val f = l.frameAt(l.pageCount - 1)
        val s = viewport.scale
        val top = (f.b - viewport.scrollY) * s + BUTTON_GAP_DP * density
        return RectF((f.l - viewport.scrollX) * s, top, (f.r - viewport.scrollX) * s, top + BUTTON_HEIGHT_DP * density)
    }

    /** Screen rectangle of the single-page "Add page" card, or null when it is not shown. */
    fun addCardRect(): RectF? {
        if (!addCard || width == 0) return null
        val w = width * 0.6f
        val h = height * 0.25f
        return RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val card = addCardRect()
        if (card != null) {
            canvas.drawColor(Color.WHITE)
            canvas.drawRect(card, border)
            canvas.drawText(addButtonLabel.orEmpty(), card.centerX(), card.centerY() + label.textSize / 3f, label)
            addCardHint?.let { canvas.drawText(it, card.centerX(), card.bottom + 24 * density, hint) }
            return
        }
        compositor?.draw(canvas, viewport, l)
        addButtonRect()?.let { r ->
            canvas.drawRect(r, fill)
            canvas.drawRect(r, border)
            canvas.drawText(addButtonLabel.orEmpty(), r.centerX(), r.centerY() + label.textSize / 3f, label)
        }
        live?.draw(canvas, viewport, l)
        overlay?.invoke(canvas)
    }

    companion object {
        const val BUTTON_HEIGHT_DP = 48f
        const val BUTTON_GAP_DP = 16f
    }
}
