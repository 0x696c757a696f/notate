package com.alexdremov.notate.webdoc

import android.view.View
import com.alexdremov.notate.input.DriverPort
import com.alexdremov.notate.util.Logger
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.pen.EpdPenManager
import com.onyx.android.sdk.pen.TouchHelper

/** A web page overlay's connection to the Onyx pen driver. [com.alexdremov.notate.input.InkArbiter] is its only caller. */
class WebDriverPort(
    private val overlay: View,
    private val helper: () -> TouchHelper?,
    /** Called after the driver switched on or off, to apply the pen look / clean up. */
    private val onSwitched: (on: Boolean) -> Unit,
) : DriverPort {
    // The raw-drawing session is opened with the surface (WebInkRawInput creates its helper).
    override fun open() {}

    override fun close() {
        helper()?.let {
            runCatching {
                it.setRawDrawingEnabled(false)
                it.closeRawDrawing()
            }
        }
    }

    override fun setActive(on: Boolean) {
        val h = helper() ?: return
        try {
            h.setRawDrawingEnabled(on)
            // Another pane (the note beside the book) may have left the pen paused or out of scribble mode.
            runCatching {
                if (on) {
                    EpdController.enterScribbleMode(overlay)
                    EpdController.setScreenHandWritingPenState(overlay, EpdPenManager.PEN_DRAWING)
                } else {
                    EpdController.setScreenHandWritingPenState(overlay, EpdPenManager.PEN_PAUSE)
                }
            }
        } catch (t: Throwable) {
            Logger.w("WebDriverPort", "Failed to switch raw drawing", t)
        }
        onSwitched(on)
    }
}
