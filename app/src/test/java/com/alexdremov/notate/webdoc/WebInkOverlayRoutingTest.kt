package com.alexdremov.notate.webdoc

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who writes the stroke: the Onyx driver or the overlay's own MotionEvent handling. A stroke the
 * driver was not able to take must never be swallowed without being recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WebInkOverlayRoutingTest {
    private lateinit var overlay: WebInkOverlayView
    private lateinit var page: WebInkPage

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        overlay =
            WebInkOverlayView(
                context,
                object : WebInkOverlayView.Viewport {
                    override val scrollXPx = 0
                    override val scrollYPx = 0
                    override val scale = 1f
                },
            )
        page = WebInkPage()
        overlay.page = page
    }

    private fun event(
        action: Int,
        x: Float,
        y: Float,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0.5f })
        val t = SystemClock.uptimeMillis()
        return MotionEvent.obtain(t, t, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    /** Sends a short pen stroke; returns whether every event was consumed. */
    private fun penStroke(toolType: Int = MotionEvent.TOOL_TYPE_STYLUS): Boolean {
        var consumed = true
        consumed = overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f, toolType)) && consumed
        consumed = overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 40f, 20f, toolType)) && consumed
        consumed = overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 80f, 30f, toolType)) && consumed
        consumed = overlay.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 80f, 30f, toolType)) && consumed
        return consumed
    }

    @Test
    fun `no native channel - the overlay inks`() {
        overlay.nativeInput = false
        assertTrue(penStroke())
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun `native channel with a paused driver - the pen still writes`() {
        overlay.nativeInput = true
        overlay.driverLive = false
        assertTrue(penStroke())
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun `native channel with a live driver - the driver owns the stroke`() {
        overlay.nativeInput = true
        overlay.driverLive = true
        assertTrue(penStroke())
        assertEquals(0, page.strokes.size)
    }

    @Test
    fun `driver switching on mid-stroke does not split the stroke`() {
        overlay.nativeInput = true
        overlay.driverLive = false
        overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f))
        overlay.driverLive = true
        overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 40f, 20f))
        overlay.dispatchTouchEvent(event(MotionEvent.ACTION_MOVE, 80f, 30f))
        overlay.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 80f, 30f))
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun `suspended ink swallows the pen without writing`() {
        overlay.nativeInput = false
        overlay.inkSuspended = true
        assertTrue(penStroke())
        assertEquals(0, page.strokes.size)
        overlay.inkSuspended = false
        assertTrue(penStroke())
        assertEquals(1, page.strokes.size)
    }

    @Test
    fun `interact mode leaves the pen to the page`() {
        overlay.writeEnabled = false
        overlay.nativeInput = false
        val consumed = overlay.dispatchTouchEvent(event(MotionEvent.ACTION_DOWN, 10f, 10f))
        assertEquals(false, consumed)
        assertEquals(0, page.strokes.size)
    }

    @Test
    fun `penUsesDriver needs a live driver and write mode`() {
        overlay.nativeInput = true
        overlay.driverLive = true
        assertTrue(overlay.penUsesDriver)
        overlay.driverLive = false
        assertEquals(false, overlay.penUsesDriver)
        overlay.driverLive = true
        overlay.writeEnabled = false
        assertEquals(false, overlay.penUsesDriver)
    }
}
