package app.notate.editor.text

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import app.notate.core.doc.tools.TextLayoutMetrics
import app.notate.core.doc.tools.TextStyle
import app.notate.core.model.TextAlign
import app.notate.core.model.TextBox

/**
 * A borderless `EditText` laid over a text box while it is edited (04 §3.8): same position, width and rotation, text size =
 * font size × zoom, white background so the stored text underneath does not show. It grows with its content and reports the
 * final text through [onDone] when the user taps elsewhere, the pen comes down outside, or Back is pressed. Main thread only.
 */
class TextEditorOverlay(
    private val parent: ViewGroup,
    private val box: TextBox,
    /** Screen px of the box's top-left corner and pixels per pt, read again on [reposition]. */
    private val placement: () -> Placement,
    private val metrics: TextLayoutMetrics,
    private val onDone: (text: String) -> Unit,
) {
    class Placement(val left: Float, val top: Float, val scale: Float)

    private val context: Context get() = parent.context
    private var style = TextStyle.of(box)
    private var finished = false

    private val edit = object : EditText(parent.context) {
        override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                finish()
                return true
            }
            return super.onKeyPreIme(keyCode, event)
        }
    }

    val view: View get() = edit
    val text: String get() = edit.text.toString()

    init {
        edit.apply {
            setBackgroundColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            setLineSpacing(0f, 1f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setText(box.text)
            setSelection(box.text.length)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = reposition()
            })
        }
        applyStyle(style)
    }

    /** Shows the editor and the soft keyboard. */
    fun show() {
        parent.addView(edit, FrameLayout.LayoutParams(1, 1))
        reposition()
        edit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Takes a new size / weight / colour / alignment from the toolbar. */
    fun applyStyle(s: TextStyle) {
        style = s
        val scale = placement().scale
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, s.sizePt * scale)
        edit.setTextColor(s.color)
        edit.typeface = if (s.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        edit.gravity = Gravity.TOP or when (s.align) {
            TextAlign.LEFT -> Gravity.START
            TextAlign.CENTER -> Gravity.CENTER_HORIZONTAL
            TextAlign.RIGHT -> Gravity.END
        }
        reposition()
    }

    fun currentStyle(): TextStyle = style

    /** Moves and sizes the editor for the current zoom and scroll, and grows it with the text. */
    fun reposition() {
        if (finished || edit.parent == null) return
        val p = placement()
        val w = (box.frame.width * p.scale).toInt().coerceAtLeast(1)
        val hPt = metrics.measure(text, style.sizePt, style.bold, box.frame.width)
        val h = (hPt * p.scale).toInt().coerceAtLeast(1)
        val lp = (edit.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(w, h)
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            edit.layoutParams = lp
        }
        edit.x = p.left
        edit.y = p.top
        edit.pivotX = w / 2f
        edit.pivotY = h / 2f
        edit.rotation = box.rotationDeg
        val sizePx = style.sizePt * p.scale
        if (edit.textSize != sizePx) edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    /** Ends the edit once: hides the keyboard, removes the view and reports the text. */
    fun finish() {
        if (finished) return
        finished = true
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(edit.windowToken, 0)
        val result = text
        parent.removeView(edit)
        onDone(result)
    }

    /** Drops the editor without reporting (the pane is released). */
    fun discard() {
        if (finished) return
        finished = true
        parent.removeView(edit)
    }

    /** Whether ([x], [y]) in the parent's coordinates lies on the editor. */
    fun contains(x: Float, y: Float): Boolean = x >= edit.x && y >= edit.y && x <= edit.x + edit.width && y <= edit.y + edit.height
}
