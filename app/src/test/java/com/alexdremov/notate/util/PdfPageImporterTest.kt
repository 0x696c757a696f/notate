package com.alexdremov.notate.util

import com.alexdremov.notate.util.PdfPageImporter.Layout
import com.alexdremov.notate.util.PdfPageImporter.PageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageImporterTest {
    private val a4 = PageSize(595f, 842f)
    private val landscape = PageSize(842f, 595f)

    @Test
    fun `fixed slots place each page in consecutive notebook pages`() {
        val layout = Layout.FixedSlots(pageWidth = 2480f, pageHeight = 3508f, spacing = 100f, firstSlot = 0)
        val rects = PdfPageImporter.layoutPages(listOf(a4, a4, a4), layout)

        assertEquals(3, rects.size)
        rects.forEachIndexed { i, r ->
            val slotTop = i * (3508f + 100f)
            assertTrue(r.left >= 0f && r.right <= 2480f + 0.01f)
            assertTrue(r.top >= slotTop - 0.01f && r.bottom <= slotTop + 3508f + 0.01f)
        }
    }

    @Test
    fun `fixed slots start after existing pages`() {
        val layout = Layout.FixedSlots(pageWidth = 2480f, pageHeight = 3508f, spacing = 100f, firstSlot = 2)
        val r = PdfPageImporter.layoutPages(listOf(a4), layout).single()
        assertTrue(r.top >= 2 * 3608f - 0.01f)
    }

    @Test
    fun `fixed slots fit and center pages with a different aspect ratio`() {
        val layout = Layout.FixedSlots(pageWidth = 2480f, pageHeight = 3508f, spacing = 100f, firstSlot = 0)
        val r = PdfPageImporter.layoutPages(listOf(landscape), layout).single()

        assertEquals(2480f, r.width, 0.5f)
        assertEquals(842f / 595f, r.width / r.height, 0.001f)
        // Vertically centered in the slot
        assertEquals(3508f - r.bottom, r.top, 0.5f)
    }

    @Test
    fun `stacked layout keeps width and aspect ratio with spacing`() {
        val layout = Layout.Stacked(left = 10f, top = 500f, width = 2480f, spacing = 100f)
        val rects = PdfPageImporter.layoutPages(listOf(a4, landscape), layout)

        assertEquals(10f, rects[0].left, 0.01f)
        assertEquals(500f, rects[0].top, 0.01f)
        assertEquals(2480f * 842f / 595f, rects[0].height, 0.5f)
        assertEquals(rects[0].bottom + 100f, rects[1].top, 0.01f)
        assertEquals(2480f * 595f / 842f, rects[1].height, 0.5f)
    }

    @Test
    fun `notebook page size follows the first page aspect ratio`() {
        val (w, h) = PdfPageImporter.notebookPageSize(PageSize(612f, 792f), 2480f) // US Letter
        assertEquals(2480f, w, 0.01f)
        assertEquals(2480f * 792f / 612f, h, 0.5f)
    }

    @Test
    fun `render size respects the pixel budget`() {
        val (w, h) = PdfPageImporter.renderSize(a4, targetWidthPx = 1800, maxPixels = 6_000_000)
        assertEquals(1800, w)
        assertEquals((1800 * 842f / 595f).toInt().toFloat(), h.toFloat(), 1f)

        val tall = PageSize(100f, 10000f)
        val (tw, th) = PdfPageImporter.renderSize(tall, targetWidthPx = 1800, maxPixels = 6_000_000)
        assertTrue(tw.toLong() * th <= 6_000_000L + 2 * (tw + th))
        assertEquals(100f / 10000f, tw.toFloat() / th, 0.001f)
    }
}
