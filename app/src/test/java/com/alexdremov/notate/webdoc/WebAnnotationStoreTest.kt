package com.alexdremov.notate.webdoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebAnnotationStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(i: Int) = WebInkStroke(WebInkTool.PEN, 0, 2f, floatArrayOf(i.toFloat(), 0f, i.toFloat() + 10f, 5f))

    private fun lib() = WebDocLibrary(tmp.newFolder("lib"))

    private fun write(
        store: WebAnnotationStore,
        href: String,
        i: Int,
        width: Float = 800f,
    ) {
        store.page(href).add(stroke(i))
        store.markInkChanged(href, width)
    }

    @Test
    fun `two holders share one page instance and see each others strokes`() {
        val library = lib()
        val activity = WebAnnotationStore.acquire(library, "doc")
        val pane = WebAnnotationStore.acquire(library, "doc")
        assertSame(activity, pane)
        assertSame(activity.page("a.html"), pane.page("a.html"))
        write(activity, "a.html", 1)
        assertEquals(1, pane.page("a.html").strokes.size)
        WebAnnotationStore.release(pane)
        // Still open for the activity: the last holder flushes.
        assertEquals(0, library.loadAnnotations("doc").strokes.size)
        WebAnnotationStore.release(activity)
        assertEquals(1, library.loadAnnotations("doc").strokes["a.html"]?.size)
    }

    @Test
    fun `a hundred queued saves end with every stroke on disk`() {
        val library = lib()
        val store = WebAnnotationStore.acquire(library, "doc")
        repeat(100) {
            write(store, "a.html", it)
            store.save()
        }
        assertTrue(store.flushBlocking(10_000))
        assertEquals(100, library.loadAnnotations("doc").strokes["a.html"]?.size)
        assertEquals(100, store.diskStrokeCount("a.html"))
        assertEquals(100, store.memoryStrokeCount("a.html"))
        assertFalse(store.stats().unsaved)
        WebAnnotationStore.release(store)
    }

    @Test
    fun `flush makes disk equal memory including erase and clear`() {
        val library = lib()
        val store = WebAnnotationStore.acquire(library, "doc")
        write(store, "a.html", 1)
        write(store, "a.html", 2)
        write(store, "b.html", 3)
        assertTrue(store.flushBlocking())
        store.page("a.html").clear()
        store.markInkChanged("a.html")
        assertTrue(store.flushBlocking())
        val disk = library.loadAnnotations("doc")
        assertNull(disk.strokes["a.html"])
        assertEquals(1, disk.strokes["b.html"]?.size)
        WebAnnotationStore.release(store)
    }

    @Test
    fun `corrupt file is backed up and the store starts empty`() {
        val library = lib()
        val dir = library.docDir("doc").apply { mkdirs() }
        File(dir, "annotations.json").writeText("{ not json")
        val store = WebAnnotationStore.acquire(library, "doc")
        assertEquals(0, store.page("a.html").strokes.size)
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("annotations.corrupt-") })
        WebAnnotationStore.release(store)
    }

    @Test
    fun `reopen restores strokes, last page, scroll and layout width`() {
        val library = lib()
        val first = WebAnnotationStore.acquire(library, "doc")
        write(first, "a.html", 1, width = 640f)
        first.setScroll("a.html", 321f)
        first.lastPage = "a.html"
        WebAnnotationStore.release(first)

        val second = WebAnnotationStore.acquire(library, "doc")
        assertEquals(1, second.page("a.html").strokes.size)
        assertEquals(321f, second.scrollOf("a.html"), 0f)
        assertEquals("a.html", second.lastPage)
        assertEquals(640f, second.layoutWidthOf("a.html")!!, 0f)
        WebAnnotationStore.release(second)
    }

    @Test
    fun `layout width is recorded once, with the first ink`() {
        val library = lib()
        val store = WebAnnotationStore.acquire(library, "doc")
        assertNull(store.layoutWidthOf("a.html"))
        write(store, "a.html", 1, width = 700f)
        write(store, "a.html", 2, width = 400f)
        assertEquals(700f, store.layoutWidthOf("a.html")!!, 0f)
        WebAnnotationStore.release(store)
    }

    @Test
    fun `layout drift needs more than five percent`() {
        assertFalse(WebAnnotationStore.isLayoutDrift(null, 500f))
        assertFalse(WebAnnotationStore.isLayoutDrift(800f, 800f))
        assertFalse(WebAnnotationStore.isLayoutDrift(800f, 830f))
        assertTrue(WebAnnotationStore.isLayoutDrift(800f, 400f))
        assertTrue(WebAnnotationStore.isLayoutDrift(800f, 900f))
        assertFalse(WebAnnotationStore.isLayoutDrift(800f, 0f))
    }

    @Test
    fun `unsaved flag follows changes`() {
        val library = lib()
        val store = WebAnnotationStore.acquire(library, "doc")
        assertFalse(store.stats().unsaved)
        write(store, "a.html", 1)
        assertTrue(store.stats().unsaved)
        store.flushBlocking()
        assertFalse(store.stats().unsaved)
        assertNotNull(store.stats().lastSaveAtMs.takeIf { it > 0 })
        WebAnnotationStore.release(store)
    }

    @Test
    fun `unknown json keys from newer versions are ignored`() {
        val library = lib()
        val dir = library.docDir("doc").apply { mkdirs() }
        File(dir, "annotations.json").writeText("""{"version":1,"strokes":{},"future":42}""")
        val store = WebAnnotationStore.acquire(library, "doc")
        assertEquals(0, store.page("a.html").strokes.size)
        WebAnnotationStore.release(store)
    }
}
