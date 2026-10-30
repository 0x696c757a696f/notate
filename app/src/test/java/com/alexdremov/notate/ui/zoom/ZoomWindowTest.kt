package com.alexdremov.notate.ui.zoom

import android.graphics.Matrix
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ZoomWindowTest {
    private lateinit var zw: ZoomWindow
    private val viewMatrix = Matrix().apply { setScale(2f, 2f) } // canvas zoom 2x, no pan

    @Before
    fun setUp() {
        zw = ZoomWindow()
        zw.open(viewMatrix, 1000, 1400, 1f)
    }

    @Test
    fun `box is docked at the bottom with the writing area under the controls`() {
        assertTrue(zw.isOpen)
        assertEquals(1392f, zw.box.bottom, 0.01f)
        assertEquals(zw.controls.bottom, zw.writing.top, 0.01f)
        assertTrue(zw.box.top > 700f)
    }

    @Test
    fun `writing area shows the target magnified on top of the canvas zoom`() {
        assertEquals(2f * ZoomWindow.DEFAULT_MAGNIFICATION, zw.scale, 0.001f)
        assertEquals(zw.writing.width() / zw.scale, zw.target.width(), 0.01f)
        assertEquals(zw.writing.height() / zw.scale, zw.target.height(), 0.01f)
    }

    @Test
    fun `world to writing maps target corners onto the writing area and back`() {
        val m = zw.worldToWriting()
        val pts = floatArrayOf(zw.target.left, zw.target.top, zw.target.right, zw.target.bottom)
        m.mapPoints(pts)
        assertEquals(zw.writing.left, pts[0], 0.01f)
        assertEquals(zw.writing.top, pts[1], 0.01f)
        assertEquals(zw.writing.right, pts[2], 0.01f)
        assertEquals(zw.writing.bottom, pts[3], 0.01f)

        val inv = Matrix()
        m.invert(inv)
        val back = floatArrayOf(zw.writing.centerX(), zw.writing.centerY())
        inv.mapPoints(back)
        assertEquals(zw.target.centerX(), back[0], 0.01f)
        assertEquals(zw.target.centerY(), back[1], 0.01f)
    }

    @Test
    fun `stroke near the right edge slides the target, short strokes do not`() {
        val left = zw.target.left
        val w = zw.target.width()
        assertFalse(zw.advanceAfterStroke(RectF(left, zw.target.top, left + w * 0.5f, zw.target.top + 5f)))
        assertEquals(left, zw.target.left, 0.001f)

        val strokeRight = left + w * 0.9f
        assertTrue(zw.advanceAfterStroke(RectF(left + w * 0.8f, zw.target.top, strokeRight, zw.target.top + 5f)))
        assertEquals(strokeRight - w * ZoomWindow.ADVANCE_KEEP, zw.target.left, 0.01f)
        assertEquals(w, zw.target.width(), 0.01f)
    }

    @Test
    fun `new line returns to the line start one target height down`() {
        val startX = zw.target.left
        val top = zw.target.top
        val h = zw.target.height()
        zw.step(ZoomWindow.STEP_FRACTION)
        zw.step(ZoomWindow.STEP_FRACTION)
        zw.newLine()
        assertEquals(startX, zw.target.left, 0.01f)
        assertEquals(top + h, zw.target.top, 0.01f)
    }

    @Test
    fun `dragged position becomes the new line start`() {
        zw.moveBy(40f, 10f)
        zw.commitLineStart()
        val start = zw.target.left
        zw.step(1f)
        zw.newLine()
        assertEquals(start, zw.target.left, 0.01f)
    }

    @Test
    fun `zoom is clamped and keeps the target corner`() {
        val left = zw.target.left
        val top = zw.target.top
        repeat(20) { zw.zoomBy(ZoomWindow.ZOOM_STEP) }
        assertEquals(ZoomWindow.MAX_MAGNIFICATION, zw.magnification, 0.001f)
        repeat(40) { zw.zoomBy(1f / ZoomWindow.ZOOM_STEP) }
        assertEquals(ZoomWindow.MIN_MAGNIFICATION, zw.magnification, 0.001f)
        assertEquals(left, zw.target.left, 0.001f)
        assertEquals(top, zw.target.top, 0.001f)
    }

    @Test
    fun `canvas zoom changes resize the target`() {
        val w = zw.target.width()
        zw.onViewScaleChanged(4f)
        assertEquals(w / 2f, zw.target.width(), 0.01f)
    }

    @Test
    fun `controls are hit-tested and the writing area is not a control`() {
        for ((control, rect) in zw.controlRects()) {
            assertEquals(control, zw.controlAt(rect.centerX(), rect.centerY()))
        }
        assertNull(zw.controlAt(zw.writing.centerX(), zw.writing.centerY()))
        assertTrue(zw.isInWriting(zw.writing.centerX(), zw.writing.centerY()))
        assertFalse(zw.isInWriting(zw.controls.centerX(), zw.controls.centerY()))
    }

    @Test
    fun `closed window captures nothing`() {
        zw.close()
        assertFalse(zw.isInWriting(zw.writing.centerX(), zw.writing.centerY()))
        assertNull(zw.controlAt(zw.controlRects()[0].second.centerX(), zw.controlRects()[0].second.centerY()))
    }
}
