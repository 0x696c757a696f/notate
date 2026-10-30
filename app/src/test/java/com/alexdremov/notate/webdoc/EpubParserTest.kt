package com.alexdremov.notate.webdoc

import com.alexdremov.notate.util.ZipUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EpubParserTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun extract(bytes: ByteArray): File {
        val zip = tmp.newFile("book.epub").apply { writeBytes(bytes) }
        return tmp.newFolder("book").also { ZipUtils.unzip(zip, it) }
    }

    @Test
    fun `reads title, linear spine and nav labels`() {
        val result = EpubParser.parse(extract(WebDocTestFixtures.epub()))
        assertEquals("交互物理", result.title)
        assertFalse(result.fixedLayout)
        assertEquals(listOf("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml"), result.pages.map { it.href })
        assertEquals("第一章 运动学", result.pages[0].title.replace(' ', ' '))
        assertEquals("第二章 动力学", result.pages[1].title)
    }

    @Test
    fun `detects fixed layout`() {
        assertTrue(EpubParser.parse(extract(WebDocTestFixtures.epub(fixed = true))).fixedLayout)
    }

    @Test
    fun `falls back to numbered titles without nav`() {
        val root = tmp.newFolder("plain")
        File(root, "META-INF").mkdirs()
        File(root, "META-INF/container.xml").writeText(WebDocTestFixtures.CONTAINER.replace("OEBPS/content.opf", "book.opf"))
        File(root, "book.opf").writeText(
            """<package><metadata/><manifest><item id="a" href="a.html" media-type="text/html"/></manifest><spine><itemref idref="a"/></spine></package>""",
        )
        val result = EpubParser.parse(root)
        assertEquals(listOf(WebDocPage("a.html", "第 1 节")), result.pages)
    }

    @Test
    fun `path helpers never climb above the root`() {
        assertEquals("b/c.html", EpubParser.normalizePath("a/../../b/./c.html"))
        assertEquals("OEBPS/img/x y.png", EpubParser.resolve("OEBPS/text", "../img/x%20y.png#frag"))
        assertEquals("abs.html", EpubParser.resolve("OEBPS", "/abs.html"))
    }
}
