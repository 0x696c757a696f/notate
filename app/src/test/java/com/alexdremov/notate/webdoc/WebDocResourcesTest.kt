package com.alexdremov.notate.webdoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebDocResourcesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `resolves files inside the root and rejects traversal`() {
        val root = tmp.newFolder("content")
        File(root, "dir").mkdirs()
        File(root, "dir/a b.js").writeText("1")
        File(root, "index.html").writeText("<html/>")
        tmp.newFile("secret.txt").writeText("no")

        assertEquals(File(root, "dir/a b.js").canonicalFile, WebDocResources.resolve(root, "/dir/a%20b.js")?.canonicalFile)
        assertEquals(File(root, "index.html").canonicalFile, WebDocResources.resolve(root, "/")?.canonicalFile)
        assertNull(WebDocResources.resolve(root, "/../secret.txt"))
        assertNull(WebDocResources.resolve(root, "/%2e%2e/secret.txt"))
        assertNull(WebDocResources.resolve(root, "/..%2fsecret.txt"))
        assertNull(WebDocResources.resolve(root, "/missing.css"))
    }

    @Test
    fun `each document gets its own origin`() {
        assertNotEquals(WebDocResources.originFor("aaaaaaaaaaaaaaaa1111"), WebDocResources.originFor("bbbbbbbbbbbbbbbb1111"))
        assertEquals("https://0123456789abcdef.webdoc.notate", WebDocResources.originFor("0123456789ABCDEF99"))
    }

    @Test
    fun `url round trip keeps unicode and spaces`() {
        val id = "0123456789abcdef"
        val url = WebDocResources.urlFor(id, "OEBPS/text/第一章 a.xhtml")
        assertEquals("OEBPS/text/第一章 a.xhtml", WebDocResources.hrefForUrl(id, "$url#p3"))
        assertNull(WebDocResources.hrefForUrl(id, "https://example.com/x.html"))
    }

    @Test
    fun `modules get a javascript mime type`() {
        assertEquals("text/javascript", WebDocResources.mimeType(File("a.mjs")))
        assertEquals("application/xhtml+xml", WebDocResources.mimeType(File("c.xhtml")))
        assertEquals("font/woff2", WebDocResources.mimeType(File("f.WOFF2")))
    }
}
