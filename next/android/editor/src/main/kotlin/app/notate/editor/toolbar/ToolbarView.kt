package app.notate.editor.toolbar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.notate.core.doc.tools.ToolController
import app.notate.core.doc.tools.ToolState
import app.notate.core.model.Capability
import app.notate.core.model.EraserMode
import app.notate.core.model.LassoMode
import app.notate.core.model.ShapeKind
import app.notate.core.model.ToolKind
import app.notate.editor.R
import app.notate.editor.popup.ColorPalettePopup
import app.notate.editor.popup.MoreMenu
import app.notate.editor.popup.MenuEntry
import app.notate.editor.popup.PenSettingsPopup
import app.notate.editor.popup.PopupHost
import app.notate.editor.popup.WidthPopup
import app.notate.editor.ui.Ink
import app.notate.editor.ui.dp

/** What the toolbar asks of its window. */
interface ToolbarActions {
    fun onUndo()
    fun onRedo()
    fun onPages()
    fun onSplit()
    fun onMore(anchor: View)
    fun onRulerToggled(on: Boolean) {}

    /** The toolbar's picture changed (tool switch, selection): the window may refresh its area. */
    fun onToolbarChanged() {}
}

/**
 * The 52 dp tool bar (04 §3.1): tools on the left, the options of the active tool in the middle, Undo / Redo / Pages / Split /
 * More on the right. Everything reads and writes the shared [ToolController]. Main thread only.
 */
