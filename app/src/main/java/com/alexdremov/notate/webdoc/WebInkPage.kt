package com.alexdremov.notate.webdoc

/**
 * The ink on one page of a web document, in document (CSS px) coordinates,
 * with a simple undo history. Kept free of Android types so it can be unit-tested.
 */
class WebInkPage(
    initial: List<WebInkStroke> = emptyList(),
) {
    private sealed interface Edit {
        class Added(
            val stroke: WebInkStroke,
        ) : Edit

        class Erased(
            val strokes: List<Pair<Int, WebInkStroke>>,
        ) : Edit

        class Cleared(
            val strokes: List<WebInkStroke>,
        ) : Edit
    }

    private val _strokes = ArrayList(initial)
    val strokes: List<WebInkStroke> get() = _strokes
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun add(stroke: WebInkStroke) {
        if (stroke.points.size < 2) return
        _strokes.add(stroke)
        push(Edit.Added(stroke))
    }

    /** Removes every stroke passing within [radius] CSS px of ([x], [y]). Returns true if anything was erased. */
    fun eraseAt(
        x: Float,
        y: Float,
        radius: Float,
    ): Boolean {
        val removed = ArrayList<Pair<Int, WebInkStroke>>()
        for (i in _strokes.indices.reversed()) {
            val s = _strokes[i]
            if (hits(s, x, y, radius + s.width / 2f)) {
                removed.add(i to s)
                _strokes.removeAt(i)
            }
        }
        if (removed.isEmpty()) return false
        push(Edit.Erased(removed.reversed()))
        return true
    }

    fun clear() {
        if (_strokes.isEmpty()) return
        push(Edit.Cleared(ArrayList(_strokes)))
        _strokes.clear()
    }

    fun undo(): Boolean {
        val edit = undoStack.removeLastOrNull() ?: return false
        when (edit) {
            is Edit.Added -> _strokes.remove(edit.stroke)
            is Edit.Erased -> edit.strokes.forEach { (i, s) -> _strokes.add(i.coerceAtMost(_strokes.size), s) }
            is Edit.Cleared -> _strokes.addAll(edit.strokes)
        }
        redoStack.addLast(edit)
        return true
    }

    fun redo(): Boolean {
        val edit = redoStack.removeLastOrNull() ?: return false
        when (edit) {
            is Edit.Added -> _strokes.add(edit.stroke)
            is Edit.Erased -> edit.strokes.forEach { (_, s) -> _strokes.remove(s) }
            is Edit.Cleared -> _strokes.clear()
        }
        undoStack.addLast(edit)
        return true
    }

    private fun push(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > 200) undoStack.removeFirst()
        redoStack.clear()
    }

    companion object {
        fun hits(
            stroke: WebInkStroke,
            x: Float,
            y: Float,
            radius: Float,
        ): Boolean {
            val p = stroke.points
            val r2 = radius * radius
            if (p.size == 2) return dist2(x, y, p[0], p[1]) <= r2
            var i = 0
            while (i + 3 < p.size) {
                if (segmentDist2(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= r2) return true
                i += 2
            }
            return false
        }

        private fun dist2(
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float = (ax - bx) * (ax - bx) + (ay - by) * (ay - by)

        private fun segmentDist2(
            px: Float,
            py: Float,
            ax: Float,
            ay: Float,
            bx: Float,
            by: Float,
        ): Float {
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            if (len2 == 0f) return dist2(px, py, ax, ay)
            val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
            return dist2(px, py, ax + t * dx, ay + t * dy)
        }
    }
}
