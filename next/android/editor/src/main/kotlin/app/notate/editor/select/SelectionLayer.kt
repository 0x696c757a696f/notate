package app.notate.editor.select

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import app.notate.core.doc.layout.DocumentLayout
import app.notate.core.doc.layout.Viewport
import app.notate.core.doc.select.Handle
import app.notate.core.doc.select.Selection
import app.notate.core.doc.select.SelectionGeometry
import kotlin.math.roundToInt

/**
 * Draws the selection box above the tiles and the live ink: 2 dp dashed outline, eight 12 dp square handles and a round
 * rotation handle 24 dp above the top edge (04 §3.5). Also converts between the selection and screen pixels. Main thread only.
 */
class SelectionLayer(private val density: Float) {
    private val dash = Paint().apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density; isAntiAlias = true
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val line = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = density; isAntiAlias = true }
    private val fill = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }
    private val edge = Paint().apply { color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density; isAntiAlias = true }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt(); textSize = 16f * density; textAlign = Paint.Align.CENTER }
    private val labelBg = Paint().apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL }

    /** Screen rectangle of the selection box. */
    fun screenRect(sel: Selection, vp: Viewport, layout: DocumentLayout): RectF {
        val f = layout.frameOf(sel.page)
        val s = vp.scale
        val b = sel.bounds
        return RectF((f.l + b.l - vp.scrollX) * s, (f.t + b.t - vp.scrollY) * s, (f.l + b.r - vp.scrollX) * s, (f.t + b.b - vp.scrollY) * s)
    }

    /** Screen rectangle of the box plus its handles (what must be repainted when the selection changes). */
    fun dirtyRect(sel: Selection, vp: Viewport, layout: DocumentLayout): android.graphics.Rect {
        val r = screenRect(sel, vp, layout)
        val pad = (ROTATE_DP + HANDLE_DP + 28f) * density
        return android.graphics.Rect(
            (r.left - pad).roundToInt(), (r.top - pad).roundToInt(), (r.right + pad).roundToInt(), (r.bottom + pad).roundToInt(),
        )
    }

    fun draw(canvas: Canvas, sel: Selection, vp: Viewport, layout: DocumentLayout) {
        if (layout.frameOf(sel.page).isEmpty) return
        val r = screenRect(sel, vp, layout)
        canvas.drawRect(r, dash)
        val half = HANDLE_DP * density / 2f
        val rot = ROTATE_DP * density
        // Stem from the top edge to the rotation handle.
        canvas.drawLine(r.centerX(), r.top, r.centerX(), r.top - rot, line)
        for (h in Handle.entries) {
            if (h == Handle.MOVE) continue
            val p = SelectionGeometry.handlePosition(app.notate.core.geometry.Rect(r.left, r.top, r.right, r.bottom), h, rot)
            if (h == Handle.ROTATE) {
                canvas.drawCircle(p.x, p.y, half, fill)
                canvas.drawCircle(p.x, p.y, half, edge)
            } else {
                canvas.drawRect(p.x - half, p.y - half, p.x + half, p.y + half, fill)
                canvas.drawRect(p.x - half, p.y - half, p.x + half, p.y + half, edge)
            }
        }
        if (sel.rotationDeg != 0f) {
            val text = "${sel.rotationDeg.roundToInt()}°"
            val cx = r.centerX()
            val cy = r.top - rot - half - 10f * density
            val w = label.measureText(text) + 12f * density
            canvas.drawRect(cx - w / 2f, cy - label.textSize, cx + w / 2f, cy + 6f * density, labelBg)
            canvas.drawText(text, cx, cy, label)
        }
    }

    /**
     * The handle (or MOVE for the interior) under a screen point, with a touch target of the visual size plus 8 dp; null
     * outside. [sx]/[sy] are layer-local screen px.
     */
    fun hit(sel: Selection, vp: Viewport, layout: DocumentLayout, sx: Float, sy: Float): Handle? {
        val r = screenRect(sel, vp, layout)
        val box = app.notate.core.geometry.Rect(r.left, r.top, r.right, r.bottom)
        return SelectionGeometry.hit(box, sx, sy, hitHalfPx(), ROTATE_DP * density)
    }

    /** Half-size of a handle's touch target in px. */
    fun hitHalfPx(): Float = (HANDLE_DP / 2f + HIT_SLOP_DP) * density

    companion object {
        const val HANDLE_DP = 12f
        const val ROTATE_DP = 24f
        const val HIT_SLOP_DP = 8f
    }
}