class ToolbarView(
    context: Context,
    private val controller: ToolController,
    private val popups: PopupHost,
    private val xdpi: Float,
    private val actions: ToolbarActions,
) : LinearLayout(context) {
    private class Def(val kind: ToolKind?, val cap: Capability, val button: ToolButton)

    private val tools = ArrayList<Def>()
    private val middle = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val undo: ToolButton
    private val redo: ToolButton
    private val pages: ToolButton
    private val split: ToolButton
    private val more: ToolButton
    private val rulerDef: Def
    private var rulerOn = false
    private var caps: Set<Capability> = Capability.entries.toSet()

    /** Whether the page in view has a grid that Snap to grid can use (plain paper greys the button). */
    var gridAvailable: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            render(controller.baseState(), force = true)
        }
    private var capReason: String = context.getString(R.string.unavailable_here)
    private var last: ToolState? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = LayerDrawable(arrayOf(ColorDrawable(Color.WHITE), GradientDrawable().apply { setColor(Color.BLACK) })).also {
            it.setLayerInset(1, 0, dp(51), 0, 0)
        }
        val left = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun tool(kind: ToolKind?, cap: Capability, icon: Int, label: Int): Def {
            val name = context.getString(label)
            val b = ToolButton(context, icon, name).onClick {
                if (kind == null) {
                    rulerOn = !rulerOn
                    actions.onRulerToggled(rulerOn)
                    last?.let { render(it, force = true) }
                } else {
                    controller.select(kind)
                }
            }
            return Def(kind, cap, b).also { tools.add(it); left.addView(b) }
        }
        tool(ToolKind.LASSO, Capability.LASSO, R.drawable.ic_highlight_alt, R.string.tool_lasso)
        tool(ToolKind.PEN, Capability.PEN, R.drawable.ic_pen, R.string.tool_pen)
        tool(ToolKind.HIGHLIGHTER, Capability.HIGHLIGHTER, R.drawable.ic_border_color, R.string.tool_highlighter)
        tool(ToolKind.ERASER, Capability.ERASER, R.drawable.ic_eraser, R.string.tool_eraser)
        tool(ToolKind.SHAPE, Capability.SHAPES, R.drawable.ic_category, R.string.tool_shapes)
        tool(ToolKind.TEXT, Capability.TEXT, R.drawable.ic_text_fields, R.string.tool_text)
        tool(ToolKind.IMAGE, Capability.IMAGE, R.drawable.ic_image, R.string.tool_image)
        rulerDef = tool(null, Capability.RULER, R.drawable.ic_straighten, R.string.tool_ruler)
        tool(ToolKind.ZOOM_WINDOW, Capability.ZOOM_WINDOW, R.drawable.ic_search, R.string.tool_zoom)
        tool(ToolKind.HAND, Capability.HAND, R.drawable.ic_pan_tool, R.string.tool_hand)
        addView(left)

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(middle)
        }
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        val right = LinearLayout(context).apply { orientation = HORIZONTAL }
        undo = ToolButton(context, R.drawable.ic_undo, context.getString(R.string.action_undo)).onClick { actions.onUndo() }
        redo = ToolButton(context, R.drawable.ic_redo, context.getString(R.string.action_redo)).onClick { actions.onRedo() }
        pages = ToolButton(context, R.drawable.ic_grid_view, context.getString(R.string.action_pages)).onClick { actions.onPages() }
        split = ToolButton(context, R.drawable.ic_view_column, context.getString(R.string.action_split)).onClick { actions.onSplit() }
        more = ToolButton(context, R.drawable.ic_more_horiz, context.getString(R.string.action_more)).onClick { actions.onMore(more) }
        split.reason = context.getString(R.string.coming_soon)
        undo.reason = context.getString(R.string.nothing_to_undo)
        redo.reason = context.getString(R.string.nothing_to_redo)
        listOf(undo, redo, pages, split, more).forEach { right.addView(it) }
        addView(right)
        render(controller.baseState())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(HEIGHT_DP), MeasureSpec.EXACTLY))
    }

    /** Greys out what the focused pane cannot do; if the active tool became unavailable the pen is selected. */
    fun bind(capabilities: Set<Capability>, unavailableReason: String = context.getString(R.string.unavailable_here)) {
        caps = capabilities
        capReason = unavailableReason
        for (d in tools) d.button.reason = if (d.cap in caps) null else capReason
        pages.reason = if (Capability.PAGES in caps) null else capReason
        val active = controller.baseState().active
        val def = tools.firstOrNull { it.kind == active }
        if (def != null && def.cap !in caps && Capability.PEN in caps) controller.select(ToolKind.PEN)
        if (rulerOn && Capability.RULER !in caps) {
            rulerOn = false
            actions.onRulerToggled(false)
        }
        render(controller.baseState(), force = true)
    }

    /** Undo / Redo follow the focused pane's history. */
    fun setUndoRedo(canUndo: Boolean, canRedo: Boolean) {
        undo.reason = if (canUndo) null else context.getString(R.string.nothing_to_undo)
        redo.reason = if (canRedo) null else context.getString(R.string.nothing_to_redo)
    }

    fun toolButton(kind: ToolKind): ToolButton? = tools.firstOrNull { it.kind == kind }?.button

    /** Shows [state]: selected tool and the options of that tool. */
    fun render(state: ToolState, force: Boolean = false) {
        if (!force && state == last) return
        last = state
        for (d in tools) d.button.isSelected = if (d.kind == null) rulerOn else d.kind == state.active
        middle.removeAllViews()
        val o = ToolbarOptions(context, middle, controller, popups, xdpi, gridAvailable)
        when (state.active) {
            ToolKind.PEN -> o.penOptions(state, ToolKind.PEN)
            ToolKind.HIGHLIGHTER -> o.penOptions(state, ToolKind.HIGHLIGHTER)
            ToolKind.ERASER -> o.eraserOptions(state)
            ToolKind.SHAPE -> o.shapeOptions(state)
            ToolKind.LASSO -> o.lassoOptions(state)
            ToolKind.TEXT -> o.textOptions(state)
            else -> Unit
        }
        actions.onToolbarChanged()
    }

    companion object {
        const val HEIGHT_DP = 52
    }
}

