package app.notate.core.store.pkg

import app.notate.core.model.DocId
import app.notate.core.model.ImageItem
import app.notate.core.model.ItemId
import app.notate.core.model.PageId
import app.notate.core.model.edit.Edit
import app.notate.core.store.AssetKind
import app.notate.core.store.DocStore
import app.notate.core.store.doc.SqliteDocStoreFactory
import app.notate.core.testing.FakeClock
import app.notate.core.testing.JdbcSqlDriverFactory
import app.notate.core.testing.meta
import app.notate.core.testing.page
import app.notate.core.testing.rect
import app.notate.core.testing.stroke
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class PackageWriterReaderTest {
    private val drivers = JdbcSqlDriverFactory()
    private val factory = SqliteDocStoreFactory(drivers, { Dispatchers.IO }, FakeClock(1000))
    private val reader = PackageReader(drivers)
    private fun tmp(): File = Files.createTempDirectory("pkg").toFile()

    private fun bin(n: Int) = File.createTempFile("asset", ".bin").apply { writeBytes(ByteArray(300) { (it + n).toByte() }) }

    private fun buildDoc(): DocStore = runBlocking {
        val s = factory.create(File(tmp(), "doc"), meta(title = "Algebra"), listOf(page(1)))
        val used = s.putAsset(AssetKind.IMAGE, "image/png", bin(1), "png")
        s.putAsset(AssetKind.IMAGE, "image/png", bin(2), "png")   // never referenced
        s.write(listOf(Edit.Items(PageId(1), emptyList(), listOf(stroke(1), ImageItem(ItemId(2), 2, used, rect(0f, 0f, 5f, 5f), 0f, 1f, false, rect(0f, 0f, 5f, 5f))))))
        s.vacuumAssets()
        s
    }

    private fun pack(s: DocStore, tags: List<String> = listOf("math")): File = runBlocking {
        val out = ByteArrayOutputStream()
        PackageWriter.write(s, s.readMeta(), tags, out, now = 5000)
        File.createTempFile("pkg", ".notate").apply { writeBytes(out.toByteArray()) }
    }

    @Test fun writesManifestDbAndOnlyReferencedAssets() {
        val s = buildDoc()
        val zip = pack(s)
        val names = ZipFile(zip).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertThat(names.first()).isEqualTo("manifest.json")
        assertThat(names).contains("doc.db")
        assertThat(names.filter { it.startsWith("assets/") }).hasSize(1)
        assertThat(reader.isNotatePackage(zip)).isTrue()
        val info = reader.inspect(zip)
        assertThat(info.title).isEqualTo("Algebra")
        assertThat(info.tags).containsExactly("math")
        assertThat(info.exportedAt).isEqualTo(5000)
        assertThat(info.assetBytes).isEqualTo(300)
        s.close()
    }

    @Test fun peekReadsManifestFromStream() {
        val s = buildDoc()
        val zip = pack(s)
        val info = zip.inputStream().use { reader.peek(it) }!!
        assertThat(info.title).isEqualTo("Algebra")
        assertThat(info.docId).isEqualTo(s.let { runBlocking { it.readMeta().id.value } })
        assertThat(File.createTempFile("junk", ".bin").apply { writeText("hello") }.inputStream().use { reader.peek(it) }).isNull()
        s.close()
    }

    @Test fun extractRoundTripsContent() = runBlocking<Unit> {
        val s = buildDoc()
        val zip = pack(s)
        val target = File(tmp(), "restored")
        reader.extract(zip, target)
        assertThat(File(target, "manifest.json").exists()).isFalse()
        val r = factory.open(target)
        assertThat(r.readMeta()).isEqualTo(s.readMeta())
        assertThat(r.readPages()).isEqualTo(s.readPages())
        assertThat(r.readItems(PageId(1))).isEqualTo(s.readItems(PageId(1)))
        val img = r.readItems(PageId(1)).filterIsInstance<ImageItem>().single()
        assertThat(r.assetFile(img.asset)!!.readBytes()).isEqualTo(s.assetFile(img.asset)!!.readBytes())
        r.close(); s.close()
    }

    @Test fun extractRewritesDocId() = runBlocking<Unit> {
        val s = buildDoc()
        val target = File(tmp(), "copy")
        reader.extract(pack(s), target, newDocId = DocId("01NEWDOC00000000000000000B"))
        val r = factory.open(target)
        assertThat(r.readMeta().id.value).isEqualTo("01NEWDOC00000000000000000B")
        assertThat(r.readMeta().title).isEqualTo("Algebra")
        r.close(); s.close()
    }

    @Test fun webAssetFolderIsIncluded() = runBlocking<Unit> {
        val s = factory.create(File(tmp(), "doc"), meta(), listOf(page(1)).map { it.copy(background = app.notate.core.model.Background.Web("a.html", 600f, 1)) })
        val src = tmp(); File(src, "OEBPS").mkdirs(); File(src, "OEBPS/c.xhtml").writeText("<html/>")
        s.putAsset(AssetKind.WEB, "text/html", src, "")
        val names = ZipFile(pack(s)).use { z -> z.entries().asSequence().map { it.name }.toList() }
        assertThat(names).contains("assets/web/OEBPS/c.xhtml")
        s.close()
    }

    @Test fun rejectsNonPackagesAndUnsafeEntries() {
        val plain = File.createTempFile("xxx", ".notate").apply { writeText("not a zip") }
        assertThat(reader.isNotatePackage(plain)).isFalse()
        val legacy = File.createTempFile("legacy", ".notate")
        ZipOutputStream(legacy.outputStream()).use { it.putNextEntry(ZipEntry("canvas.bin")); it.write(1); it.closeEntry() }
        assertThat(reader.isNotatePackage(legacy)).isFalse()
        assertThrows(PackageException::class.java) { reader.inspect(legacy) }

        val evil = File.createTempFile("evil", ".notate")
        val manifest = """{"format":"notate-package","version":1,"docId":"a","title":"t","exportedAt":1}"""
        ZipOutputStream(evil.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json")); it.write(manifest.toByteArray()); it.closeEntry()
            it.putNextEntry(ZipEntry("doc.db")); it.write(1); it.closeEntry()
            it.putNextEntry(ZipEntry("assets/../../escape.txt")); it.write(1); it.closeEntry()
        }
        val target = File(tmp(), "t")
        val ex = assertThrows(PackageException::class.java) { reader.extract(evil, target) }
        assertThat(ex.reason).isEqualTo(PackageException.Reason.UNSAFE)
        assertThat(target.exists()).isFalse()
    }

    @Test fun newerVersionIsRejected() {
        val f = File.createTempFile("ver2", ".notate")
        ZipOutputStream(f.outputStream()).use {
            it.putNextEntry(ZipEntry("manifest.json"))
            it.write("""{"format":"notate-package","version":9,"docId":"a","title":"t","exportedAt":1}""".toByteArray()); it.closeEntry()
        }
        assertThat(assertThrows(PackageException::class.java) { reader.inspect(f) }.reason).isEqualTo(PackageException.Reason.UNSUPPORTED_VERSION)
    }
}
