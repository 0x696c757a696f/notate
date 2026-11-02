package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WebResourcePathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `resolves files inside the root and rejects traversal`() {
        val root = tmp.newFolder("content")
        File(root, "dir").mkdirs()
        File(root, "dir/a b.js").writeText("1")
        File(root, "index.html").writeText("<html/>")
        tmp.newFile("secret.txt").writeText("no")

        assertThat(WebResourcePaths.resolve(root, "/dir/a%20b.js")?.canonicalFile).isEqualTo(File(root, "dir/a b.js").canonicalFile)
        assertThat(WebResourcePaths.resolve(root, "/")?.canonicalFile).isEqualTo(File(root, "index.html").canonicalFile)
        assertThat(WebResourcePaths.resolve(root, "dir/a%20b.js")).isNotNull()
        for (bad in listOf("/../secret.txt", "/%2e%2e/secret.txt", "/..%2fsecret.txt", "/dir/..%5c..%5csecret.txt", "//etc/passwd",
            "/index.html%00.txt", "/missing.css", "/dir/../../secret.txt", "C:/Windows/x")) {
            assertThat(WebResourcePaths.resolve(root, bad)).isNull()
        }
    }

    @Test fun `isUnsafe flags encoded slashes, NUL, absolute and dotdot`() {
        assertThat(WebResourcePaths.isUnsafe("a/b/c.html")).isFalse()
        assertThat(WebResourcePaths.isUnsafe("a%20b.html")).isFalse()
        assertThat(WebResourcePaths.isUnsafe("../x")).isTrue()
        assertThat(WebResourcePaths.isUnsafe("a%2Fb")).isTrue()
        assertThat(WebResourcePaths.isUnsafe("a\u0000b")).isTrue()
        assertThat(WebResourcePaths.isUnsafe("/etc/passwd")).isTrue()
        assertThat(WebResourcePaths.isUnsafe("a\\..\\b")).isTrue()
    }

    @Test fun `symlink out of the root is refused`() {
        val root = tmp.newFolder("content")
        val outside = tmp.newFile("outside.txt").also { it.writeText("x") }
        val link = File(root, "link.txt")
        val made = runCatching { java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (made) assertThat(WebResourcePaths.resolve(root, "/link.txt")).isNull()
    }

    @Test fun `mime table`() {
        assertThat(WebResourcePaths.mimeType(File("a.mjs"))).isEqualTo("text/javascript")
        assertThat(WebResourcePaths.mimeType(File("c.xhtml"))).isEqualTo("application/xhtml+xml")
        assertThat(WebResourcePaths.mimeType(File("f.WOFF2"))).isEqualTo("font/woff2")
        assertThat(WebResourcePaths.mimeType(File("m.wasm"))).isEqualTo("application/wasm")
        assertThat(WebResourcePaths.mimeType(File("i.svg"))).isEqualTo("image/svg+xml")
        assertThat(WebResourcePaths.mimeType(File("x.bin"))).isEqualTo("application/octet-stream")
    }
}
