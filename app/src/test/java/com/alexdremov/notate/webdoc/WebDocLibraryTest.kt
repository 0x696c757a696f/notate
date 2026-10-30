package com.alexdremov.notate.webdoc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDocLibraryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `imports epub and reimport returns the same document`() {
        val lib = WebDocLibrary(tmp.newFolder("lib"))
        val bytes = WebDocTestFixtures.epub()
        val meta = lib.importFile("book.epub", { bytes.inputStream() })
        assertEquals(WebDocKind.EPUB, meta.kind)
        assertEquals("交互物理", meta.title)
        assertTrue(File(lib.contentDir(meta.id), "OEBPS/js/app.mjs").isFile)

        val again = lib.importFile("copy.epub", { bytes.inputStream() })
        assertEquals(meta.id, again.id)
        assertEquals(1, lib.list().size)
    }

    @Test
    fun `imports single html file with its title`() {
        val lib = WebDocLibrary(tmp.newFolder("lib"))
        val html = "<html><head><title> 单摆 模拟 </title></head><body><canvas></canvas><script>1</script></body></html>"
        val meta = lib.importFile("pendulum.htm", { html.byteInputStream() })
        assertEquals(WebDocKind.HTML, meta.kind)
        assertEquals("单摆 模拟", meta.title)
        assertEquals("pendulum.htm", meta.pages.single().href)
    }

    @Test
    fun `zip site picks the shallowest index html`() {
        val lib = WebDocLibrary(tmp.newFolder("lib"))
        val zip =
            WebDocTestFixtures.zip(
                linkedMapOf(
                    "site/docs/index.html" to "<html/>",
                    "site/index.html" to "<html><title>Site</title></html>",
                    "site/js/main.js" to "",
                    "__MACOSX/site/index.html" to "",
                ),
            )
        val meta = lib.importFile("site.zip", { zip.inputStream() })
        assertEquals("site/index.html", meta.pages.single().href)
        assertEquals("Site", meta.title)
    }

    @Test
    fun `imports a folder and ignores escaping paths`() {
        val base = tmp.newFolder("lib")
        val lib = WebDocLibrary(base)
        val files =
            sequenceOf<Pair<String, () -> java.io.InputStream>>(
                "index.html" to { "<html><title>Lab</title></html>".byteInputStream() },
                "lib/katex.min.js" to { "k".byteInputStream() },
                "../evil.txt" to { "x".byteInputStream() },
            )
        val meta = lib.importFolder("lab", files)
        assertEquals("Lab", meta.title)
        assertTrue(File(lib.contentDir(meta.id), "lib/katex.min.js").isFile)
        // "../evil.txt" normalises to "evil.txt" inside the content root, never outside it.
        assertTrue(!File(base.parentFile, "evil.txt").exists())
    }

    @Test
    fun `annotations and policy round trip`() {
        val lib = WebDocLibrary(tmp.newFolder("lib"))
        val meta = lib.importFile("a.html", { "<html/>".byteInputStream() })
        val stroke = WebInkStroke(WebInkTool.HIGHLIGHTER, 0x60FFD500, 14f, floatArrayOf(1f, 2f, 3f, 4f))
        lib.saveAnnotations(meta.id, WebDocAnnotations(strokes = mapOf("a.html" to listOf(stroke)), lastPage = "a.html", scrollY = mapOf("a.html" to 120f)))
        lib.saveMeta(meta.copy(policy = WebDocPolicy(networkAllowed = true)))

        val loaded = lib.loadAnnotations(meta.id)
        assertEquals("a.html", loaded.lastPage)
        assertEquals(120f, loaded.scrollY["a.html"]!!, 0f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f), loaded.strokes["a.html"]!!.single().points, 0f)
        assertTrue(lib.loadMeta(meta.id)!!.policy.networkAllowed)

        assertTrue(lib.delete(meta.id))
        assertTrue(lib.list().isEmpty())
    }
}
