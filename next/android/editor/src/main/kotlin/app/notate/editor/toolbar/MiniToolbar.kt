package app.notate.editor.toolbar

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.core.doc.save.SaveState
import app.notate.core.model.EraserMode
import app.notate.core.model.ToolKind
import app.notate.editor.R

/**
 * Temporary 52 dp toolbar (R2-2 replaces it): Back, title, Pen, Eraser, Undo, Redo and the save indicator. Icons only;
 * a pressed or selected button is shown inverted. Main thread only.
 */
class MiniToolbar(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {
    interface Callbacks {
        fun onPen()
        fun onEraser()
        fun onUndo()
        fun onRedo()
        fun onBack()
    }

    private val density = context.resources.displayMetrics.density
    private val title = TextView(context)
    private val status = TextView(context)
    private val pen: ImageButton
    private val eraser: ImageButton
    private val undo: ImageButton
    private val redo: ImageButton
    private var eraserMode = EraserMode.PARTIAL

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundDrawable(
            LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
                it.setLayerInset(1, 0, dp(51), 0, 0)
            },
        )
        addView(button(R.drawable.ic_back, context.getString(R.string.action_back)) { callbacks.onBack() })
        title.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
        }
        addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        status.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.BLACK)
            setPadding(dp(8), 0, dp(8), 0)
        }
        addView(status)
        pen = button(R.drawable.ic_pen, context.getString(R.string.tool_pen)) { callbacks.onPen() }
        eraser = button(R.drawable.ic_eraser, context.getString(R.string.tool_eraser)) { callbacks.onEraser() }
        undo = button(R.drawable.ic_undo, context.getString(R.string.action_undo)) { callbacks.onUndo() }
        redo = button(R.drawable.ic_redo, context.getString(R.string.action_redo)) { callbacks.onRedo() }
        listOf(pen, eraser, undo, redo).forEach { addView(it) }
    }

    fun setTitle(text: String) { title.text = text }

    fun setTool(kind: ToolKind, eraserMode: EraserMode) {
        this.eraserMode = eraserMode
        pen.isSelected = kind == ToolKind.PEN
        eraser.isSelected = kind == ToolKind.ERASER
        eraser.contentDescription = context.getString(R.string.tool_eraser)
    }

    fun setUndoRedo(canUndo: Boolean, canRedo: Boolean) {
        undo.isEnabled = canUndo
        redo.isEnabled = canRedo
    }

    /** "●" while edits are waiting to be written, "!" when writing failed, nothing when everything is saved. */
    fun setSaveState(state: SaveState) {
        status.text = when (state) {
            SaveState.Clean -> ""
            is SaveState.Pending, SaveState.Writing -> "●"
            is SaveState.Error -> "!"
        }
    }

    private fun button(icon: Int, description: String, onClick: () -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = description
            scaleType = android.widget.ImageView.ScaleType.CENTER
            imageTintList = ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(0xFF9E9E9E.toInt(), Color.WHITE, Color.WHITE, Color.BLACK),
            )
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Color.BLACK))
                addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(Color.BLACK))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            layoutParams = LayoutParams(dp(56), dp(51))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY))
    }
}
