@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.alexdremov.notate.ocr

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alexdremov.notate.data.CanvasData
import com.alexdremov.notate.data.RegionProto
import com.alexdremov.notate.data.StrokeData
import com.alexdremov.notate.model.StrokeType
import com.alexdremov.notate.ocr.index.OcrBlockEntity
import com.alexdremov.notate.ocr.index.OcrDocumentEntity
import com.alexdremov.notate.ocr.index.OcrIndexDatabase
import com.alexdremov.notate.ocr.index.OcrIndexWriter
import com.alexdremov.notate.ocr.index.OcrSearchNormalizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OcrIndexWriterTest {
    private lateinit var context: Context
    private lateinit var database: OcrIndexDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, OcrIndexDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun ftsStoresChineseSubstringTokensForVerifiedQueries() = runTest {
        val dao = database.dao()
        dao.upsertDocument(OcrDocumentEntity("doc", "project", "/note.notate", "Note", 1, "v", "INDEXED"))
        val text = "这是手写笔记"
        dao.insertBlocks(
            listOf(
                OcrBlockEntity(
                    stableId = "block",
                    documentId = "doc",
                    regionId = "r_0_0",
                    regionHash = "hash",
                    source = OcrTextSource.INK_OCR.name,
                    text = text,
                    normalizedText = OcrSearchNormalizer.normalize(text),
                    searchTokens = OcrSearchNormalizer.searchableTokens(text),
                    confidence = 0.9f,
                    left = 0f,
                    top = 0f,
                    right = 10f,
                    bottom = 10f,
                ),
            ),
        )
        assertThat(dao.getFtsRows()).hasSize(1)
        assertThat(dao.getFtsRows().single().searchTokens).contains("z51997b14")
        assertThat(OcrSearchNormalizer.ftsQuery("写笔")).contains("z51997b14")
    }

    @Test
    fun missingModelKeepsPreviousRegionRowsAndMarksDocumentStale() = runTest {
        val dao = database.dao()
        val documentId = "doc-stale"
        dao.upsertDocument(OcrDocumentEntity(documentId, null, "/note.notate", "Note", 1, "old", "INDEXED"))
        dao.insertBlocks(
            listOf(
                OcrBlockEntity(
                    stableId = "old-block",
                    documentId = documentId,
                    regionId = "r_0_0",
                    regionHash = "old-hash",
                    source = OcrTextSource.INK_OCR.name,
                    text = "previous text",
                    normalizedText = "previous text",
                    searchTokens = "previous text",
                    confidence = 0.8f,
                    left = 1f,
                    top = 2f,
                    right = 3f,
                    bottom = 4f,
                ),
            ),
        )

        val session = File(context.cacheDir, "ocr-stale-test").apply { deleteRecursively(); mkdirs() }
        File(session, "manifest.bin").writeBytes(ProtoBuf.encodeToByteArray(CanvasData.serializer(), CanvasData(uuid = documentId)))
        val stroke =
            StrokeData(
                pointsPacked = floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 10f, 10f, 1f, 1f, 0f, 0f),
                timestampsPacked = longArrayOf(1, 2),
                color = android.graphics.Color.BLACK,
                width = 2f,
                style = StrokeType.BALLPOINT,
            )
        File(session, "r_0_0.bin").writeBytes(
            ProtoBuf.encodeToByteArray(RegionProto.serializer(), RegionProto(0, 0, strokes = listOf(stroke))),
        )

        val outcome = OcrIndexWriter(context, UnavailableEngine, dao).indexSession(session, "/note.notate", 2)
        assertThat(outcome.staleRegions).isEqualTo(1)
        assertThat(dao.getBlocksForRegion(documentId, "r_0_0").map { it.text }).containsExactly("previous text")
        assertThat(dao.getDocument(documentId)?.status).isEqualTo("STALE")
        session.deleteRecursively()
    }

    private object UnavailableEngine : PaddleOcrEngine {
        override val modelInfo = OcrModelInfo()
        override suspend fun recognize(bitmap: Bitmap): List<OcrBlock> = error("Model missing")
        override fun isAvailable(): Boolean = false
        override fun close() = Unit
    }
}
