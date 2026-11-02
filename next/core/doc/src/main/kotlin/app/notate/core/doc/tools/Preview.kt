package app.notate.core.doc.tools

import app.notate.core.geometry.Vec2
import app.notate.core.model.InkStyle
import app.notate.core.model.Item
import app.notate.core.model.ItemId
import app.notate.core.model.StrokePoints

/** Receives transient visuals from a tool session while a gesture is in progress. Main thread only. */
interface PreviewSink {
    fun show(p: Preview)
    fun clear()
}

/** Transient visual for a gesture in progress; all geometry is in page coordinates (pt). */
sealed interface Preview {
    data class LiveStroke(val points: StrokePoints, val style: InkStyle) : Preview
    /** Eraser circle; [radius] in pt. */
    data class EraserCursor(val center: Vec2, val radius: Float) : Preview
    class LassoPath(val points: FloatArray) : Preview
    /** Shape drag / selection move preview. */
    data class Ghost(val items: List<Item>) : Preview
    /** Items to hide while the gesture runs (stroke eraser). */
    data class Hidden(val ids: Set<ItemId>) : Preview
}
