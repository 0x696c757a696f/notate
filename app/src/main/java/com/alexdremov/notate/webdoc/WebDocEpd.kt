package com.alexdremov.notate.webdoc

import android.view.View
import com.alexdremov.notate.util.EpdFastModeController
import com.alexdremov.notate.util.Logger
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode

/**
 * E Ink refresh policy for web documents:
 *  - while a finger scrolls/drags or the pen writes: fast (animation) waveform, no flashing;
 *  - once the screen has been idle for a moment: back to the normal high-quality waveform;
 *  - an explicit full (GC) refresh on demand to clear ghosting.
 * Every call is guarded so non-Onyx devices simply get normal Android rendering.
 */
class WebDocEpd(
    private val view: View,
) {
    companion object {
        private const val TAG = "WebDocEpd"
        const val IDLE_DELAY_MS = 700L
    }

    private var fast = false
    private val exitRunnable = Runnable { exitFast() }

    fun onInteractionStart() {
        view.removeCallbacks(exitRunnable)
        if (fast) return
        fast = true
        safe { EpdFastModeController.enterFastMode() }
    }

    fun onInteractionEnd() {
        view.removeCallbacks(exitRunnable)
        view.postDelayed(exitRunnable, IDLE_DELAY_MS)
    }

    /** Leaves fast mode now instead of after the idle delay (the pen is about to write). */
    fun settle() {
        view.removeCallbacks(exitRunnable)
        exitFast()
    }

    /** Content changed without touch (e.g. scripted animation or scroll by JS). */
    fun onContentMoved() {
        onInteractionStart()
        onInteractionEnd()
    }

    fun fullRefresh() {
        exitFast()
        safe { EpdController.invalidate(view.rootView, UpdateMode.GC) }
    }

    fun release() {
        view.removeCallbacks(exitRunnable)
        exitFast()
    }

    private fun exitFast() {
        if (!fast) return
        fast = false
        safe { EpdFastModeController.exitFastMode() }
        view.rootView.invalidate()
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Logger.d(TAG, "EPD call unavailable: ${t.message}")
        }
    }
}
