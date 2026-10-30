package com.alexdremov.notate.webdoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebInkPageTest {
    private fun line(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
    ) = WebInkStroke(WebInkTool.PEN, 0, 2f, floatArrayOf(x0, y0, x1, y1))

    @Test
    fun `erase hits segments not just points`() {
        val page = WebInkPage()
        val a = line(0f, 0f, 100f, 0f)
        val b = line(0f, 50f, 100f, 50f)
        page.add(a)
        page.add(b)
        assertFalse(page.eraseAt(50f, 20f, 5f))
        assertTrue(page.eraseAt(50f, 3f, 5f))
        assertEquals(listOf(b), page.strokes)
    }

    @Test
    fun `undo and redo restore order`() {
        val page = WebInkPage()
        val a = line(0f, 0f, 10f, 0f)
        val b = line(0f, 10f, 10f, 10f)
        val c = line(0f, 20f, 10f, 20f)
        listOf(a, b, c).forEach(page::add)
        page.eraseAt(5f, 10f, 1f)
        assertEquals(listOf(a, c), page.strokes)
        page.undo()
        assertEquals(listOf(a, b, c), page.strokes)
        page.redo()
        assertEquals(listOf(a, c), page.strokes)
        page.clear()
        assertTrue(page.strokes.isEmpty())
        page.undo()
        assertEquals(listOf(a, c), page.strokes)
    }
}
