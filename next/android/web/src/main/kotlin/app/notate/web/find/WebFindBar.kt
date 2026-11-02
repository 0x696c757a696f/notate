package app.notate.web.find

import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.editor.toolbar.ToolButton
import app.notate.editor.ui.dp
import app.notate.web.R

/**
 * The find bar at the top of a web pane (R4-3): search field, previous / next match, count ("3 of 12"), "all chapters" switch
 * and close. Icon buttons only. Reports; the pane does the searching. Main thread only.
 */
class WebFindBar(
    context: Context,
    private val onQuery: (String) -> Unit,
    private val onStep: (forward: Boolean) -> Unit,
    private val onAllChapters: (Boolean) -> Unit,
    private val onClose: () -> Unit,
) : LinearLayout(context) {
    private val field = EditText(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Color.BLACK)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        hint = context.getString(R.string.web_find_hint)
        setPadding(context.dp(8), 0, context.dp(8), 0)
        setOnEditorActionListener { _, _, _ -> onQuery(text.toString()); true }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onQuery(s?.toString().orEmpty())
        })
    }
    private val count = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.BLACK)
        gravity = Gravity.CENTER
        minWidth = context.dp(64)
    }
    private val previous = button(app.notate.editor.R.drawable.ic_back, R.string.web_find_previous) { onStep(false) }
        .apply { rotation = 90f }
    private val next = button(app.notate.editor.R.drawable.ic_back, R.string.web_find_next) { onStep(true) }
        .apply { rotation = -90f }
    private val all = button(app.notate.editor.R.drawable.ic_menu_book, R.string.web_find_all_chapters) { toggleAll() }
    private val close = button(app.notate.editor.R.drawable.ic_close, R.string.web_find_close) { onClose() }
    private var allChapters = false

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
        addView(field, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(count)
        addView(previous, LayoutParams(context.dp(44), context.dp(HEIGHT_DP)))
        addView(next, LayoutParams(context.dp(44), context.dp(HEIGHT_DP)))
        addView(all, LayoutParams(context.dp(44), context.dp(HEIGHT_DP)))
        addView(close, LayoutParams(context.dp(44), context.dp(HEIGHT_DP)))
        all.isSelected = false
    }

    private fun button(icon: Int, label: Int, click: () -> Unit) =
        ToolButton(context, icon, context.getString(label), size = HEIGHT_DP).onClick(click)

    private fun toggleAll() {
        allChapters = !allChapters
        all.isSelected = allChapters
        onAllChapters(allChapters)
    }

    val query: String get() = field.text.toString()

    fun focusField() {
        field.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.showSoftInput(field, 0)
    }

    /** Shows "[active] of [total]"; an empty query shows nothing, no match shows "0 of 0". */
    fun setCount(active: Int, total: Int) {
        count.text = when {
            query.isBlank() -> ""
            total <= 0 -> context.getString(R.string.web_find_none)
            else -> context.getString(R.string.web_find_count, active, total)
        }
    }

    companion object {
        const val HEIGHT_DP = 40
    }
}
