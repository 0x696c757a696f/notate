package app.notate.core.doc.tools

import app.notate.core.doc.editor.DocumentEditor
import app.notate.core.geometry.Rect
import app.notate.core.model.Clock
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
)
