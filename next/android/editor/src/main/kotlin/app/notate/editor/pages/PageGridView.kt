package app.notate.editor.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import app.notate.core.model.PageId
import app.notate.core.model.PageInfo
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * The thumbnails of the page grid, drawn by hand so that drag-to-reorder needs no recycler: tap opens a page, long press then
 * lift asks for the page menu, long press then drag reorders (one [Listener.onMove] per drop, 3 dp insertion bar, no animation).
 * Reordering is off while only bookmarked pages are listed. Main thread only.
 */
class PageGridView(context: Context, private val thumbs: Thumbs) : View(context) {
    /** Where thumbnails come from. */
    interface Thumbs {
        fun get(page: PageId): Bitmap?
        fun request(info: PageInfo)
    }

    interface Listener {
        fun onOpen(page: PageId)
        fun onMenu(page: PageId)

        /** [toIndex] is the index the page ends up at in the complete page list. */
        fun onMove(page: PageId, toIndex: Int)
    }

    var listener: Listener? = null

    /** Complete page list in order. */
    var pages: List<PageInfo> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }
    var current: PageId? = null
        set(value) { field = value; invalidate() }
    var bookmarksOnly: Boolean = false
        set(value) { field = value; requestLayout(); invalidate() }

    /** Number of columns for the current size (4 portrait, 6 landscape). */
    var columns: Int = 4
        private set

    private enum class Mode { IDLE, PRESS, LONG, DRAG }

    private val handler = Handler(Looper.getMainLooper())
    private val slop = dp(8).toFloat()
    private val pad = dp(16).toFloat()
    private val gap = dp(12).toFloat()
    private val labelH = dp(30).toFloat()
    private val line = Paint().apply { color = Ink.GRAY; style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat() }
    private val currentLine = Paint().apply { color = Ink.BLACK; style = Paint.Style.STROKE; strokeWidth = dp(3).toFloat() }
    private val fill = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; textSize = dp(16).toFloat(); textAlign = Paint.Align.CENTER }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val bar = Paint().apply { color = Ink.BLACK; style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tmp = RectF()

    private var mode = Mode.IDLE
    private var downX = 0f
    private var downY = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var pressed: Int = -1          // index into [shown]
    private var insertAt = -1               // "before cell" position 0..shown.size while dragging
    private val longPress = Runnable {
        if (mode == Mode.PRESS) {
            mode = Mode.LONG
            parent?.requestDisallowInterceptTouchEvent(true)
        }
    }

    private val shown: List<PageInfo> get() = if (bookmarksOnly) pages.filter { it.bookmarked } else pages

    private val cellW: Float get() = (width - 2 * pad - (columns - 1) * gap) / columns
    private val thumbH: Float get() = cellW * 4f / 3f
    private val cellH: Float get() = thumbH + labelH + gap

    /** Vertical offset of the row holding [page], for scrolling the grid to the current page. */
    fun rowTop(page: PageId): Int {
        val i = shown.indexOfFirst { it.id == page }
        return if (i < 0 || width == 0) 0 else (pad + (i / columns) * cellH).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val screenH = resources.displayMetrics.heightPixels
        columns = if (w > screenH) 6 else 4
        val cw = (w - 2 * pad - (columns - 1) * gap) / columns
        val rows = max(1, ceil(shown.size / columns.toFloat()).toInt())
        val h = (2 * pad + rows * (cw * 4f / 3f + labelH + gap)).toInt()
        setMeasuredDimension(w, h)
    }

    private fun cellRect(i: Int, out: RectF): RectF {
        val col = i % columns
        val row = i / columns
        val l = pad + col * (cellW + gap)
        val t = pad + row * cellH
        return out.also { it.set(l, t, l + cellW, t + thumbH) }
    }

    private fun cellAt(x: Float, y: Float): Int {
        val n = shown.size
        for (i in 0 until n) {
            cellRect(i, tmp)
            if (x >= tmp.left && x <= tmp.right && y >= tmp.top && y <= tmp.bottom + labelH) return i
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        val list = shown
        val clip = canvas.clipBounds
        val dragged = if (mode == Mode.DRAG) pressed else -1
        for (i in list.indices) {
            cellRect(i, tmp)
            if (tmp.bottom + labelH < clip.top || tmp.top > clip.bottom) continue
            drawCell(canvas, list[i], i, RectF(tmp), faded = i == dragged)
        }
        if (mode == Mode.DRAG && dragged in list.indices) {
            val r = cellRect(dragged, RectF())
            r.offsetTo(touchX - r.width() / 2f, touchY - r.height() / 2f)
            drawCell(canvas, list[dragged], dragged, r, faded = false)
            drawInsertBar(canvas)
        }
        if (list.isEmpty()) {
            text.textSize = dp(18).toFloat()
            canvas.drawText(context.getString(app.notate.editor.R.string.pages_no_bookmarks), width / 2f, dp(80).toFloat(), text)
            text.textSize = dp(16).toFloat()
        }
    }

    private fun drawCell(canvas: Canvas, p: PageInfo, index: Int, r: RectF, faded: Boolean) {
        canvas.drawRect(r, fill)
        val bmp = thumbs.get(p.id)
        if (bmp == null) thumbs.request(p) else if (!faded) {
            // Fit the page inside the 3:4 box, top-aligned and centered.
            val ratio = bmp.width.toFloat() / bmp.height
            var w = r.width()
            var h = w / ratio
            if (h > r.height()) { h = r.height(); w = h * ratio }
            val l = r.left + (r.width() - w) / 2f
            canvas.drawBitmap(bmp, null, RectF(l, r.top, l + w, r.top + h), bitmapPaint)
        }
        canvas.drawRect(r, if (p.id == current) currentLine else line)
        if (p.bookmarked) {
            val s = dp(22).toFloat()
            val path = Path().apply {
                moveTo(r.right - s, r.top); lineTo(r.right, r.top); lineTo(r.right, r.top + s); close()
            }
            canvas.drawPath(path, ink)
        }
        val number = pages.indexOfFirst { it.id == p.id } + 1
        canvas.drawText(context.getString(app.notate.editor.R.string.page_label, number), r.centerX(), r.bottom + labelH * 0.75f, text)
    }

    private fun drawInsertBar(canvas: Canvas) {
        val n = shown.size
        if (n == 0 || insertAt < 0) return
        val w = dp(3).toFloat()
        val x: Float
        val top: Float
        if (insertAt >= n) {
            cellRect(n - 1, tmp); x = tmp.right + gap / 2f; top = tmp.top
        } else {
            cellRect(insertAt, tmp); x = tmp.left - gap / 2f; top = tmp.top
        }
        canvas.drawRect(x - w / 2f, top, x + w / 2f, top + thumbH, bar)
    }

    /** Insertion slot (0..n) for a finger at ([x], [y]): before the nearest cell when the finger is in its left half, else after. */
    private fun slotAt(x: Float, y: Float): Int {
        val n = shown.size
        if (n == 0) return 0
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            cellRect(i, tmp)
            val cx = tmp.centerX()
            val cy = tmp.centerY()
            val d = (cx - x) * (cx - x) + (cy - y) * (cy - y)
            if (d < bestD) { bestD = d; best = i }
        }
        cellRect(best, tmp)
        return if (x < tmp.centerX()) best else best + 1
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; touchX = ev.x; touchY = ev.y
                pressed = cellAt(ev.x, ev.y)
                mode = if (pressed >= 0) Mode.PRESS else Mode.IDLE
                if (mode == Mode.PRESS) handler.postDelayed(longPress, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                touchX = ev.x; touchY = ev.y
                val moved = abs(ev.x - downX) > slop || abs(ev.y - downY) > slop
                when (mode) {
                    Mode.PRESS -> if (moved) cancelPress()
                    Mode.LONG -> if (moved && !bookmarksOnly) {
                        mode = Mode.DRAG
                        insertAt = slotAt(ev.x, ev.y)
                        invalidate()
                    }
                    Mode.DRAG -> { insertAt = slotAt(ev.x, ev.y); invalidate() }
                    Mode.IDLE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                val list = shown
                val idx = pressed
                val m = mode
                val slot = insertAt
                reset()
                if (idx !in list.indices) return true
                val page = list[idx].id
                when (m) {
                    Mode.PRESS -> listener?.onOpen(page)
                    Mode.LONG -> listener?.onMenu(page)
                    Mode.DRAG -> {
                        val to = if (slot > idx) slot - 1 else slot
                        if (to != idx && to in list.indices) listener?.onMove(page, to)
                    }
                    Mode.IDLE -> Unit
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                reset()
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun cancelPress() {
        handler.removeCallbacks(longPress)
        mode = Mode.IDLE
    }

    private fun reset() {
        mode = Mode.IDLE
        pressed = -1
        insertAt = -1
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }

    internal companion object {
        const val LONG_PRESS_MS = 500L
    }
}
