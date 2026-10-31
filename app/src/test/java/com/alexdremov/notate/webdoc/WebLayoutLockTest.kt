package com.alexdremov.notate.webdoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebLayoutLockTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val viewport = 1000f * 1500f

    private fun c(
        id: Int,
        scrollH: Float,
        clientH: Float,
        overflow: String = "visible",
        area: Float = 0f,
        root: Boolean = false,
    ) = ScrollCandidate(id, scrollH, clientH, overflow, area, root)

    // ---- scroll element rule

    @Test
    fun `root wins when the window scrolls`() {
        val root = c(0, 5000f, 1500f, root = true)
        val inner = c(1, 4000f, 1500f, "auto", viewport)
        assertEquals(0, ScrollElementPicker.pick(listOf(root, inner), viewport))
    }

    @Test
    fun `body that scrolls is picked when the window does not`() {
        val root = c(0, 1500f, 1500f, root = true)
        val body = c(1, 6000f, 1500f, "auto", viewport)
        assertEquals(1, ScrollElementPicker.pick(listOf(root, body), viewport))
    }

    @Test
    fun `largest scrollable element wins, tiny ones are ignored`() {
        val root = c(0, 1500f, 1500f, root = true)
        val small = c(1, 900f, 100f, "auto", 100f * 200f)
        val main = c(2, 4000f, 1400f, "scroll", 900f * 1400f)
        val bigger = c(3, 4000f, 1500f, "auto", 1000f * 1500f)
        assertEquals(3, ScrollElementPicker.pick(listOf(root, small, main, bigger), viewport))
        assertEquals(2, ScrollElementPicker.pick(listOf(root, small, main), viewport))
    }

    @Test
    fun `elements that do not overflow or are hidden are not scrollers`() {
        val root = c(0, 1500f, 1500f, root = true)
        val fits = c(1, 1500f, 1500f, "auto", viewport)
        val hidden = c(2, 5000f, 1500f, "hidden", viewport)
        assertEquals(0, ScrollElementPicker.pick(listOf(root, fits, hidden), viewport))
    }

    @Test
    fun `no candidates gives null`() {
        assertNull(ScrollElementPicker.pick(emptyList(), viewport))
    }

    // ---- layout width

    @Test
    fun `new document locks to the pane width but never beyond the cap`() {
        assertEquals(600f, WebLayoutLock.initialWidth(emptyList(), 600f), 0.01f)
        assertEquals(WebLayoutLock.MAX_WIDTH_CSS, WebLayoutLock.initialWidth(emptyList(), 1600f), 0.01f)
    }

    @Test
    fun `a pane that is not laid out yet does not lock anything`() {
        assertEquals(0f, WebLayoutLock.initialWidth(emptyList(), 0f), 0f)
    }

    @Test
    fun `existing ink keeps the width it was written at`() {
        assertEquals(640f, WebLayoutLock.initialWidth(listOf(640f, 640f, 800f), 1000f), 0.01f)
    }

    @Test
    fun `reflow clamps to a sane range`() {
        assertEquals(WebLayoutLock.MIN_WIDTH_CSS, WebLayoutLock.reflowWidth(50f), 0f)
        assertEquals(500f, WebLayoutLock.reflowWidth(500f), 0f)
    }

    // ---- stored layout

    @Test
    fun `layout is fixed on first use, survives reopen and is the same in a narrower pane`() {
        val library = WebDocLibrary(tmp.newFolder("lib"))
        val first = WebAnnotationStore.acquire(library, "doc")
        assertNull(first.layoutOrNull)
        assertEquals(700f, first.lockedWidthCss(700f), 0.01f)
        // Split view: the pane is half as wide, the document keeps its layout.
        assertEquals(700f, first.lockedWidthCss(350f), 0.01f)
        WebAnnotationStore.release(first)

        val again = WebAnnotationStore.acquire(library, "doc")
        assertEquals(700f, again.layoutOrNull?.widthCss ?: 0f, 0.01f)
        assertEquals(700f, again.lockedWidthCss(1000f), 0.01f)
        WebAnnotationStore.release(again)
    }

    @Test
    fun `reflow changes the width, bumps the generation and keeps old ink records`() {
        val library = WebDocLibrary(tmp.newFolder("lib"))
        val s = WebAnnotationStore.acquire(library, "doc")
        s.lockedWidthCss(800f)
        s.page("a.html").add(WebInkStroke(WebInkTool.PEN, 0, 2f, floatArrayOf(0f, 0f, 10f, 5f)))
        s.markInkChanged("a.html", 800f)
        assertTrue(s.hasInk())
        s.reflowTo(500f)
        assertEquals(500f, s.lockedWidthCss(800f), 0.01f)
        assertEquals(2, s.layoutOrNull?.gen)
        // The page still remembers it was written at 800, so the drift banner can warn.
        assertEquals(800f, s.layoutWidthOf("a.html") ?: 0f, 0.01f)
        assertTrue(WebLayoutLock.differs(s.layoutWidthOf("a.html") ?: 0f, s.lockedWidthCss(0f)))
        WebAnnotationStore.release(s)

        val reopened = WebAnnotationStore.acquire(library, "doc")
        assertNotNull(reopened.layoutOrNull)
        assertEquals(500f, reopened.layoutOrNull!!.widthCss, 0.01f)
        assertFalse(WebLayoutLock.differs(500f, 505f))
        WebAnnotationStore.release(reopened)
    }
}
