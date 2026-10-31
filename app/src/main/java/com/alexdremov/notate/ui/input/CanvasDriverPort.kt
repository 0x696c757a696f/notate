package com.alexdremov.notate.ui.input

import android.view.View
import com.alexdremov.notate.input.DriverPort
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.TouchHelper

/**
 * The note canvas's connection to the Onyx pen driver. [InkArbiter][com.alexdremov.notate.input.InkArbiter]
 * is its only caller.
 */
class CanvasDriverPort(
    private val view: View,
    private val helper: () -> TouchHelper?,
    /** Creates the helper if needed and opens the raw-drawing session (no-op while there is no surface). */
    private val openSession: () -> Unit,
    /** Re-applies the pen look for the current tool and zoom after the driver came back. */
    private val applyTool: () -> Unit,
) : DriverPort {
    override fun open() {
        openSession()
        helper() ?: return
        EpdController.enterScribbleMode(view)
    }

    override fun close() {
        helper()?.apply {
            setRawDrawingRenderEnabled(false)
            setRawDrawingEnabled(false)
            closeRawDrawing()
        }
        EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE)
        EpdController.leaveScribbleMode(view)
    }

    override fun setActive(on: Boolean) {
        val h = helper() ?: return
        if (on) {
            h.setRawDrawingEnabled(true)
            EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_DRAWING)
            applyTool()
        } else {
            h.setRawDrawingRenderEnabled(false)
            h.setRawDrawingEnabled(false)
            EpdController.setScreenHandWritingPenState(view, EpdPenManager.PEN_PAUSE)
        }
    }
}
