package app.notate.core.input

/** Identifies a pane (one document view) inside a window. */
@JvmInline
value class PaneId(
    val value: String,
)

enum class PointerKind { STYLUS, ERASER_END, FINGER, MOUSE }

enum class PointerAction { DOWN, MOVE, UP, CANCEL, HOVER_ENTER, HOVER_MOVE, HOVER_EXIT, POINTER_DOWN, POINTER_UP }

/**
 * One pointer event in screen pixels. The Android adapter emits one sample per pointer and change:
 * [PointerAction.DOWN]/[PointerAction.UP] for the first/last pointer, [PointerAction.POINTER_DOWN]/
 * [PointerAction.POINTER_UP] for the others (with the lifted pointer's [id] and position), and one
 * [PointerAction.MOVE] per moved pointer. [pointerCount] counts the pointers down, including this one.
 */
data class PointerSample(
    val id: Int,
    val kind: PointerKind,
    val action: PointerAction,
    val x: Float,
    val y: Float,
    val pressure: Float = 0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val majorMm: Float = 0f,
    val buttons: Int = 0,
    val timeMs: Long = 0L,
    val pointerCount: Int = 1,
) {
    companion object {
        /** Same value as `MotionEvent.BUTTON_STYLUS_PRIMARY`. */
        const val BUTTON_STYLUS_PRIMARY = 0x20
    }
}
