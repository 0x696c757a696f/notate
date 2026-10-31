package com.alexdremov.notate.input

import android.os.SystemClock
import android.view.MotionEvent

/** Turns Android finger events into the plain [TouchSample]s that [PalmRejector] understands. */
object InputClassifier {
    /** Whether the event's acting pointer is a finger (not the pen or its eraser tail). */
    fun isFinger(ev: MotionEvent): Boolean = ev.getToolType(ev.actionIndex) == MotionEvent.TOOL_TYPE_FINGER

    fun sample(ev: MotionEvent): TouchSample {
        val action =
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> TouchAction.DOWN
                MotionEvent.ACTION_UP -> TouchAction.UP
                MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
                MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
                MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
                else -> TouchAction.MOVE
            }
        return TouchSample(
            action = action,
            x = ev.rawX,
            y = ev.rawY,
            majorsPx = FloatArray(ev.pointerCount) { ev.getTouchMajor(it) },
            time = SystemClock.uptimeMillis(),
        )
    }

    /** A rejector for one window, tuned to this panel's density and the user's handedness. */
    fun newRejector(context: android.content.Context): PalmRejector {
        val xdpi = context.resources.displayMetrics.xdpi
        return PalmRejector(
            palmMinSizePx = PalmRejector.PALM_MIN_SIZE_MM / 25.4f * xdpi,
            pxPerCm = xdpi / 2.54f,
        ).also { it.handedness = com.alexdremov.notate.data.PreferencesManager.getHandedness(context) }
    }

    fun cancelCopy(ev: MotionEvent): MotionEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
}
