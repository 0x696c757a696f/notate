package app.notate.core.doc.tools

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.geometry.Rect
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.ItemId
import app.notate.core.model.PageId

/** Per-gesture tool configuration. [holdShapeMs] null = hold-to-shape off. */
data class ToolSettings(val holdShapeMs: Long?, val eraserRadiusPx: Float)

/** Ruler / protractor position (filled in by R2-6). */
interface GuideState {
    companion object { val NONE: GuideState = object : GuideState {} }
}

/** Everything a [ToolSession] needs; the page must be loaded in [editor] before the gesture starts. */
class ToolContext(
    val editor: DocumentEditor,
    val page: PageId,
    val pageBounds: Rect?,
    /** Current zoom: 1 pt = this many screen px (eraser radius and hit tolerance are screen-sized). */
    val pxPerPt: Float,
    val preview: PreviewSink,
    val settings: ToolSettings,
    val clock: Clock,
    val guides: GuideState = GuideState.NONE,
    /** Background of [page]; shape tools read the grid spacing and snap to it. */
    val background: Background? = null,
    /** Text measurement for the TEXT tool; the Android layer passes a `StaticLayout` implementation. */
    val textMetrics: TextLayoutMetrics = TextLayoutMetrics.Estimate,
) {
    /**
     * TEXT tool: a text box should be edited ([isNew] = it was just created empty and is not yet in the undo history).
     * Set by the pane; called on the main thread.
     */
    var onEditText: (id: ItemId, isNew: Boolean) -> Unit = { _, _ -> }

    /** True while a second finger holds the constraint (square, circle, 15° angles); set by the pane. */
    @Volatile var constraint: Boolean = false
}
