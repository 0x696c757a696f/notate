@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package app.notate.core.legacy

import app.notate.core.model.Clock
import app.notate.core.model.DocId
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Builds old-format containers with the copied DTOs (the layout is documented in LegacyNotateReader). */
object LegacyFixtures {
    val clock = object : Clock { override fun now() = 1_000L }
    fun docIds(): DocIdSource { var n = 0; return DocIdSource { DocId("DOC%023d".format(++n)) } }

    fun stroke(
        points: List<Triple<Float, Float, Float>>, type: LegacyStrokeType = LegacyStrokeType.FINELINER,
        width: Float = 10f, order: Long = 1, color: Int = 0xFF000000.toInt(),
    ) = LegacyStrokeData(
        pointsPacked = FloatArray(points.size * 6) { i -> val p = points[i / 6]; when (i % 6) { 0 -> p.first; 1 -> p.second; 2 -> p.third; 3 -> 1f; else -> 0f } },
        timestampsPacked = LongArray(points.size) { 5_000L + it * 10L }, color = color, width = width, style = type, strokeOrder = order,
    )

    fun region(x: Int, y: Int, strokes: List<LegacyStrokeData> = emptyList(), images: List<LegacyImageData> = emptyList(),
               texts: List<LegacyTextData> = emptyList(), links: List<LegacyLinkData> = emptyList()) =
        LegacyRegionProto(x, y, strokes, images, texts, links)

    fun zip(file: File, manifest: LegacyCanvasData, regions: List<LegacyRegionProto>, extra: Map<String, ByteArray> = emptyMap(), badRegions: List<String> = emptyList()): ZipFile {
        ZipOutputStream(file.outputStream()).use { z ->
            fun put(name: String, bytes: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() }
            put("manifest.bin", ProtoBuf.encodeToByteArray(LegacyCanvasData.serializer(), manifest))
            for (r in regions) put("r_${r.idX}_${r.idY}.bin", ProtoBuf.encodeToByteArray(LegacyRegionProto.serializer(), r))
            for (b in badRegions) put(b, byteArrayOf(0x0A, 0x7F, 0x01))
            for ((n, b) in extra) put(n, b)
        }
        return ZipFile(file)
    }
}
