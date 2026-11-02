package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebImportPlannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val planner = WebImportPlanner(WebFixtures.parserFactory)

    private fun dir(files: Map<String, String>): File = tmp.newFolder().also { WebFixtures.write(it, files) }

    @Test fun `EPUB pages follow the spine and skip linear=no`() {
        val plan = planner.plan(dir(WebFixtures.epubFiles()), WebSourceKind.EPUB, "book.epub")
        assertThat(plan.kind).isEqualTo(WebDocType.EPUB)
        assertThat(plan.pages.map { it.href }).containsExactly("OEBPS/text/ch 1.xhtml", "OEBPS/text/ch2.xhtml").inOrder()
        assertThat(plan.pages.map { it.title }).containsExactly("第一章\u00A0运动学", "第二章 动力学").inOrder()
        assertThat(plan.entryHref).isEqualTo("OEBPS/text/ch 1.xhtml")
        assertThat(plan.title).isEqualTo("交互物理")
        assertThat(plan.author).isEqualTo("Dr. Lin")
        assertThat(plan.coverHref).isEqualTo("OEBPS/img/cover.png")
        assertThat(plan.toc).hasSize(2)
    }

    @Test fun `a ZIP or folder with container xml is an EPUB`() {
        val plan = planner.plan(dir(WebFixtures.epubFiles(epub2 = true)), WebSourceKind.ZIP, "x.zip")
        assertThat(plan.kind).isEqualTo(WebDocType.EPUB)
        assertThat(plan.pages.map { it.title }).containsExactly("Chapter One", "Chapter Two").inOrder()
    }

    @Test fun `EPUB source without container is rejected`() {
        val e = assertThrows(WebImportException::class.java) { planner.plan(dir(mapOf("a.html" to "x")), WebSourceKind.EPUB, "a.epub") }
        assertThat(e.reason).isEqualTo(WebImportException.Reason.NOT_SUPPORTED)
    }

    @Test fun `broken EPUB is NOT_SUPPORTED`() {
        val files = mapOf("META-INF/container.xml" to "<container/>")
        assertThrows(WebImportException::class.java) { planner.plan(dir(files), WebSourceKind.EPUB, "a.epub") }
    }

    @Test fun `html entry prefers index at the shallowest depth`() {
        val root = dir(mapOf("a.html" to "", "sub/index.html" to "", "index.htm" to "", "z/deep/index.html" to ""))
        assertThat(WebImportPlanner.findHtmlEntry(root)).isEqualTo("index.htm")
        val root2 = dir(mapOf("a.html" to "", "sub/index.html" to ""))
        assertThat(WebImportPlanner.findHtmlEntry(root2)).isEqualTo("sub/index.html")
    }

    @Test fun `without index the only root html wins, else the shallowest`() {
        assertThat(WebImportPlanner.findHtmlEntry(dir(mapOf("page.html" to "", "sub/other.html" to "")))).isEqualTo("page.html")
        assertThat(WebImportPlanner.findHtmlEntry(dir(mapOf("b/x.html" to "", "a/y.html" to "", "a/b/z.html" to "")))).isEqualTo("a/y.html")
        assertThat(WebImportPlanner.findHtmlEntry(dir(mapOf("__MACOSX/index.html" to "", "x.txt" to "")))).isNull()
    }

    @Test fun `html plan has one page titled from the title element`() {
        val root = dir(mapOf("site/index.html" to "<html><head><title>\n Hello\n  World </title></head></html>", "site/app.js" to ""))
        val plan = planner.plan(root, WebSourceKind.FOLDER, "folder")
        assertThat(plan.kind).isEqualTo(WebDocType.HTML)
        assertThat(plan.entryHref).isEqualTo("site/index.html")
        assertThat(plan.pages).containsExactly(PlannedPage("site/index.html", "Hello World"))
        assertThat(plan.title).isEqualTo("Hello World")
        assertThat(plan.toc).isEmpty()
        assertThat(plan.coverHref).isNull()
    }

    @Test fun `html without title falls back to the display name`() {
        val plan = planner.plan(dir(mapOf("p.html" to "<html/>")), WebSourceKind.HTML_FILE, "notes.html")
        assertThat(plan.title).isEqualTo("notes")
    }

    @Test fun `folder without html is rejected`() {
        assertThrows(WebImportException::class.java) { planner.plan(dir(mapOf("a.txt" to "x")), WebSourceKind.FOLDER, "f") }
    }
}
