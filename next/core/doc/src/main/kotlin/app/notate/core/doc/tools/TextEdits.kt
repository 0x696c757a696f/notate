package app.notate.core.doc.tools

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.model.ItemBounds
import app.notate.core.model.PageId
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox
import app.notate.core.model.edit.Edit

/** Style of a text box under edit (what the toolbar shows while the keyboard is up). */
data class TextStyle(val sizePt: Float, val bold: Boolean, val color: Int, val align: TextAlign) {
    companion object {
        fun of(t: TextBox) = TextStyle(t.fontSizePt, t.bold, t.color, t.align)
    }
}

/** Turns a finished text edit into document edits. Main thread only. */
object TextEdits {
    /** [base] with new [text] and [style]; the height follows the content (width stays). */
    fun updated(base: TextBox, text: String, style: TextStyle, metrics: TextLayoutMetrics): TextBox {
        val h = metrics.measure(text, style.sizePt, style.bold, base.frame.width)
        val frame = base.frame.copy(b = base.frame.t + h)
        return base.copy(
            text = text, fontSizePt = style.sizePt, bold = style.bold, color = style.color, align = style.align,
            frame = frame, bounds = ItemBounds.frameBounds(frame, base.rotationDeg),
        )
    }

    /**
     * Commits an edit of box [id]. [isNew]: the box was created empty by the TEXT tool and is not in the undo history, so the
     * placeholder is dropped silently and the result enters as one undo step (Undo removes the box). Blank text deletes the box.
     * Returns the committed box, or null when it was deleted or nothing changed.
     */
    fun commit(editor: DocumentEditor, page: PageId, id: app.notate.core.model.ItemId, isNew: Boolean, text: String, style: TextStyle, metrics: TextLayoutMetrics): TextBox? {
        val current = editor.content(page)?.get(id) as? TextBox ?: return null
        val blank = text.isBlank()
        if (blank) {
            editor.apply(Edit.Items(page, listOf(current), emptyList()), undoable = !isNew)
            return null
        }
        val next = updated(current, text.trimEnd(), style, metrics)
        if (isNew) {
            editor.apply(Edit.Items(page, listOf(current), emptyList()), undoable = false)
            editor.apply(Edit.Items(page, emptyList(), listOf(next)))
            return next
        }
        if (next == current) return null
        editor.apply(Edit.Items(page, listOf(current), listOf(next)))
        return next
    }
}
