package com.alexdremov.notate.webdoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebInkStoreV2Test {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun stroke(i: Int) = WebInkStroke(WebInkTool.PEN, 0, 2f, floatArrayOf(i.toFloat(), 0f, i + 10f, 5f))

    private fun lib() = WebDocLibrary(tmp.newFolder("lib"))

    private fun op(
        seq: Long,
        href: String,
        vararg i: Int,
    ) = WebInkStoreV2.opLine(WebDocLibrary.json, WebJournalOp(seq, href, "add", i.map { stroke(it) }))

    @Test
    fun `v1 file migrates to v2 and reads back identical, keeping a backup`() {
        val library = lib()
        val dir = library.docDir("doc").apply { mkdirs() }
        val v1 =
            WebDocAnnotations(
                strokes = mapOf("a.html" to listOf(stroke(1), stroke(2)), "b.html" to listOf(stroke(3))),
                lastPage = "b.html",
                scrollY = mapOf("a.html" to 120f),
                layoutWidth = mapOf("a.html" to 640f),
                penMode = PenMode.INTERACT.name,
            )
        File(dir, "annotations.json").writeText(WebDocLibrary.json.encodeToString(WebDocAnnotations.serializer(), v1))

        val loaded = library.inkStore("doc").load(openForSession = true)
        assertEquals(2, loaded.strokes["a.html"]?.size)
        assertEquals("b.html", loaded.lastPage)
        assertEquals(PenMode.INTERACT.name, loaded.penMode)
        assertFalse(File(dir, "annotations.json").exists())
        assertTrue(File(dir, "annotations.v1.bak.json").isFile)
        assertTrue(File(dir, "state.json").isFile)

        val again = library.loadAnnotations("doc")
        assertEquals(v1.strokes["a.html"]!![1].points.toList(), again.strokes["a.html"]!![1].points.toList())
        assertEquals(120f, again.scrollY["a.html"])
        assertEquals(640f, again.layoutWidth["a.html"])
        assertEquals(1, again.strokes["b.html"]?.size)
    }

    @Test
    fun `truncated last journal line is dropped and earlier lines replay`() {
        val library = lib()
        val dir = library.docDir("doc").apply { mkdirs() }
        library.inkStore("doc").write(WebDocAnnotations(strokes = mapOf("a.html" to listOf(stroke(1)))), null, 0)
        val half = op(3, "a.html", 9).dropLast(15)
        File(dir, "ink.journal").writeText(op(1, "a.html", 2) + "\n" + op(2, "a.html", 3) + "\n" + half)

        val loaded = library.inkStore("doc").load(openForSession = true)
        assertEquals(3, loaded.strokes["a.html"]?.size)
        // The session checkpoint folded the journal into the ink files.
        assertEquals(0L, File(dir, "ink.journal").length())
        assertEquals(3, library.loadAnnotations("doc").strokes["a.html"]?.size)
    }

    @Test
    fun `journal entries already in the checkpoint are not applied twice`() {
        val library = lib()
        val dir = library.docDir("doc").apply { mkdirs() }
        library.inkStore("doc").write(WebDocAnnotations(strokes = mapOf("a.html" to listOf(stroke(1), stroke(2)))), null, 2)
        File(dir, "ink.journal").writeText(op(1, "a.html", 1) + "\n" + op(2, "a.html", 2) + "\n" + op(3, "a.html", 3) + "\n")
        assertEquals(3, library.loadAnnotations("doc").strokes["a.html"]?.size)
    }

    @Test
    fun `only dirty pages are rewritten`() {
        val library = lib()
        val store = library.inkStore("doc")
        val ann = WebDocAnnotations(strokes = mapOf("a.html" to listOf(stroke(1)), "b.html" to listOf(stroke(2))))
        store.write(ann, null, 0)
        val inkDir = File(library.docDir("doc"), "ink")
        val fa = File(inkDir, WebInkStoreV2.keyFor("a.html") + ".json")
        val fb = File(inkDir, WebInkStoreV2.keyFor("b.html") + ".json")
        fa.setLastModified(1_000_000L)
        fb.setLastModified(1_000_000L)

        val next = ann.copy(strokes = mapOf("a.html" to listOf(stroke(1), stroke(5)), "b.html" to listOf(stroke(2))))
        store.write(next, setOf("a.html"), 1)
        assertTrue(fa.lastModified() > 1_000_000L)
        assertEquals(1_000_000L, fb.lastModified())
        assertEquals(2, library.loadAnnotations("doc").strokes["a.html"]?.size)
    }

    @Test
    fun `erasing every stroke of a page removes its ink file`() {
        val library = lib()
        val store = library.inkStore("doc")
        store.write(WebDocAnnotations(strokes = mapOf("a.html" to listOf(stroke(1)))), null, 0)
        store.write(WebDocAnnotations(), setOf("a.html"), 1)
        val f = File(library.docDir("doc"), "ink/" + WebInkStoreV2.keyFor("a.html") + ".json")
        assertFalse(f.exists())
        assertEquals(0, library.loadAnnotations("doc").strokes.size)
    }

    @Test
    fun `journal fsync is throttled`() {
        val dir = tmp.newFolder("j")
        var now = 1_000_000L
        val journal = WebInkJournal(File(dir, "ink.journal"), 250) { now }
        repeat(100) { journal.append(it + 1L, "x") }
        assertEquals(1, journal.syncCount)
        now += 300
        journal.append(101, "x")
        assertEquals(2, journal.syncCount)
        journal.resetIfCovered(50)
        assertEquals(101L, journal.lastSeq)
        journal.resetIfCovered(101)
        assertEquals(0L, File(dir, "ink.journal").length())
        journal.close()
    }

    @Test
    fun `a thousand strokes save and reload quickly`() {
        val library = lib()
        val ann = WebDocAnnotations(strokes = mapOf("a.html" to (0 until 1000).map { stroke(it) }))
        val store = library.inkStore("doc")
        store.write(ann, null, 0) // warm up the serializer
        val t0 = System.nanoTime()
        store.write(ann, setOf("a.html"), 1)
        val loaded = library.loadAnnotations("doc")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(1000, loaded.strokes["a.html"]?.size)
        assertTrue("took ${ms}ms", ms < 1000)
    }

    @Test
    fun `store journals strokes so a crash before save keeps them`() {
        val library = lib()
        val first = WebAnnotationStore.acquire(library, "doc")
        first.page("a.html").add(stroke(1))
        first.markInkChanged("a.html", 800f)
        first.page("a.html").add(stroke(2))
        first.markInkChanged("a.html", 800f)
        first.flushJournalForTest()
        // No save(): simulate a kill by reading the disk as another process would.
        assertEquals(2, library.loadAnnotations("doc").strokes["a.html"]?.size)
        WebAnnotationStore.release(first)
    }
}
