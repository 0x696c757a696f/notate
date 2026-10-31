package com.alexdremov.notate.ui.tabs

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.alexdremov.notate.R
import com.alexdremov.notate.doc.SaveState
import com.alexdremov.notate.ui.dpToPx

/**
 * Tab strip across the top of the editor (GoodNotes-style): home, one tab per open document,
 * then the split-view buttons. Icons only, grayscale for e-ink. Lives outside the canvas, so the
 * pen never inks on it.
 */
class TabBarView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        var onHome: (() -> Unit)? = null
        var onTabClick: ((DocTab) -> Unit)? = null
        var onTabClose: ((DocTab) -> Unit)? = null
        var onSplit: ((SplitOrientation) -> Unit)? = null
        var onSwapPanes: (() -> Unit)? = null
        var onExitSplit: (() -> Unit)? = null

        private val tabRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        private val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = false
                addView(tabRow)
            }
        private val sideButton = iconButton(R.drawable.ic_split_side, "Split left and right") { onSplit?.invoke(SplitOrientation.SIDE_BY_SIDE) }
        private val stackedButton = iconButton(R.drawable.ic_split_stacked, "Split top and bottom") { onSplit?.invoke(SplitOrientation.STACKED) }
        private val swapButton = iconButton(R.drawable.ic_swap_panes, "Swap panes") { onSwapPanes?.invoke() }
        private val exitButton = iconButton(R.drawable.ic_split_exit, "Close split view") { onExitSplit?.invoke() }

        private val saveMarks = HashMap<String, TextView>()
        private var lastSaveStates: Map<String, SaveState> = emptyMap()

        private val bottomLine = Paint().apply { color = Color.BLACK }

        /** The reader has no split view yet; it hides the split buttons. */
        var splitButtonsVisible = true

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            setWillNotDraw(false)
            addView(iconButton(R.drawable.ic_home, "Home") { onHome?.invoke() })
            addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(sideButton)
            addView(stackedButton)
            addView(swapButton)
            addView(exitButton)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val h = dpToPx(1).toFloat()
            canvas.drawRect(0f, height - h, width.toFloat(), height.toFloat(), bottomLine)
        }

        /**
         * [shownPaths] are the documents on screen (one, or two in split view); [activePath] is
         * the one the pen writes in.
         */
        fun render(
            tabs: List<DocTab>,
            activePath: String?,
            shownPaths: Set<String>,
            split: SplitState?,
        ) {
            tabRow.removeAllViews()
            saveMarks.clear()
            var activeView: View? = null
            tabs.forEach { tab ->
                val view = tabView(tab, isActive = tab.path == activePath, isShown = tab.path in shownPaths)
                if (tab.path == activePath) activeView = view
                tabRow.addView(view)
            }
            sideButton.isSelected = split?.orientation == SplitOrientation.SIDE_BY_SIDE
            stackedButton.isSelected = split?.orientation == SplitOrientation.STACKED
            sideButton.visibility = if (splitButtonsVisible) View.VISIBLE else View.GONE
            stackedButton.visibility = if (splitButtonsVisible) View.VISIBLE else View.GONE
            swapButton.visibility = if (split != null) View.VISIBLE else View.GONE
            exitButton.visibility = if (split != null) View.VISIBLE else View.GONE
            setSaveStates(lastSaveStates)
            activeView?.let { v -> scroll.post { scroll.requestChildRectangleOnScreen(tabRow, android.graphics.Rect(v.left, 0, v.right, v.height), false) } }
        }

        /**
         * Marks tabs whose document has unsaved edits (a dot) or whose last save failed ("!").
         * Updates the marks in place, so a save does not rebuild the strip.
         */
        fun setSaveStates(states: Map<String, SaveState>) {
            lastSaveStates = states
            saveMarks.forEach { (path, view) ->
                val text =
                    when (states[path]) {
                        null, SaveState.Clean -> ""
                        is SaveState.Error -> "!"
                        else -> "\u25CF"
                    }
                if (view.text.toString() != text) {
                    view.text = text
                    view.contentDescription =
                        when (text) {
                            "!" -> "Not saved"
                            "" -> null
                            else -> "Unsaved changes"
                        }
                }
            }
        }

        private fun tabView(
            tab: DocTab,
            isActive: Boolean,
            isShown: Boolean,
        ): View {
            val row =
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(12), 0, dpToPx(2), 0)
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dpToPx(6).toFloat()
                            setColor(if (isActive) Color.rgb(0xEE, 0xEE, 0xEE) else Color.WHITE)
                            if (isShown) setStroke(dpToPx(if (isActive) 2 else 1), Color.BLACK)
                        }
                    contentDescription = tab.title
                    setOnClickListener { onTabClick?.invoke(tab) }
                }
            row.addView(
                TextView(context).apply {
                    text = tab.title
                    maxWidth = dpToPx(200)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(if (isShown) Color.BLACK else Color.rgb(0x55, 0x55, 0x55))
                    typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                },
            )
            row.addView(
                TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(Color.BLACK)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(4), 0, 0, 0)
                    saveMarks[tab.path] = this
                },
            )
            row.addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_close)
                    contentDescription = "Close tab"
                    val pad = dpToPx(8)
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener { onTabClose?.invoke(tab) }
                },
                LayoutParams(dpToPx(36), dpToPx(36)),
            )
            row.layoutParams =
                LayoutParams(LayoutParams.WRAP_CONTENT, dpToPx(36)).apply {
                    marginStart = dpToPx(4)
                    marginEnd = dpToPx(4)
                }
            return row
        }

        private fun iconButton(
            icon: Int,
            description: String,
            onClick: () -> Unit,
        ) = ImageView(context).apply {
            setImageResource(icon)
            contentDescription = description
            setBackgroundResource(R.drawable.bg_tool_selected)
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            layoutParams = LayoutParams(dpToPx(44), dpToPx(44))
            setOnClickListener { onClick() }
        }
    }
