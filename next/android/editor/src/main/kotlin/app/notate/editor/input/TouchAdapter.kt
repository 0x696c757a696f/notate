package app.notate.editor.input

import android.view.MotionEvent
import app.notate.core.input.PointerAction
import app.notate.core.input.PointerKind
import app.notate.core.input.PointerSample

/**
 * Turns Android [MotionEvent]s into [PointerSample]s for `InputDispatcher` (see its KDoc for the contract):
 * one sample per pointer and change, historical batched points of pens expanded in order, window coordinates.
 * [xdpi] converts the contact size to millimetres. Main thread only.
 */
class TouchAdapter(private val xdpi: Float) {
    fun convert(ev: MotionEvent): List<PointerSample> {
        val out = ArrayList<PointerSample>(ev.pointerCount)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> add(out, ev, ev.actionIndex, PointerAction.DOWN)
            MotionEvent.ACTION_POINTER_DOWN -> add(out, ev, ev.actionIndex, PointerAction.POINTER_DOWN)
            MotionEvent.ACTION_POINTER_UP -> add(out, ev, ev.actionIndex, PointerAction.POINTER_UP)
            MotionEvent.ACTION_UP -> add(out, ev, ev.actionIndex, PointerAction.UP)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until ev.pointerCount) add(out, ev, i, PointerAction.CANCEL)
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until ev.historySize) {
                    for (i in 0 until ev.pointerCount) {
                        if (kindOf(ev.getToolType(i)) != PointerKind.FINGER) addHistorical(out, ev, i, h)
                    }
                }
                for (i in 0 until ev.pointerCount) add(out, ev, i, PointerAction.MOVE)
            }
            MotionEvent.ACTION_HOVER_ENTER -> add(out, ev, ev.actionIndex, PointerAction.HOVER_ENTER)
            MotionEvent.ACTION_HOVER_MOVE -> add(out, ev, ev.actionIndex, PointerAction.HOVER_MOVE)
            MotionEvent.ACTION_HOVER_EXIT -> add(out, ev, ev.actionIndex, PointerAction.HOVER_EXIT)
        }
        return out
    }

    private fun add(out: MutableList<PointerSample>, ev: MotionEvent, i: Int, action: PointerAction) {
        out.add(
            PointerSample(
                id = ev.getPointerId(i),
                kind = kindOf(ev.getToolType(i)),
                action = action,
                x = ev.getX(i),
                y = ev.getY(i),
                pressure = ev.getPressure(i),
                majorMm = mm(ev.getTouchMajor(i)),
                buttons = ev.buttonState,
                timeMs = ev.eventTime,
                pointerCount = ev.pointerCount,
            ),
        )
    }

    private fun addHistorical(out: MutableList<PointerSample>, ev: MotionEvent, i: Int, h: Int) {
        out.add(
            PointerSample(
                id = ev.getPointerId(i),
                kind = kindOf(ev.getToolType(i)),
                action = PointerAction.MOVE,
                x = ev.getHistoricalX(i, h),
                y = ev.getHistoricalY(i, h),
                pressure = ev.getHistoricalPressure(i, h),
                majorMm = mm(ev.getHistoricalTouchMajor(i, h)),
                buttons = ev.buttonState,
                timeMs = ev.getHistoricalEventTime(h),
                pointerCount = ev.pointerCount,
            ),
        )
    }

    private fun mm(px: Float): Float = if (xdpi > 0f) px / xdpi * 25.4f else 0f

    private fun kindOf(toolType: Int): PointerKind = when (toolType) {
        MotionEvent.TOOL_TYPE_STYLUS -> PointerKind.STYLUS
        MotionEvent.TOOL_TYPE_ERASER -> PointerKind.ERASER_END
        MotionEvent.TOOL_TYPE_MOUSE -> PointerKind.MOUSE
        else -> PointerKind.FINGER
    }
}
