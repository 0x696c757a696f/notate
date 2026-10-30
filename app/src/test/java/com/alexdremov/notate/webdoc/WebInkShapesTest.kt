package com.alexdremov.notate.webdoc

import android.graphics.PointF
import com.alexdremov.notate.model.ShapeType
import com.alexdremov.notate.model.StrokeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WebInkShapesTest {
    @Test
    fun `drag shape stays in css px`() {
        val segments = WebInkShapes.dragShape(ShapeType.RECTANGLE, 5, PointF(10f, 10f), PointF(60f, 40f), scale = 2f)
        assertEquals(4, segments.size)
        val xs = segments.flatMap { s -> s.points.map { it.x } }
        assertEquals(10f, xs.minOrNull()!!, 0.01f)
        assertEquals(60f, xs.maxOrNull()!!, 0.01f)
    }

    @Test
    fun `tap threshold is measured on screen`() {
        // 5 css px at 2x zoom is 10 screen px: a real drag, not a tap.
        assertTrue(WebInkShapes.dragShape(ShapeType.LINE, 5, PointF(0f, 0f), PointF(5f, 0f), scale = 2f).isNotEmpty())
        assertTrue(WebInkShapes.dragShape(ShapeType.LINE, 5, PointF(0f, 0f), PointF(5f, 0f), scale = 1f).isEmpty())
    }

    @Test
    fun `straight freehand stroke becomes a line`() {
        val pts = FloatArray(40)
        for (i in 0 until 20) {
            pts[i * 2] = i * 10f
            pts[i * 2 + 1] = if (i % 2 == 0) 0f else 0.5f
        }
        val shape = WebInkShapes.recognize(pts, scale = 1.5f)
        assertNotNull(shape)
        assertEquals(1, shape!!.size)
    }

    @Test
    fun `too short stroke is not recognized`() {
        assertNull(WebInkShapes.recognize(floatArrayOf(0f, 0f, 1f, 1f), scale = 1f))
    }

    @Test
    fun `annotations saved before line styles still load`() {
        val old = """{"strokes":{"a.html":[{"tool":"PEN","color":0,"width":2.0,"points":[1.0,2.0,3.0,4.0]}]}}"""
        val parsed = WebDocLibrary.json.decodeFromString(WebDocAnnotations.serializer(), old)
        assertEquals(StrokeType.FINELINER, parsed.strokes.getValue("a.html").single().style)
    }
}
