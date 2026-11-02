package app.notate.core.web

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ZipSafetyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipFile(entries: Map<String, String>) = tmp.newFile().also { it.writeBytes(WebFixtures.zip(entries)) }

    @Test fun `normal archive extracts`() {
        val out = tmp.newFolder("out")
        ZipSafety.extract(zipFile(mapOf("a/b.txt" to "hi", "c.html" to "<html/>")), out)
        assertThat(File(out, "a/b.txt").readText()).isEqualTo("hi")
        assertThat(File(out, "c.html").exists()).isTrue()
    }

    @Test fun `zip slip is rejected`() {
        val out = tmp.newFolder("out")
        val e = assertThrows(WebImportException::class.java) { ZipSafety.extract(zipFile(mapOf("../evil.txt" to "x")), out) }
        assertThat(e.reason).isEqualTo(WebImportException.Reason.UNSAFE)
        assertThat(File(out.parentFile, "evil.txt").exists()).isFalse()
        assertThrows(WebImportException::class.java) { ZipSafety.extract(zipFile(mapOf("a/../../evil.txt" to "x")), tmp.newFolder("o2")) }
    }

    @Test fun `too many files is rejected`() {
        val z = zipFile((1..5).associate { "f$it.txt" to "x" })
        val e = assertThrows(WebImportException::class.java) { ZipSafety.extract(z, tmp.newFolder("o"), ImportLimits(maxFiles = 4)) }
        assertThat(e.reason).isEqualTo(WebImportException.Reason.TOO_LARGE)
    }

    @Test fun `total and single file size limits count actual bytes`() {
        val z = zipFile(mapOf("a.txt" to "x".repeat(600), "b.txt" to "y".repeat(600)))
        val total = assertThrows(WebImportException::class.java) { ZipSafety.extract(z, tmp.newFolder("o1"), ImportLimits(maxTotalBytes = 1000)) }
        assertThat(total.reason).isEqualTo(WebImportException.Reason.TOO_LARGE)
        val single = assertThrows(WebImportException::class.java) { ZipSafety.extract(z, tmp.newFolder("o2"), ImportLimits(maxFileBytes = 500)) }
        assertThat(single.reason).isEqualTo(WebImportException.Reason.TOO_LARGE)
        ZipSafety.extract(z, tmp.newFolder("o3"), ImportLimits(maxTotalBytes = 1200, maxFileBytes = 600))
    }

    @Test fun `not a zip is NOT_SUPPORTED`() {
        val f = tmp.newFile().also { it.writeText("hello") }
        val e = assertThrows(WebImportException::class.java) { ZipSafety.extract(f, tmp.newFolder("o")) }
        assertThat(e.reason).isEqualTo(WebImportException.Reason.NOT_SUPPORTED)
    }

    @Test fun `hash is stable and content dependent`() {
        val a = tmp.newFile().also { it.writeText("abc") }
        assertThat(ZipSafety.sha256OfFile(a)).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        val d1 = tmp.newFolder("d1").also { File(it, "x.txt").writeText("1") }
        val d2 = tmp.newFolder("d2").also { File(it, "x.txt").writeText("1") }
        assertThat(ZipSafety.sha256OfTree(d1)).isEqualTo(ZipSafety.sha256OfTree(d2))
        File(d2, "y.txt").writeText("2")
        assertThat(ZipSafety.sha256OfTree(d1)).isNotEqualTo(ZipSafety.sha256OfTree(d2))
    }

    @Test fun `copyLimited enforces the budget on folder copies`() {
        val budget = ImportBudget(ImportLimits(maxFiles = 1))
        ZipSafety.copyLimited("a".byteInputStream(), File(tmp.root, "one"), budget)
        assertThrows(WebImportException::class.java) { ZipSafety.copyLimited("b".byteInputStream(), File(tmp.root, "two"), budget) }
    }
}
