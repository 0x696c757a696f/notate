package app.notate.core.input

enum class InputPenMode { WRITE, INTERACT, READ_ONLY }

sealed interface PenEvent {
    val x: Float
    val y: Float

    data class Down(
        override val x: Float,
        override val y: Float,
        val pressure: Float,
        val tiltX: Float,
        val tiltY: Float,
        val tMs: Long,
        val eraser: Boolean,
    ) : PenEvent

    data class Move(
        override val x: Float,
        override val y: Float,
        val pressure: Float,
        val tiltX: Float,
        val tiltY: Float,
        val tMs: Long,
    ) : PenEvent

    data class Up(
        override val x: Float,
        override val y: Float,
        val tMs: Long,
    ) : PenEvent

    data class Cancel(
        override val x: Float,
        override val y: Float,
        val tMs: Long,
    ) : PenEvent

    data class Hover(
        override val x: Float,
        override val y: Float,
        val exit: Boolean,
    ) : PenEvent
}

/** One document view the dispatcher can route to. Coordinates are screen pixels. */
interface PaneTarget {
    val id: PaneId

    fun hitTest(
        x: Float,
        y: Float,
    ): Boolean

    val penMode: InputPenMode

    /**
     * False for panes whose view handles fingers itself (a web page scrolls and zooms on its own): the dispatcher still
     * applies palm rejection but passes the touches on instead of turning them into gestures.
     */
    val consumesFingers: Boolean get() = true

    fun onFingerGesture(g: FingerGesture)

    fun onPen(ev: PenEvent)
}

/**
 * Routes pointer samples to panes: palm rejection first, then finger gestures or pen events.
 * Main thread only. [onSample] returns true when the sample was consumed.
 */
class InputDispatcher(
    private val palm: PalmRejector,
    private val gestures: GestureClassifier,
    private val hover: HoverFocusDebouncer,
    private val focus: (PaneId) -> Unit,
) {
    /** The focused pane; updated by the dispatcher on [focus], set it when focus changes elsewhere. */
    var focusedPane: PaneId? = null

    /** False when the finger mode is Off: finger samples are left to ordinary views. */
    var fingersEnabled: Boolean = true

    private var gesturePane: PaneTarget? = null
    private var penPane: PaneTarget? = null

    fun onDriverStroke(
        begin: Boolean,
        timeMs: Long,
    ) {
        if (begin) palm.onPenDown(timeMs) else palm.onPenUp(timeMs)
    }

    fun onSample(
        s: PointerSample,
        panes: List<PaneTarget>,
    ): Boolean =
        when (s.kind) {
            PointerKind.FINGER -> onFinger(s, panes)
            else -> onPen(s, panes)
        }

    /** Drives long-press detection; returns true if a gesture was emitted. */
    fun onTimer(nowMs: Long): Boolean {
        val pane = gesturePane ?: return false
        val out = gestures.onTimer(nowMs)
        out.forEach { pane.onFingerGesture(it) }
        return out.isNotEmpty()
    }

    private fun onFinger(
        s: PointerSample,
        panes: List<PaneTarget>,
    ): Boolean {
        if (!fingersEnabled) return false
        if (s.action == PointerAction.HOVER_ENTER || s.action == PointerAction.HOVER_MOVE || s.action == PointerAction.HOVER_EXIT) return false
        val touch =
            TouchSample(
                action =
                    when (s.action) {
                        PointerAction.DOWN -> TouchAction.DOWN
                        PointerAction.UP -> TouchAction.UP
                        PointerAction.CANCEL -> TouchAction.CANCEL
                        PointerAction.POINTER_DOWN -> TouchAction.POINTER_DOWN
                        PointerAction.POINTER_UP -> TouchAction.POINTER_UP
                        else -> TouchAction.MOVE
                    },
                x = s.x,
                y = s.y,
                majorsPx = floatArrayOf(s.majorMm * palm.pxPerCm / 10f),
                time = s.timeMs,
            )
        when (palm.onTouch(touch)) {
            PalmRejector.Decision.REJECT -> return true
            PalmRejector.Decision.CANCEL_ONGOING -> {
                gesturePane?.onFingerGesture(FingerGesture.Cancel)
                gestures.cancel()
                gesturePane = null
                return true
            }
            PalmRejector.Decision.ACCEPT -> Unit
        }
        if (s.action == PointerAction.DOWN) {
            gesturePane = panes.firstOrNull { it.hitTest(s.x, s.y) }?.takeIf { it.consumesFingers }
        }
        val pane = gesturePane ?: return false
        for (g in gestures.onSample(s)) {
            if (g is FingerGesture.Tap && focusedPane != pane.id) focusPane(pane)
            pane.onFingerGesture(g)
        }
        if (gestures.isIdle) gesturePane = null
        return true
    }

    private fun onPen(
        s: PointerSample,
        panes: List<PaneTarget>,
    ): Boolean {
        when (s.action) {
            PointerAction.HOVER_ENTER, PointerAction.HOVER_MOVE -> {
                palm.onHover(s.timeMs, s.x, s.y)
                val under = panes.firstOrNull { it.hitTest(s.x, s.y) }
                val idx = if (under == null) -1 else panes.indexOf(under)
                val active = panes.indexOfFirst { it.id == focusedPane }
                hover.onHover(idx, active, s.timeMs)?.let { focusPane(panes[it]) }
                val target = panes.firstOrNull { it.id == focusedPane } ?: under
                target?.onPen(PenEvent.Hover(s.x, s.y, exit = false))
                return target != null
            }
            PointerAction.HOVER_EXIT -> {
                palm.onHoverExit(s.timeMs)
                hover.reset()
                val target = panes.firstOrNull { it.id == focusedPane }
                target?.onPen(PenEvent.Hover(s.x, s.y, exit = true))
                return target != null
            }
            PointerAction.DOWN, PointerAction.POINTER_DOWN -> {
                val pane = panes.firstOrNull { it.hitTest(s.x, s.y) } ?: return false
                palm.onPenDown(s.timeMs)
                when (pane.penMode) {
                    InputPenMode.INTERACT -> return false
                    InputPenMode.READ_ONLY -> return true
                    InputPenMode.WRITE -> Unit
                }
                if (focusedPane != pane.id) focusPane(pane)
                penPane = pane
                val eraser = s.kind == PointerKind.ERASER_END || s.buttons and PointerSample.BUTTON_STYLUS_PRIMARY != 0
                pane.onPen(PenEvent.Down(s.x, s.y, s.pressure, s.tiltX, s.tiltY, s.timeMs, eraser))
                return true
            }
            PointerAction.MOVE -> {
                val pane = penPane ?: return false
                pane.onPen(PenEvent.Move(s.x, s.y, s.pressure, s.tiltX, s.tiltY, s.timeMs))
                return true
            }
            PointerAction.UP, PointerAction.POINTER_UP, PointerAction.CANCEL -> {
                palm.onPenUp(s.timeMs)
                val pane = penPane ?: return false
                penPane = null
                pane.onPen(
                    if (s.action == PointerAction.CANCEL) PenEvent.Cancel(s.x, s.y, s.timeMs) else PenEvent.Up(s.x, s.y, s.timeMs),
                )
                return true
            }
        }
    }

    private fun focusPane(pane: PaneTarget) {
        focusedPane = pane.id
        focus(pane.id)
    }
}
