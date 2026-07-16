package com.alexdremov.notate.ocr.index

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.CanvasSerializer
import com.alexdremov.notate.data.PreferencesManager
import com.alexdremov.notate.data.RegionProto
import com.alexdremov.notate.model.Stroke
import com.alexdremov.notate.ocr.OcrBlock
import com.alexdremov.notate.ocr.OcrModelInfo
import com.alexdremov.notate.ocr.OcrTextSource
import com.alexdremov.notate.ocr.PaddleOcrEngine
import com.alexdremov.notate.ocr.PaddleOcrProvider
import com.alexdremov.notate.ocr.StrokeOcrRasterizer
import com.alexdremov.notate.util.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.security.MessageDigest
import kotlin.math.floor

data class OcrIndexOutcome(
    val documentId: String,
    val indexedRegions: Int,
    val unchangedRegions: Int,
    val staleRegions: Int,
)

class OcrIndexWriter(
    context: Context,
    private val engine: PaddleOcrEngine = PaddleOcrProvider.get(context),
    private val dao: OcrIndexDao = OcrIndexDatabase.get(context).dao(),
) {
    private val appContext = context.applicationContext

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun indexSession(
        sessionDir: File,
        targetPath: String,
        lastModified: Long = System.currentTimeMillis(),
    ): OcrIndexOutcome {
        val manifestFile = File(sessionDir, "manifest.bin")
        check(manifestFile.isFile) { "Missing canvas manifest" }
        val manifest = ProtoBuf.decodeFromByteArray(CanvasData.serializer(), manifestFile.readBytes())
        val documentId = manifest.uuid ?: sha256(targetPath.toByteArray())
        val name = noteName(targetPath)
        val projectId = projectIdFor(targetPath)
        val modelVersion = engine.modelInfo.indexVersion
        val previous = dao.getDocument(documentId)

        dao.upsertDocument(
            OcrDocumentEntity(
                documentId = documentId,
                projectId = projectId,
                path = targetPath,
                name = name,
                lastModified = lastModified,
                modelVersion = modelVersion,
                status = STATUS_INDEXING,
                indexedAt = previous?.indexedAt ?: 0L,
            ),
        )

        replaceFilename(documentId, name, modelVersion)
        var indexed = 0
        var unchanged = 0
        var stale = 0
        val liveRegionIds = mutableSetOf(FILENAME_REGION)

        sessionDir.listFiles { file -> file.isFile && REGION_FILE.matches(file.name) }
            .orEmpty()
            .sortedBy(File::getName)
            .forEach { file ->
                val regionId = file.nameWithoutExtension
                liveRegionIds += regionId
                val bytes = file.readBytes()
                val regionHash = sha256(bytes, modelVersion.toByteArray())
                if (dao.getRegionHash(documentId, regionId) == regionHash) {
                    unchanged++
                    return@forEach
                }

                val region = runCatching {
                    ProtoBuf.decodeFromByteArray(RegionProto.serializer(), bytes)
                }.getOrElse { error ->
                    stale++
                    Logger.e("OcrIndex", "Unable to decode $regionId", error)
                    return@forEach
                }

                val blocks = runCatching { buildRegionBlocks(documentId, regionId, regionHash, region) }
                    .getOrElse { error ->
                        stale++
                        Logger.e("OcrIndex", "OCR failed for $regionId; retaining previous rows", error)
                        return@forEach
                    }
                dao.replaceRegion(documentId, regionId, blocks)
                indexed++
            }

        if (stale == 0) {
            (dao.getRegionIds(documentId).toSet() - liveRegionIds).forEach { dao.deleteRegion(documentId, it) }
        }
        dao.upsertDocument(
            OcrDocumentEntity(
                documentId = documentId,
                projectId = projectId,
                path = targetPath,
                name = name,
                lastModified = lastModified,
                modelVersion = modelVersion,
                status = if (stale == 0) STATUS_INDEXED else STATUS_STALE,
                errorMessage = if (stale == 0) null else "$stale region(s) retained from the previous index",
                indexedAt = System.currentTimeMillis(),
            ),
        )
        return OcrIndexOutcome(documentId, indexed, unchanged, stale)
    }

    private suspend fun replaceFilename(
        documentId: String,
        name: String,
        modelVersion: String,
    ) {
        val hash = sha256(name.toByteArray(), modelVersion.toByteArray())
        if (dao.getRegionHash(documentId, FILENAME_REGION) == hash) return
        dao.replaceRegion(
            documentId,
            FILENAME_REGION,
            listOf(block(documentId, FILENAME_REGION, hash, OcrTextSource.FILENAME, name, 1f, null, 0)),
        )
    }

    private suspend fun buildRegionBlocks(
        documentId: String,
        regionId: String,
        regionHash: String,
        region: RegionProto,
    ): List<OcrBlockEntity> {
        val output = mutableListOf<OcrBlockEntity>()
        region.texts.forEachIndexed { index, text ->
            if (text.text.isNotBlank()) {
                output += block(
                    documentId,
                    regionId,
                    regionHash,
                    OcrTextSource.TYPED_TEXT,
                    text.text,
                    1f,
                    RectF(text.x, text.y, text.x + text.width, text.y + text.height),
                    index,
                )
            }
        }

        if (region.strokes.isNotEmpty()) {
            check(engine.isAvailable()) { "PP-OCRv3 runtime is unavailable" }
            val strokes = region.strokes.map(CanvasSerializer::fromStrokeData)
            try {
                val recognized = recognizeTiles(strokes)
                recognized.forEachIndexed { index, recognizedBlock ->
                    output += block(
                        documentId,
                        regionId,
                        regionHash,
                        OcrTextSource.INK_OCR,
                        recognizedBlock.text,
                        recognizedBlock.confidence,
                        recognizedBlock.bounds,
                        index,
                    )
                }
            } finally {
                strokes.forEach(Stroke::recycle)
            }
        }
        return output
    }

    private suspend fun recognizeTiles(strokes: List<Stroke>): List<OcrBlock> {
        if (strokes.isEmpty()) return emptyList()
        val contentBounds = RectF(strokes.first().bounds)
        strokes.drop(1).forEach { contentBounds.union(it.bounds) }
        val firstX = floor(contentBounds.left / TILE_STRIDE).toInt() * TILE_STRIDE
        val firstY = floor(contentBounds.top / TILE_STRIDE).toInt() * TILE_STRIDE
        val results = mutableListOf<OcrBlock>()
        var y = firstY
        while (y < contentBounds.bottom) {
            var x = firstX
            while (x < contentBounds.right) {
                val tile = RectF(x, y, x + TILE_SIZE, y + TILE_SIZE)
                if (strokes.any { RectF.intersects(it.bounds, tile) }) {
                    StrokeOcrRasterizer.render(strokes, tile)?.let { raster ->
                        try {
                            results += engine.recognize(raster.bitmap).map(raster::toWorld)
                        } finally {
                            raster.bitmap.recycle()
                        }
                    }
                }
                x += TILE_STRIDE
            }
            y += TILE_STRIDE
        }
        return OcrBlockDeduplicator.deduplicate(results)
    }

    private fun block(
        documentId: String,
        regionId: String,
        regionHash: String,
        source: OcrTextSource,
        text: String,
        confidence: Float,
        bounds: RectF?,
        index: Int,
    ): OcrBlockEntity {
        val normalized = OcrSearchNormalizer.normalize(text)
        val stableId = sha256("$documentId|$regionId|${source.name}|$index|$normalized".toByteArray())
        return OcrBlockEntity(
            stableId = stableId,
            documentId = documentId,
            regionId = regionId,
            regionHash = regionHash,
            source = source.name,
            text = text,
            normalizedText = normalized,
            searchTokens = OcrSearchNormalizer.searchableTokens(text),
            confidence = confidence,
            left = bounds?.left,
            top = bounds?.top,
            right = bounds?.right,
            bottom = bounds?.bottom,
        )
    }

    private fun projectIdFor(path: String): String? {
        val projects = PreferencesManager.getProjects(appContext)
        projects.filter { path.startsWith(it.uri) }.maxByOrNull { it.uri.length }?.let { return it.id }
        if (!path.startsWith("content://")) return null
        val documentUri = Uri.parse(path)
        val documentId = runCatching { DocumentsContract.getDocumentId(documentUri) }.getOrNull() ?: return null
        return projects.firstOrNull { project ->
            runCatching {
                val projectUri = Uri.parse(project.uri)
                projectUri.authority == documentUri.authority &&
                    documentId.startsWith(DocumentsContract.getTreeDocumentId(projectUri))
            }.getOrDefault(false)
        }?.id
    }

    private fun noteName(path: String): String {
        val raw =
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                DocumentFile.fromSingleUri(appContext, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':')
            } else {
                File(path).name
            }
        return raw.orEmpty().substringAfterLast('/').removeSuffix(".notate").ifBlank { "Untitled" }
    }

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val REGION_FILE = Regex("r_-?\\d+_-?\\d+\\.bin")
        private const val FILENAME_REGION = "__filename__"
        private const val TILE_SIZE = 960f
        private const val TILE_OVERLAP = 96f
        private const val TILE_STRIDE = TILE_SIZE - TILE_OVERLAP
        const val STATUS_INDEXING = "INDEXING"
        const val STATUS_INDEXED = "INDEXED"
        const val STATUS_STALE = "STALE"
    }
}

object OcrBlockDeduplicator {
    fun deduplicate(blocks: List<OcrBlock>): List<OcrBlock> {
        val ordered = blocks.sortedByDescending(OcrBlock::confidence)
        val kept = mutableListOf<OcrBlock>()
        ordered.forEach { candidate ->
            val normalized = OcrSearchNormalizer.normalize(candidate.text)
            val duplicate = kept.any { existing ->
                OcrSearchNormalizer.normalize(existing.text) == normalized && intersectionOverUnion(existing.bounds, candidate.bounds) >= 0.35f
            }
            if (!duplicate) kept += candidate
        }
        return kept.sortedWith(compareBy<OcrBlock> { it.bounds.top }.thenBy { it.bounds.left })
    }

    internal fun intersectionOverUnion(first: RectF, second: RectF): Float {
        val intersection = RectF(first)
        if (!intersection.intersect(second)) return 0f
        val intersectionArea = intersection.width() * intersection.height()
        val unionArea = first.width() * first.height() + second.width() * second.height() - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }
}
