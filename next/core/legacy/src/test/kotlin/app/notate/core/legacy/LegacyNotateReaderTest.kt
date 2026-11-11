package app.notate.core.legacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyNotateReaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pts = listOf(Triple(10f, 20f, 2048f), Triple(30f, 40f, 4096f))

    @Test fun readsManifestRegionsAndImages() {
        val abs = tmp.newFile("abs.png").apply { writeBytes(byteArrayOf(9, 9, 9)) }
        val manifest = LegacyCanvasData(
            canvasType = LegacyCanvasType.FIXED_PAGES, pageWidth = 2480f, pageHeight = 3508f, uuid = "u-1",
            backgroundStyle = LegacyBackground.Grid(color = 0xFF112233.toInt(), spacing = 60f, thickness = 2f),
            tagIds = listOf("t1"), tagDefinitions = listOf(LegacyTag("t1", "Physics", 0xFF00FF00.toInt())),
        )
        val img = { uri: String -> LegacyImageData(uri, 0f, 0f, 100f, 100f, 0f, 1) }
        val z = LegacyFixtures.zip(
            tmp.newFile("a.notate"), manifest,
            listOf(
                LegacyFixtures.region(0, 0, strokes = listOf(LegacyFixtures.stroke(pts)), images = listOf(img("images/a.png"), img(abs.absolutePath), img("content://x/y"), img("images/nope.png"))),
                LegacyFixtures.region(-1, 2, strokes = listOf(LegacyFixtures.stroke(pts))),
            ),
            extra = mapOf("images/a.png" to byteArrayOf(1, 2, 3), "index.bin" to byteArrayOf()),
        )
        z.use {
            val c = LegacyNotateReader.read(it)
            assertThat(c.data.uuid).isEqualTo("u-1")
            assertThat(c.data.canvasType).isEqualTo(LegacyCanvasType.FIXED_PAGES)
            assertThat(c.data.backgroundStyle).isEqualTo(manifest.backgroundStyle)
            assertThat(c.data.tagDefinitions.single().name).isEqualTo("Physics")
            assertThat(c.regions.map { r -> r.idX to r.idY }).containsExactly(0 to 0, -1 to 2).inOrder()
            assertThat(c.assets.keys).containsExactly("images/a.png", abs.absolutePath)
            assertThat(c.assets["images/a.png"]!!.assetId.value).hasLength(32)
            assertThat(c.assets["images/a.png"]!!.extension).isEqualTo("png")
            assertThat(c.openAsset("images/a.png")!!.use { s -> s.readBytes() }).isEqualTo(byteArrayOf(1, 2, 3))
            assertThat(c.warnings.filter { w -> w.startsWith("Image not found") }).hasSize(2)
        }
    }

    @Test fun corruptRegionIsSkippedWithWarning() {
        LegacyFixtures.zip(tmp.newFile("b.notate"), LegacyCanvasData(), listOf(LegacyFixtures.region(0, 0, strokes = listOf(LegacyFixtures.stroke(pts)))), badRegions = listOf("r_3_3.bin")).use {
            val c = LegacyNotateReader.read(it)
            assertThat(c.regions).hasSize(1)
            assertThat(c.warnings.single()).contains("r_3_3.bin")
        }
    }

    @Test fun missingOrBrokenManifestThrows() {
        val empty = tmp.newFile("c.notate")
        java.util.zip.ZipOutputStream(empty.outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("r_0_0.bin")); z.closeEntry() }
        java.util.zip.ZipFile(empty).use { assertThrows(LegacyFormatException::class.java) { LegacyNotateReader.read(it) } }
    }

    @Test fun readsUnpackedDirectory() {
        val dir = tmp.newFolder("session")
        File(dir, "manifest.bin").writeBytes(kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(LegacyCanvasData.serializer(), LegacyCanvasData(uuid = "d")))
        File(dir, "images").mkdirs(); File(dir, "images/p.jpg").writeBytes(byteArrayOf(7))
        File(dir, "r_0_0.bin").writeBytes(kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(LegacyRegionProto.serializer(),
            LegacyFixtures.region(0, 0, images = listOf(LegacyImageData("images/p.jpg", 0f, 0f, 5f, 5f, 0f, 1)))))
        val c = LegacyNotateReader.read(dir)
        assertThat(c.data.uuid).isEqualTo("d")
        assertThat(c.assets["images/p.jpg"]!!.extension).isEqualTo("jpg")
    }

    @Test fun movedContainerFallsBackToImagesEntryByFileName() {
        LegacyFixtures.zip(tmp.newFile("d.notate"), LegacyCanvasData(),
            listOf(LegacyFixtures.region(0, 0, images = listOf(LegacyImageData("/data/user/0/old/files/s/images/q.png", 0f, 0f, 5f, 5f, 0f, 1)))),
            extra = mapOf("images/q.png" to byteArrayOf(4))).use {
            assertThat(LegacyNotateReader.read(it).assets).hasSize(1)
        }
    }

    @Test fun peekReadsUuidWithoutTheRestOfTheContainer() {
        val bytes = kotlinx.serialization.protobuf.ProtoBuf.encodeToByteArray(LegacyCanvasData.serializer(), LegacyCanvasData(uuid = "u-9"))
        assertThat(LegacyNotateReader.peek(bytes).uuid).isEqualTo("u-9")
        assertThrows(LegacyFormatException::class.java) { LegacyNotateReader.peek(byteArrayOf(0x0A, 0x7F, 0x01)) }
    }
}
