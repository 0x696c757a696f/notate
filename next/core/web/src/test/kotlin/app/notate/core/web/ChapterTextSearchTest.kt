package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterTextSearchTest {
    private val html = """
        <html><head><title>Hidden Title</title><style>.x{color:red}</style></head>
        <body><h1>Entropy</h1><p>The <b>second</b> law &amp; <i>entropy</i> of an isolated system.</p>
        <script>var entropy = 1;</script><!-- entropy in a comment --></body></html>
    """.trimIndent()

    @Test fun removesTagsScriptsStylesAndComments() {
        val text = ChapterTextSearch.plainText(html)
        assertThat(text).isEqualTo("Entropy The second law & entropy of an isolated system.")
    }

    @Test fun matchesIgnoringCase() {
        val hits = ChapterTextSearch.search("a.xhtml", html, "ENTROPY")!!
        assertThat(hits.count).isEqualTo(2)
        assertThat(hits.snippets).hasSize(2)
        assertThat(hits.href).isEqualTo("a.xhtml")
    }

    @Test fun matchesAcrossInlineTags() {
        val h = "<p>un<b>bound</b>ed <span>sys</span>tem</p>"
        assertThat(ChapterTextSearch.search("c.html", h, "unbounded")?.count).isEqualTo(1)
        assertThat(ChapterTextSearch.search("c.html", h, "system")?.count).isEqualTo(1)
    }

    @Test fun blockTagsSeparateWords() {
        val h = "<p>one</p><p>two</p>"
        assertThat(ChapterTextSearch.search("c.html", h, "onetwo")).isNull()
        assertThat(ChapterTextSearch.search("c.html", h, "one two")?.count).isEqualTo(1)
    }

    @Test fun decodesNumericEntitiesAndIgnoresEmptyQuery() {
        assertThat(ChapterTextSearch.search("c.html", "<p>caf&#233; &#x41;</p>", "café a")?.count).isEqualTo(1)
        assertThat(ChapterTextSearch.search("c.html", html, "  ")).isNull()
        assertThat(ChapterTextSearch.search("c.html", html, "zzz")).isNull()
    }
}
