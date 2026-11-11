package app.notate.core.doc.select

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.tools.Preview
import app.notate.core.doc.tools.PreviewSink
import app.notate.core.geometry.Affine
import app.notate.core.geometry.Rect
import app.notate.core.geometry.Vec2
import app.notate.core.model.ImageItem
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.TextBox
import app.notate.core.model.edit.Edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/** Maps between page coordinates and the document plane (implemented over `DocumentLayout` by the pane). */
interface PageGeometry {
    /** Top-left of [page] on the document plane. */
    fun origin(page: PageId): Vec2
    /** Page under a document-plane point, or null in a gap. */
    fun pageAt(docX: Float, docY: Float): PageId?
}

/**
 * Owns the selection and its operations. Every finished operation is one undoable [Edit]. **Main thread only.**
 * A selection lives on a single page; transforms are driven in document-plane coordinates so they can cross pages.
 */
class SelectionController(
    private val editor: DocumentEditor,
    private val geometry: PageGeometry,
    private val preview: PreviewSink,
) {
    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> get() = _selection

    private class Active(
        val handle: Handle, val items: List<Item>, val bounds: Rect, var start: Vec2?, var target: PageId,
    ) {
        var affine: Affine = Affine.IDENTITY
        var angle = 0f
    }
    private var active: Active? = null

    /**
     * True for the selection made automatically after a shape was drawn ([selectQuick]): the pen grabs only its handles and its
     * outline, so writing inside the shape still works. Any other way of selecting, or clearing, turns it off.
     */
    var quick = false
        private set

    val isTransforming: Boolean get() = active != null

    // ---- selecting ----

    /** Selects items on [page] with at least 50 % of their points inside [polygon] (x,y pairs); locked images are skipped. */
    fun selectByLasso(page: PageId, polygon: FloatArray) {
        cancelTransform()
        quick = false
        val content = editor.content(page) ?: return clear()
        if (polygon.size < 6) return clear()
        val hits = content.query(LassoHit.polygonBounds(polygon)).filter {
            LassoHit.selectable(it) && LassoHit.fractionInside(it, polygon) >= 0.5f
        }
        setSelection(page, hits)
    }

    fun selectByRect(page: PageId, r: Rect) = selectByLasso(page, LassoHit.rectPolygon(r))

    /** Selects exactly [ids] on [page] (used after paste and duplicate). */
    fun selectIds(page: PageId, ids: Collection<ItemId>) {
        cancelTransform()
        quick = false
        val content = editor.content(page) ?: return clear()
        setSelection(page, ids.mapNotNull { content[it] })
    }

    /** Selects the just-drawn shape [id] so it can be moved, scaled and rotated right away. */
    fun selectQuick(page: PageId, id: ItemId) {
        selectIds(page, listOf(id))
        quick = _selection.value != null
    }

    /** True when ([x], [y]) on the selection's page is within [tol] pt of the outline of a selected shape. */
    fun nearShapeOutline(x: Float, y: Float, tol: Float): Boolean {
        for (item in items()) {
            if (item !is app.notate.core.model.Shape) continue
            val reach = tol + item.style.widthPt / 2f
            for (poly in app.notate.core.ink.shape.ShapeOutline.of(item.kind, item.params, item.style.widthPt)) {
                val xy = poly.points
                for (i in 0 until xy.size / 2 - 1) {
                    if (app.notate.core.geometry.Geo.distPointSegment(x, y, xy[2 * i], xy[2 * i + 1], xy[2 * i + 2], xy[2 * i + 3]) <= reach) return true
                }
            }
        }
        return false
    }

    fun clear() {
        cancelTransform()
        quick = false
        _selection.value = null
    }

    /** Re-reads the selected items (call after undo/redo): drops vanished ids, refreshes bounds, clears when empty. */
    fun refresh() {
        if (active != null) return
        val s = _selection.value ?: return
        val content = editor.content(s.page) ?: return clear()
        setSelection(s.page, s.ids.mapNotNull { content[it] })
    }

    private fun setSelection(page: PageId, items: List<Item>) {
        _selection.value = if (items.isEmpty()) null else Selection(page, items.map { it.id }.toSet(), unionBounds(items))
    }

    private fun current(): Pair<Selection, List<Item>>? {
        val s = _selection.value ?: return null
        val content = editor.content(s.page) ?: return null
        val items = s.ids.mapNotNull { content[it] }
        if (items.isEmpty()) return null
        return s to items.sortedWith(compareBy({ it.layer.code }, { it.order }))
    }

    /** The selected items (stored versions, draw order). */
    fun items(): List<Item> = current()?.second ?: emptyList()

    // ---- transform ----

    /**
     * Starts a drag of [handle]. With [docX]/[docY] the pointer start is known; without them the first [updateTransform]
     * call is taken as the start.
     */
    fun beginTransform(handle: Handle, docX: Float = Float.NaN, docY: Float = Float.NaN) {
        cancelTransform()
        val (s, items) = current() ?: return
        val o = geometry.origin(s.page)
        val start = if (docX.isNaN() || docY.isNaN()) null else Vec2(docX - o.x, docY - o.y)
        active = Active(handle, items, unionBounds(items), start, s.page)
    }

    /** Live update: only the ghost preview and the selection box change; the document is untouched. */
    fun updateTransform(docX: Float, docY: Float, targetPage: PageId?) {
        val a = active ?: return
        val s = _selection.value ?: return
        val o = geometry.origin(s.page)
        val p = Vec2(docX - o.x, docY - o.y)
        val start = a.start ?: run { a.start = p; return }
        a.target = if (a.handle == Handle.MOVE && targetPage != null) targetPage else s.page
        a.angle = 0f
        a.affine = when (a.handle) {
            Handle.MOVE -> Affine.translate(p.x - start.x, p.y - start.y)
            Handle.ROTATE -> rotation(a, p, start)
            else -> scaling(a, p, start)
        }
        val ghost = a.items.map { it.transformed(a.affine) }
        preview.show(Preview.Hidden(a.items.map { it.id }.toSet()))
        preview.show(Preview.Ghost(ghost))
        _selection.value = Selection(s.page, s.ids, unionBounds(ghost), a.angle)
    }

    /** Commits the drag as one [Edit]; a cross-page move is one [Edit.Batch]. */
    fun endTransform() {
        val a = active ?: return
        active = null
        preview.clear()
        val s = _selection.value ?: return
        if (a.affine == Affine.IDENTITY) { setSelection(s.page, a.items); return }
        val moved = a.items.map { it.transformed(a.affine) }
        val target = a.target
        if (target != s.page && editor.content(target) != null) {
            val shift = geometry.origin(s.page) - geometry.origin(target)
            val placed = a.items.map { it.transformed(Affine.translate(shift.x, shift.y) * a.affine) }
                .map { ItemOps.withOrder(it, editor.nextOrder(target)) }
            editor.apply(Edit.Batch(listOf(Edit.Items(s.page, a.items, emptyList()), Edit.Items(target, emptyList(), placed))))
            setSelection(target, placed)
        } else {
            editor.apply(Edit.Items(s.page, a.items, moved))
            setSelection(s.page, moved)
        }
    }

    /** Page under the centre of the (possibly ghosted) selection box, or null in a gap; pass it to [updateTransform]. */
    fun pageAtSelectionCenter(): PageId? {
        val s = _selection.value ?: return null
        val c = s.bounds.center
        val o = geometry.origin(s.page)
        return geometry.pageAt(c.x + o.x, c.y + o.y)
    }

    /** Document-plane to page conversion helper for sessions: top-left of [page]. */
    fun originOf(page: PageId): Vec2 = geometry.origin(page)

    private fun cancelTransform() {
        if (active == null) return
        active = null
        preview.clear()
        val s = _selection.value ?: return
        val content = editor.content(s.page)
        if (content == null) { _selection.value = null; return }
        setSelection(s.page, s.ids.mapNotNull { content[it] })
    }

    private fun rotation(a: Active, p: Vec2, start: Vec2): Affine {
        val c = a.bounds.center
        var deg = Math.toDegrees((atan2(p.y - c.y, p.x - c.x) - atan2(start.y - c.y, start.x - c.x)).toDouble()).toFloat()
        while (deg > 180f) deg -= 360f
        while (deg <= -180f) deg += 360f
        val snapped = round(deg / SNAP_STEP_DEG) * SNAP_STEP_DEG
        if (abs(deg - snapped) <= SNAP_TOLERANCE_DEG) deg = snapped
        a.angle = deg
        return if (deg == 0f) Affine.IDENTITY else Affine.rotate(deg, c.x, c.y)
    }

    private fun scaling(a: Active, p: Vec2, start: Vec2): Affine {
        val b = a.bounds
        val h = a.handle
        val corner = h == Handle.NE || h == Handle.NW || h == Handle.SE || h == Handle.SW
        val minK = min(1f, MIN_SIZE_PT / max(max(b.width, b.height), 1e-3f))
        // Anchor = the opposite corner / edge of the dragged handle.
        val ax = when (h) { Handle.NE, Handle.SE, Handle.E -> b.l; Handle.NW, Handle.SW, Handle.W -> b.r; else -> b.center.x }
        val ay = when (h) { Handle.NW, Handle.NE, Handle.N -> b.b; Handle.SW, Handle.SE, Handle.S -> b.t; else -> b.center.y }
        val horizontal = h == Handle.E || h == Handle.W
        val vertical = h == Handle.N || h == Handle.S
        if (corner) {
            val d0 = dist(start.x, start.y, ax, ay)
            if (d0 < 1e-3f) return Affine.IDENTITY
            val k = max(dist(p.x, p.y, ax, ay) / d0, minK)
            return Affine.scale(k, k, ax, ay)
        }
        val k = if (horizontal) ratio(p.x - ax, start.x - ax) else ratio(p.y - ay, start.y - ay)
        val kk = max(k, minK)
        val free = a.items.all { it is TextBox || it is ImageItem }
        return when {
            !free -> Affine.scale(kk, kk, ax, ay)
            horizontal -> Affine.scale(kk, 1f, ax, ay)
            vertical -> Affine.scale(1f, kk, ax, ay)
            else -> Affine.IDENTITY
        }
    }

    private fun ratio(num: Float, den: Float): Float = if (abs(den) < 1e-3f) 1f else num / den

    private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        val dx = x0 - x1; val dy = y0 - y1
        return sqrt(dx * dx + dy * dy)
    }

    // ---- operations ----

    fun delete() {
        val (s, items) = current() ?: return
        editor.apply(Edit.Items(s.page, items, emptyList()))
        _selection.value = null
    }

    /** Copies the selection [offsetPt] right and down on the same page and selects the copies. */
    fun duplicate(offsetPt: Float = 12f) {
        val (s, items) = current() ?: return
        val shift = Affine.translate(offsetPt, offsetPt)
        val copies = items.map { ItemOps.withIdOrder(it.transformed(shift), editor.newItemId(), editor.nextOrder(s.page)) }
        editor.apply(Edit.Items(s.page, emptyList(), copies))
        setSelection(s.page, copies)
    }

    fun recolor(color: Int) = rewrite { ItemOps.recolored(it, color) }

    fun rewidth(widthPt: Float) = rewrite { ItemOps.rewidthed(it, widthPt) }

    private fun rewrite(f: (Item) -> Item?) {
        val (s, items) = current() ?: return
        val changed = items.mapNotNull { old -> f(old)?.let { old to it } }
        if (changed.isEmpty()) return
        editor.apply(Edit.Items(s.page, changed.map { it.first }, changed.map { it.second }))
        refresh()
    }

    /** Moves the selection above everything else on the page (within its layers), keeping its relative order. */
    fun bringToFront() {
        val (s, items) = current() ?: return
        val re = items.map { ItemOps.withOrder(it, editor.nextOrder(s.page)) }
        editor.apply(Edit.Items(s.page, items, re))
        refresh()
    }

    /** Moves the selection below everything else on the page, keeping its relative order. */
    fun sendToBack() {
        val (s, items) = current() ?: return
        val lowest = editor.content(s.page)?.all()?.minOfOrNull { it.order } ?: return
        val base = lowest - items.size
        val re = items.mapIndexed { i, it -> ItemOps.withOrder(it, base + i) }
        editor.apply(Edit.Items(s.page, items, re))
        refresh()
    }

    /** Locks the selected images (they become part of the page, like imported PDF pages) and ends the selection. */
    fun lockImages() {
        val (s, items) = current() ?: return
        val images = items.filterIsInstance<ImageItem>().filter { !it.locked }
        if (images.isEmpty()) return
        editor.apply(Edit.Items(s.page, images, images.map { it.copy(locked = true) }))
        _selection.value = null
    }

    fun copy(clipboard: InkClipboard) {
        val (_, items) = current() ?: return
        clipboard.put(items, unionBounds(items), editor)
    }

    fun cut(clipboard: InkClipboard) {
        copy(clipboard)
        delete()
    }

    private fun unionBounds(items: List<Item>): Rect = items.fold(Rect.EMPTY) { acc, i -> acc.union(i.bounds) }

    companion object {
        const val MIN_SIZE_PT = 4f
        const val SNAP_STEP_DEG = 45f
        const val SNAP_TOLERANCE_DEG = 3f
    }
}
