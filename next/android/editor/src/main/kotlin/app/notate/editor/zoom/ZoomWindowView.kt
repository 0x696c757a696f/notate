package app.notate.editor.zoom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.zoom.ZoomWindowState
import app.notate.core.geometry.Affine
import app.notate.editor.R
import app.notate.editor.view.LiveInkLayer
import app.notate.render.view.TileCompositor

/**
 * The zoom writing window drawn over the lower third of a page pane (04 §3.10): the magnified [ZoomWindowState.box] on the
 * left, a column of icon buttons on the right, and the dashed box on the page above. It draws and answers hit tests; touches
 * arrive through the pane's gesture callbacks, so it is not a `View` of its own. Main thread only.
 */
class ZoomWindowView(
    context: Context,
    private val compositor: TileCompositor,
    private val live: LiveInkLayer,
) {
    enum class Control(val icon: Int, val label: Int) {
        BACK(R.drawable.ic_back, R.string.zoom_back),
        FORWARD(R.drawable.ic_arrow_forward, R.string.zoom_forward),
        NEW_LINE(R.drawable.ic_keyboard_return, R.string.zoom_new_line),
        SMALLER(R.drawable.ic_remove, R.string.zoom_smaller),
        LARGER(R.drawable.ic_add, R.string.zoom_larger),
        CLOSE(R.drawable.ic_close, R.string.zoom_close),
    }

    private val density = context.resources.displayMetrics.density
    private val icons: Map<Control, Drawable> = Control.entries.associateWith { c ->
        context.getDrawable(c.icon)!!.mutate()
    }
    private val line = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 2 * density }
    private val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val dashed = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        pathEffect = DashPathEffect(floatArrayOf(8 * density, 6 * density), 0f)
    }

    /** The window's state while it is open; null = closed. */
    var state: ZoomWindowState? = null

    /** Layout and viewport of the pane (the dashed box is drawn with them). */
    var layout: DocumentLayout? = null
    var viewport: Viewport = Viewport(0f, 0f, 1f, 1f, 0, 0)

    /** The whole docked area, the writing area and the button column, in view-local px. */
    val region = RectF()
    val writing = RectF()
    val buttons = RectF()
    private val buttonRects = ArrayList<Pair<Control, RectF>>()

    val isOpen: Boolean get() = state != null

    /** Docks the window to the bottom third of a [width] × [height] view. */
    fun dock(width: Int, height: Int) {
        val top = height - height / REGION_DIVISOR
        region.set(0f, top.toFloat(), width.toFloat(), height.toFloat())
        val colW = BUTTON_COLUMN_DP * density
        buttons.set(region.right - colW, region.top, region.right, region.bottom)
        writing.set(region.left, region.top, buttons.left, region.bottom)
        buttonRects.clear()
        // The page indicator sits in the bottom-right corner: the column uses the space above it.
        val usable = (region.height() - RESERVED_BOTTOM_DP * density).coerceAtLeast(Control.entries.size * MIN_BUTTON_DP * density)
        val size = minOf(BUTTON_DP * density, usable / Control.entries.size)
        var y = region.top + density * 2
        for (c in Control.entries) {
            buttonRects.add(c to RectF(buttons.left, y, buttons.right, y + size))
            y += size
        }
    }

    fun contains(x: Float, y: Float): Boolean = isOpen && region.contains(x, y)

    /** The button under ([x], [y]), or null. */
    fun controlAt(x: Float, y: Float): Control? {
        if (!isOpen) return null
        return buttonRects.firstOrNull { it.second.contains(x, y) }?.first
    }

    fun rectOf(c: Control): RectF? = buttonRects.firstOrNull { it.first == c }?.second

    /** Pixels per pt inside the writing area (the box is mapped onto it exactly). */
    val scale: Float get() = state?.let { writing.width() / it.box.width } ?: 1f

    /**
     * The writing area's own viewport (origin at its top-left), used to render tiles for it; null while closed or before
     * the first layout. Pass it to the tile scheduler and, translated by [writing], to the compositor.
     */
    fun zoomViewport(): Viewport? {
        val s = state ?: return null
        val l = layout ?: return null
        if (writing.isEmpty || s.box.isEmpty) return null
        val f = l.frameOf(s.page)
        if (f.isEmpty) return null
        val sc = writing.width() / s.box.width
        return Viewport(f.l + s.box.l, f.t + s.box.t, sc / viewport.pxPerPt, viewport.pxPerPt, writing.width().toInt(), writing.height().toInt())
    }

    /** View-local px → page coordinates for pen points inside [writing]. */
    fun pointMap(): Affine? {
        val s = state ?: return null
        if (writing.isEmpty) return null
        return s.writingToPage(writing.width(), writing.height()) * Affine.translate(-writing.left, -writing.top)
    }

    /** The box on the page as view-local px in the pane's own viewport, or null. */
    fun boxOnScreen(vp: Viewport = viewport, l: DocumentLayout? = layout): RectF? {
        val s = state ?: return null
        val layout = l ?: return null
        val f = layout.frameOf(s.page)
        if (f.isEmpty) return null
        val r = vp.docToScreen().mapRect(app.notate.core.geometry.Rect(f.l + s.box.l, f.t + s.box.t, f.l + s.box.r, f.t + s.box.b))
        return RectF(r.l, r.t, r.r, r.b)
    }

    /** Projection for the live ink layer: the same strokes, drawn into the writing area. */
    fun projection(): LiveInkLayer.Projection? = zoomViewport()?.let { LiveInkLayer.Projection(it, writing.left, writing.top) }

    fun draw(canvas: Canvas) {
        val s = state ?: return
        val l = layout ?: return
        boxOnScreen()?.let { canvas.drawRect(it, dashed) }
        canvas.drawRect(region, fill)
        canvas.drawLine(region.left, region.top, region.right, region.top, line)
        canvas.drawLine(buttons.left, region.top, buttons.left, region.bottom, line)
        val zv = zoomViewport()
        if (zv != null) {
            canvas.save()
            canvas.clipRect(writing.left, writing.top + line.strokeWidth / 2, writing.right, writing.bottom)
            canvas.translate(writing.left, writing.top)
            compositor.draw(canvas, zv, l)
            live.draw(canvas, zv, l)
            canvas.restore()
        }
        val pad = (density * ICON_PAD_DP).toInt()
        for ((c, r) in buttonRects) {
            val d = icons.getValue(c)
            d.setBounds((r.left + pad).toInt(), (r.top + pad).toInt(), (r.right - pad).toInt(), (r.bottom - pad).toInt())
            d.draw(canvas)
        }
        // The magnification, small, under the buttons' column top: helps to see what − / + did.
        textPaint.textSize = 14 * density
        val last = buttonRects.lastOrNull()?.second
        if (last != null) canvas.drawText("${"%.1f".format(s.magnification)}×", buttons.centerX(), last.bottom + 18 * density, textPaint)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }

    private companion object {
        const val REGION_DIVISOR = 3
        const val BUTTON_COLUMN_DP = 56f
        const val BUTTON_DP = 48f
        const val MIN_BUTTON_DP = 36f
        const val ICON_PAD_DP = 10f
        const val RESERVED_BOTTOM_DP = 80f
    }
}
