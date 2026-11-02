package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EpubParserTest {
    @get:Rule val tmp = TemporaryFolder()
    private val parser = EpubParser(WebFixtures.parserFactory)

    private fun dir(files: Map<String, String>): File = tmp.newFolder().also { WebFixtures.write(it, files) }

    @Test fun `EPUB3 nav gives title, author, cover, spine and nested toc`() {
        val info = parser.parse(dir(WebFixtures.epubFiles()))
        assertThat(info.title).isEqualTo("交互物理")
        assertThat(info.author).isEqualTo("Dr. Lin")
        assertThat(info.coverHref).isEqualTo("OEBPS/img/cover.png")
        assertThat(info.fixedLayout).isFalse()
        assertThat(info.spine.map { it.href to it.linear }).containsExactly(
            "OEBPS/text/ch 1.xhtml" to true, "OEBPS/text/notes.xhtml" to false, "OEBPS/text/ch2.xhtml" to true,
        ).inOrder()
        assertThat(info.toc.map { it.title }).containsExactly("第一章\u00A0运动学", "第二章 动力学").inOrder()
        assertThat(info.toc[0].href).isEqualTo("OEBPS/text/ch 1.xhtml")
        assertThat(info.toc[0].children.map { it.title to it.href }).containsExactly("1.1 速度" to "OEBPS/text/ch 1.xhtml#s1")
        assertThat(info.toc[1].href).isEqualTo("OEBPS/text/ch2.xhtml#sec")
    }

    @Test fun `EPUB2 NCX gives nested toc and meta cover`() {
        val info = parser.parse(dir(WebFixtures.epubFiles(epub2 = true)))
        assertThat(info.toc.map { it.title }).containsExactly("Chapter One", "Chapter Two").inOrder()
        assertThat(info.toc[0].children.single().title).isEqualTo("Section 1.1")
        assertThat(info.toc[0].children.single().href).isEqualTo("OEBPS/text/ch 1.xhtml#s1")
        assertThat(info.coverHref).isEqualTo("OEBPS/img/cover.png")
    }

    @Test fun `fixed layout reads rendition viewport`() {
        val info = parser.parse(dir(WebFixtures.epubFiles(fixed = true)))
        assertThat(info.fixedLayout).isTrue()
        assertThat(info.viewport).isEqualTo(1200 to 1600)
    }

    @Test fun `fixed layout falls back to the page viewport meta`() {
        val files = WebFixtures.epubFiles().toMutableMap()
        files["OEBPS/content.opf"] = WebFixtures.opf().replace("<metadata", "<metadata").replace(
            "<dc:creator>", "<meta name=\"fixed-layout\" content=\"true\"/><dc:creator>",
        )
        files["OEBPS/text/ch 1.xhtml"] = "<html><head><meta name=\"viewport\" content=\"width=800, height=1280\"/></head></html>"
        assertThat(parser.parse(dir(files)).viewport).isEqualTo(800 to 1280)
    }

    @Test fun `no toc is fine`() {
        val files = linkedMapOf(
            "META-INF/container.xml" to WebFixtures.CONTAINER.replace("OEBPS/content.opf", "book.opf"),
            "book.opf" to """<package><metadata/><manifest><item id="a" href="a.html" media-type="text/html"/></manifest><spine><itemref idref="a"/></spine></package>""",
        )
        val info = parser.parse(dir(files))
        assertThat(info.toc).isEmpty()
        assertThat(info.spine.single().href).isEqualTo("a.html")
        assertThat(info.title).isNull()
    }

    @Test fun `missing container or empty spine is an IOException`() {
        assertThrows(IOException::class.java) { parser.parse(dir(mapOf("a.html" to "x"))) }
        val files = mapOf(
            "META-INF/container.xml" to WebFixtures.CONTAINER.replace("OEBPS/content.opf", "book.opf"),
            "book.opf" to "<package><manifest/><spine/></package>",
        )
        assertThrows(IOException::class.java) { parser.parse(dir(files)) }
    }

    @Test fun `path helpers never climb above the root`() {
        assertThat(WebResourcePaths.normalize("a/../../b/./c.html")).isEqualTo("b/c.html")
        assertThat(EpubParser.resolve("OEBPS/text", "../img/x%20y.png#frag")).isEqualTo("OEBPS/img/x y.png")
        assertThat(EpubParser.resolve("OEBPS", "/abs.html")).isEqualTo("abs.html")
        assertThat(EpubParser.resolveKeepFragment("OEBPS", "a%20b.xhtml#p%203")).isEqualTo("OEBPS/a b.xhtml#p 3")
    }

    @Test fun `viewport parsing`() {
        assertThat(EpubParser.parseViewport("width=1000,height=1400")).isEqualTo(1000 to 1400)
        assertThat(EpubParser.parseViewport("width=device-width")).isNull()
    }
}