/** Builds the middle section for one tool into [row]. */
internal class ToolbarOptions(
    private val context: Context,
    private val row: LinearLayout,
    private val controller: ToolController,
    private val popups: PopupHost,
    private val xdpi: Float,
    private val gridAvailable: Boolean = true,
) {
    private fun divider() {
        row.addView(View(context).apply { setBackgroundColor(Ink.GRAY) }, LinearLayout.LayoutParams(context.dp(1), context.dp(24)).apply {
            leftMargin = context.dp(4); rightMargin = context.dp(4)
        })
    }

    fun penOptions(s: ToolState, kind: ToolKind) {
        val slots = ToolEdits.slots(s, kind)
        if (kind == ToolKind.PEN) {
            lateinit var b: ToolButton
            b = ToolButton(context, R.drawable.ic_tune, context.getString(R.string.pen_settings)).onClick {
                PenSettingsPopup(popups, b, controller, xdpi).show()
            }
            row.addView(b)
            divider()
        }
        slots.colors.forEachIndexed { i, c ->
            lateinit var v: ColorSlot
            v = ColorSlot(context, c, i == slots.color, context.getString(R.string.color_slot, i + 1), { ToolEdits.selectColor(controller, kind, i) }) {
                val alpha = if (kind == ToolKind.HIGHLIGHTER) 0x80 else 0xFF
                ColorPalettePopup(popups, v, c, alpha) { argb -> ToolEdits.setColor(controller, kind, argb) }.show()
            }
            row.addView(v)
        }
        divider()
        slots.widthsPt.forEachIndexed { i, w ->
            val mm = ToolEdits.mm(w)
            val dia = if (kind == ToolKind.HIGHLIGHTER) mm * 2.5f else 3f + mm * 10f
            lateinit var v: WidthSlot
            v = WidthSlot(context, dia.coerceIn(3f, 20f), i == slots.width, context.getString(R.string.width_slot, i + 1), {
                ToolEdits.selectWidth(controller, kind, i)
            }) { WidthPopup(popups, v, controller, kind).show() }
            row.addView(v)
        }
    }

    fun eraserOptions(s: ToolState) {
        fun mode(m: EraserMode, icon: Int, label: Int) {
            row.addView(ToolButton(context, icon, context.getString(label)).onClick { controller.update { it.copy(eraserMode = m) } }.also {
                it.isSelected = s.eraserMode == m
            })
        }
        mode(EraserMode.PARTIAL, R.drawable.ic_content_cut, R.string.eraser_partial)
        mode(EraserMode.STROKE, R.drawable.ic_delete, R.string.eraser_stroke)
        mode(EraserMode.LASSO, R.drawable.ic_gesture, R.string.eraser_lasso)
        divider()
        s.eraserSizesDp.forEachIndexed { i, dpSize ->
            row.addView(
                WidthSlot(context, (dpSize * 0.32f).coerceIn(3f, 20f), i == s.eraserSize, context.getString(R.string.eraser_size, i + 1), {
                    controller.update { it.copy(eraserSize = i) }
                }, null),
            )
        }
        divider()
        row.addView(
            ToolButton(context, R.drawable.ic_layers, context.getString(R.string.eraser_highlighter_only)).onClick {
                controller.update { it.copy(eraseHighlighterOnly = !it.eraseHighlighterOnly) }
            }.also { it.isSelected = s.eraseHighlighterOnly },
        )
    }

    fun shapeOptions(s: ToolState) {
        fun kind(k: ShapeKind, icon: Int, label: Int, selected: Boolean): ToolButton {
            val b = ToolButton(context, icon, context.getString(label)).onClick { controller.update { it.copy(shapeKind = k) } }
            b.isSelected = selected
            row.addView(b)
            return b
        }
        kind(ShapeKind.LINE, R.drawable.ic_horizontal_rule, R.string.shape_line, s.shapeKind == ShapeKind.LINE)
        kind(ShapeKind.ARROW, R.drawable.ic_north_east, R.string.shape_arrow, s.shapeKind == ShapeKind.ARROW)
        kind(ShapeKind.RECT, R.drawable.ic_crop_square, R.string.shape_rect, s.shapeKind == ShapeKind.RECT)
        kind(ShapeKind.ELLIPSE, R.drawable.ic_radio_button_unchecked, R.string.shape_ellipse, s.shapeKind == ShapeKind.ELLIPSE)
        lateinit var pb: ToolButton
        pb = kind(ShapeKind.POLYGON, R.drawable.ic_pentagon, R.string.shape_polygon, s.shapeKind == ShapeKind.POLYGON)
        pb.onLongClick {
            MoreMenu(
                popups, pb,
                (3..8).map { n ->
                    MenuEntry(context.getString(R.string.polygon_sides, n)) {
                        controller.update { it.copy(shapeKind = ShapeKind.POLYGON, polygonSides = n) }
                    }
                },
            ).show()
        }
        val axes = s.shapeKind == ShapeKind.AXES_2D || s.shapeKind == ShapeKind.AXES_Q1
        lateinit var ab: ToolButton
        ab = ToolButton(context, R.drawable.ic_show_chart, context.getString(R.string.shape_axes)).onClick {
            controller.update { if (axes) it else it.copy(shapeKind = ShapeKind.AXES_2D) }
        }.onLongClick {
            MoreMenu(
                popups, ab,
                listOf(
                    MenuEntry(context.getString(R.string.axes_2d)) { controller.update { it.copy(shapeKind = ShapeKind.AXES_2D) } },
                    MenuEntry(context.getString(R.string.axes_q1)) { controller.update { it.copy(shapeKind = ShapeKind.AXES_Q1) } },
                ),
            ).show()
        }
        ab.isSelected = axes
        row.addView(ab)
        divider()
        row.addView(
            ToolButton(context, R.drawable.ic_grid_on, context.getString(R.string.snap_to_grid)).onClick {
                controller.update { it.copy(snapToGrid = !it.snapToGrid) }
            }.also {
                it.isSelected = s.snapToGrid && gridAvailable
                it.reason = if (gridAvailable) null else context.getString(R.string.snap_unavailable)
            },
        )
    }

    fun lassoOptions(s: ToolState) {
        row.addView(ToolButton(context, R.drawable.ic_gesture, context.getString(R.string.lasso_freeform)).onClick {
            controller.update { it.copy(lassoMode = LassoMode.FREEFORM) }
        }.also { it.isSelected = s.lassoMode == LassoMode.FREEFORM })
        row.addView(ToolButton(context, R.drawable.ic_crop_square, context.getString(R.string.lasso_rectangle)).onClick {
            controller.update { it.copy(lassoMode = LassoMode.RECTANGLE) }
        }.also { it.isSelected = s.lassoMode == LassoMode.RECTANGLE })
    }

    fun textOptions(s: ToolState) {
        row.addView(ToolButton(context, R.drawable.ic_remove, context.getString(R.string.text_smaller)).onClick {
            controller.update { it.copy(textSizePt = stepSize(it.textSizePt, -1)) }
        })
        row.addView(TextView(context).apply {
            text = s.textSizePt.toInt().toString()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            minWidth = context.dp(36)
        })
        row.addView(ToolButton(context, R.drawable.ic_add, context.getString(R.string.text_larger)).onClick {
            controller.update { it.copy(textSizePt = stepSize(it.textSizePt, 1)) }
        })
        row.addView(ToolButton(context, R.drawable.ic_format_bold, context.getString(R.string.text_bold)).onClick {
            controller.update { it.copy(textBold = !it.textBold) }
        }.also { it.isSelected = s.textBold })
        divider()
        lateinit var v: ColorSlot
        v = ColorSlot(context, s.textColor, true, context.getString(R.string.text_color), {}) {
            ColorPalettePopup(popups, v, s.textColor) { argb -> controller.update { it.copy(textColor = argb) } }.show()
        }
        row.addView(v)
    }

    private fun stepSize(cur: Float, dir: Int): Float {
        val i = TEXT_SIZES.indexOfFirst { it >= cur }.let { if (it < 0) TEXT_SIZES.size - 1 else it }
        val next = when {
            dir < 0 -> if (TEXT_SIZES[i] < cur) i else i - 1
            else -> if (TEXT_SIZES[i] > cur) i else i + 1
        }
        return TEXT_SIZES[next.coerceIn(0, TEXT_SIZES.size - 1)]
    }

    private companion object {
        val TEXT_SIZES = listOf(8f, 10f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 40f, 48f, 64f)
    }
}
