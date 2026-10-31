package com.alexdremov.notate.webdoc

import com.alexdremov.notate.doc.Capability
import com.alexdremov.notate.doc.DocKind
import com.alexdremov.notate.doc.SaveState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSessionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(i: Int) = WebInkStroke(WebInkTool.PEN, 0, 2f, floatArrayOf(i.toFloat(), 0f, i.toFloat() + 10f, 5f))

    private fun lib() = WebDocLibrary(tmp.newFolder("lib"))

    @Test
    fun `registry hands out one session per document and reloads after the last release`() {
        val library = lib()
        val a = WebDocSessionRegistry.acquire(library, "doc", "My book")
        val b = WebDocSessionRegistry.acquire(library, "doc")
        assertSame(a, b)
        assertEquals("My book", a.ref.title)
        assertEquals(DocKind.WEB, a.ref.kind)
        a.page("p.html").add(stroke(1))
        a.markInkChanged("p.html", 800f)
        WebDocSessionRegistry.release(b)
        WebDocSessionRegistry.release(a)
        val again = WebDocSessionRegistry.acquire(library, "doc")
        assertTrue(again !== a)
        assertEquals(1, again.page("p.html").strokes.size)
        WebDocSessionRegistry.release(again)
    }

    @Test
    fun `save state follows edits and flush`() =
        runTest {
            val library = lib()
            val s = WebDocSessionRegistry.acquire(library, "doc")
            assertEquals(SaveState.Clean, s.saveState.value)
            s.page("p.html").add(stroke(1))
            s.markInkChanged("p.html", 800f)
            assertEquals(SaveState.Dirty, s.saveState.value)
            s.flush()
            assertEquals(SaveState.Clean, s.saveState.value)
            assertEquals(1, library.loadAnnotations("doc").strokes["p.html"]?.size)
            WebDocSessionRegistry.release(s)
        }

    @Test
    fun `undo acts on the current page and marks it for saving`() =
        runTest {
            val library = lib()
            val s = WebDocSessionRegistry.acquire(library, "doc")
            assertFalse(s.undo.canUndo)
            s.currentHref = "p.html"
            s.page("p.html").add(stroke(1))
            s.markInkChanged("p.html", 800f)
            assertTrue(s.undo.canUndo)
            assertTrue(s.undo.undo())
            assertEquals(0, s.page("p.html").strokes.size)
            assertTrue(s.undo.canRedo)
            assertTrue(s.undo.redo())
            assertEquals(1, s.page("p.html").strokes.size)
            WebDocSessionRegistry.release(s)
        }

    @Test
    fun `books lack text and lasso`() {
        val library = lib()
        val s = WebDocSessionRegistry.acquire(library, "doc")
        assertFalse(Capability.TEXT in s.capabilities)
        assertTrue(Capability.PEN in s.capabilities)
        WebDocSessionRegistry.release(s)
    }
}
