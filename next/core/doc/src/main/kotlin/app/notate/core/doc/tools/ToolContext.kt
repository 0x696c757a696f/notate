package app.notate.core.doc.tools

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.doc.select.SelectionController
import app.notate.core.geometry.Rect
import app.notate.core.ink.guide.ProtractorState
import app.notate.core.ink.guide.RulerState
import app.notate.core.model.Background
import app.notate.core.model.Clock
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.Units

/** Per-gesture tool configuration. [holdShapeMs] null = hold-to-shape off. */
data class ToolSettings(
    val holdShapeMs: Long?,
    val eraserRadiusPx: Float,
    /** Half-size of a selection handle's touch target in screen px (visual 6 dp + 8 dp slop). */
    val handleHitPx: Float = 20f,
    /** Distance of the rotation handle above the selection box in screen px (24 dp). */
    val rotateOffsetPx: Float = 34f,
)

/** Ruler / protractor on the page of the gesture (page coordinates), as far as the pane shows one. Main thread only. */
interface GuideState {
    val ruler: RulerState? get() = null
    val protractor: ProtractorState? get() = null

    /** How far from a guide edge a stroke may start and still follow it (6 mm on screen at the current zoom). */
    val snapTolPt: Float get() = Units.mm(6f)

    /** A stroke was drawn along a protractor ray at [absAngleDeg]; the pane keeps the last two to show the angle between them. */
    fun onRay(absAngleDeg: Float) {}

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
    /** Selection owner of the pane; lasso select needs it, other tools ignore it. */
    val selection: SelectionController? = null,
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
